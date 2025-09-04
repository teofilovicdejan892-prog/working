/**
 * P8FS Device Registration Flow - Android Implementation
 * 
 * Based on reference implementation from T1 app
 * Key features:
 * 1. Ed25519 keypair generation using Tink library (Android Keystore doesn't support Ed25519)
 * 2. Secure key storage in EncryptedSharedPreferences
 * 3. Email verification with challenge-response authentication
 * 4. JWT token handling and storage
 * 5. Comprehensive error handling for validation and auth errors
 */

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.coroutines.resume

// MARK: - Data Models

@Serializable
data class RegisterRequest(
    val email: String,
    val public_key: String,
    val device_info: DeviceInfo
)

@Serializable
data class DeviceInfo(
    val device_type: String = "mobile",
    val platform: String = "Android",
    val model: String,
    val os_version: String,
    val app_version: String
)

@Serializable
data class RegisterResponse(
    val registration_id: String,
    val message: String,
    val expires_in: Int
)

@Serializable
data class VerifyRequest(
    val email: String,
    val code: String,
    val challenge: String,
    val signature: String
)

@Serializable
data class VerifyResponse(
    val access_token: String,
    val refresh_token: String,
    val tenant_id: String,
    val expires_in: Int
)

@Serializable
data class AuthError(
    val error: String,
    val message: String
)

@Serializable
data class ValidationErrorDetail(
    val type: String,
    val loc: List<String>,
    val msg: String,
    val input: String? = null
)

@Serializable
data class ValidationError(
    val detail: List<ValidationErrorDetail>
)

@Serializable
data class SimpleError(
    val detail: String
)

/**
 * P8FS Device Registration Manager
 * Handles the complete device registration and verification flow
 */
class P8FSDeviceRegistrationManager(
    private val context: Context,
    private val headersManager: HeadersManager
) {
    companion object {
        private const val TAG = "P8FSDeviceRegistration"
        private const val PREF_EMAIL = "user_email"
        private const val PREF_ACCESS_TOKEN = "access_token"
        private const val PREF_REFRESH_TOKEN = "refresh_token"
        private const val PREF_TENANT_ID = "tenant_id"
        private const val PREF_DEVICE_REGISTERED = "device_registered"
        private const val PREF_ED25519_PUBLIC_KEY = "ed25519_public_key"
        private const val PREF_ED25519_PRIVATE_KEY = "ed25519_private_key"
    }
    
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    private val prefs = AppConfig.getSecurePrefs()
    private val ed25519Crypto = Ed25519CryptoManager()
    
    /**
     * Check if device is already registered and has valid tokens
     */
    fun isDeviceRegistered(): Boolean {
        return prefs.getBoolean(PREF_DEVICE_REGISTERED, false) && 
               prefs.getString(PREF_ACCESS_TOKEN, null) != null
    }
    
    /**
     * Register device with P8FS server
     * Step 1: Generate Ed25519 keypair and send registration request
     */
    suspend fun registerDevice(email: String): Result<RegisterResponse> {
        return try {
            Log.d(TAG, "🚀 Starting device registration for email: $email")
            
            // Step 1: Generate/get Ed25519 device keypair
            val publicKeyBytes = getOrCreateDeviceKeypair()
            val publicKeyEncoded = Base64.getEncoder().encodeToString(publicKeyBytes)
            
            Log.d(TAG, "🔐 Generated Ed25519 public key: ${publicKeyBytes.size} bytes")
            
            // Step 2: Prepare device information
            val deviceInfo = DeviceInfo(
                device_type = "mobile",
                platform = "Android",
                model = android.os.Build.MODEL,
                os_version = android.os.Build.VERSION.RELEASE,
                app_version = getAppVersion()
            )
            
            // Step 3: Create registration request
            val request = RegisterRequest(
                email = email,
                public_key = publicKeyEncoded,
                device_info = deviceInfo
            )
            
            Log.d(TAG, "📱 Device info: ${deviceInfo.model} (${deviceInfo.platform} ${deviceInfo.os_version})")
            
            // Step 4: Send registration request with P8FS headers
            val response = httpClient.post("${AppConfig.baseUrl}/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                
                // Add comprehensive P8FS headers
                headersManager.getBaseHeaders().forEach { (key, value) ->
                    header(key, value)
                }
                
                setBody(request)
            }
            
            Log.d(TAG, "📡 Registration response status: ${response.status}")
            
            // Step 5: Handle response
            when (response.status) {
                HttpStatusCode.Accepted, HttpStatusCode.OK, HttpStatusCode.Created -> {
                    try {
                        val registerResponse = response.body<RegisterResponse>()
                        Log.d(TAG, "✅ Device registration successful: ${registerResponse.message}")
                        Log.d(TAG, "⏰ Expires in: ${registerResponse.expires_in} seconds")
                        
                        // Store email for verification step
                        prefs.edit().putString(PREF_EMAIL, email).apply()
                        
                        Result.success(registerResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing success response", e)
                        Result.failure(Exception("Unexpected response format: ${e.message}"))
                    }
                }
                else -> {
                    val errorMessage = parseErrorResponse(response)
                    Log.e(TAG, "❌ Registration failed: $errorMessage")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Registration error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Verify email code and complete registration
     * Step 2: Submit verification code with cryptographic challenge
     */
    suspend fun verifyEmailCode(code: String): Result<VerifyResponse> {
        return try {
            Log.d(TAG, "🔍 Verifying email code")
            
            // Step 1: Get stored email from registration
            val email = prefs.getString(PREF_EMAIL, null) 
                ?: return Result.failure(Exception("No email found - complete registration first"))
            
            // Step 2: Ensure Ed25519 keys are loaded
            ensureEd25519KeysLoaded()
            
            // Step 3: Create unique challenge (timestamp + random UUID)
            val challenge = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
            Log.d(TAG, "🎯 Created challenge: ${challenge.take(50)}...")
            
            // Step 4: Sign challenge with Ed25519 private key
            val signature = ed25519Crypto.signDataBase64(challenge)
            Log.d(TAG, "✍️ Created signature: ${signature.take(20)}... (${signature.length} chars)")
            
            // Step 5: Create verification request
            val request = VerifyRequest(
                email = email,
                code = code,
                challenge = challenge,
                signature = signature
            )
            
            // Step 6: Send verification request
            val response = httpClient.post("${AppConfig.baseUrl}/api/v1/auth/verify") {
                contentType(ContentType.Application.Json)
                
                // Add comprehensive P8FS headers
                headersManager.getBaseHeaders().forEach { (key, value) ->
                    header(key, value)
                }
                
                setBody(request)
            }
            
            Log.d(TAG, "📡 Verification response status: ${response.status}")
            
            // Step 7: Handle successful verification
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.Created -> {
                    try {
                        val verifyResponse = response.body<VerifyResponse>()
                        Log.d(TAG, "✅ Email verification successful")
                        Log.d(TAG, "🏢 Tenant ID: ${verifyResponse.tenant_id}")
                        
                        // Extract user ID from JWT
                        val userId = JwtUtils.getUserId(verifyResponse.access_token)
                        Log.d(TAG, "👤 User ID from JWT: $userId")
                        
                        // Store tokens and mark as registered
                        prefs.edit().apply {
                            putString(PREF_ACCESS_TOKEN, verifyResponse.access_token)
                            putString(PREF_REFRESH_TOKEN, verifyResponse.refresh_token)
                            putString(PREF_TENANT_ID, verifyResponse.tenant_id)
                            putBoolean(PREF_DEVICE_REGISTERED, true)
                            apply()
                        }
                        
                        // Store auth data in HeadersManager for API calls
                        headersManager.storeAuthData(
                            verifyResponse.access_token,
                            verifyResponse.refresh_token,
                            email,
                            userId,
                            verifyResponse.tenant_id
                        )
                        
                        Log.d(TAG, "💾 Authentication tokens stored successfully")
                        
                        Result.success(verifyResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing verify response", e)
                        Result.failure(Exception("Unexpected response format: ${e.message}"))
                    }
                }
                else -> {
                    val errorMessage = parseErrorResponse(response)
                    Log.e(TAG, "❌ Verification failed: $errorMessage")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Verification error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Generate or retrieve Ed25519 keypair
     * Uses Tink library since Android Keystore doesn't support Ed25519
     */
    private fun getOrCreateDeviceKeypair(): ByteArray {
        // Check if we have stored Ed25519 keys
        val storedPublicKey = prefs.getString(PREF_ED25519_PUBLIC_KEY, null)
        val storedPrivateKey = prefs.getString(PREF_ED25519_PRIVATE_KEY, null)
        
        return if (storedPublicKey != null && storedPrivateKey != null) {
            Log.d(TAG, "🔑 Restoring existing Ed25519 keypair")
            
            // Restore keys from secure storage
            val publicKeyBytes = Base64.getDecoder().decode(storedPublicKey)
            val privateKeyBytes = Base64.getDecoder().decode(storedPrivateKey)
            ed25519Crypto.restoreFromKeys(publicKeyBytes, privateKeyBytes)
            
            publicKeyBytes
        } else {
            Log.d(TAG, "🆕 Generating new Ed25519 keypair")
            
            // Generate new keypair using Tink
            val publicKeyBytes = ed25519Crypto.generateKeypair()
            
            // Store keys securely in EncryptedSharedPreferences
            val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes)
            val privateKeyBytes = ed25519Crypto.getPrivateKeyBytes()
            
            if (privateKeyBytes != null) {
                val privateKeyBase64 = Base64.getEncoder().encodeToString(privateKeyBytes)
                prefs.edit().apply {
                    putString(PREF_ED25519_PUBLIC_KEY, publicKeyBase64)
                    putString(PREF_ED25519_PRIVATE_KEY, privateKeyBase64)
                    apply()
                }
                Log.d(TAG, "🔐 Ed25519 keypair stored securely")
            }
            
            publicKeyBytes
        }
    }
    
    /**
     * Ensure Ed25519 keys are loaded for signing operations
     */
    private fun ensureEd25519KeysLoaded() {
        if (ed25519Crypto.getPublicKeyBytes() == null) {
            getOrCreateDeviceKeypair()
        }
    }
    
    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    /**
     * Authenticate with biometric and get access to stored tokens
     */
    suspend fun authenticateWithBiometric(activity: FragmentActivity): Result<String> {
        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(context)
            val biometricPrompt = BiometricPrompt(
                activity, 
                executor, 
                object : BiometricPrompt.AuthenticationCallback() {
                    
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Log.e(TAG, "❌ Biometric authentication error: $errString")
                        continuation.resume(Result.failure(Exception(errString.toString())))
                    }
                    
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Log.d(TAG, "✅ Biometric authentication successful")
                        
                        // Get stored access token after successful biometric auth
                        val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)
                        if (accessToken != null) {
                            continuation.resume(Result.success(accessToken))
                        } else {
                            continuation.resume(Result.failure(Exception("No access token found")))
                        }
                    }
                    
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Log.w(TAG, "⚠️ Biometric authentication failed - user can retry")
                        // Don't complete continuation here - user can retry
                    }
                }
            )
            
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("P8FS Authentication")
                .setSubtitle("Use your biometric to access P8FS")
                .setNegativeButtonText("Cancel")
                .build()
            
            biometricPrompt.authenticate(promptInfo)
        }
    }
    
    /**
     * Get current access token (without refresh)
     */
    fun getAccessToken(): String? {
        return prefs.getString(PREF_ACCESS_TOKEN, null)
    }
    
    /**
     * Get current user email
     */
    fun getUserEmail(): String? {
        return prefs.getString(PREF_EMAIL, null)
    }
    
    /**
     * Get tenant ID
     */
    fun getTenantId(): String? {
        return prefs.getString(PREF_TENANT_ID, null)
    }
    
    /**
     * Check if access token is expired
     */
    fun isTokenExpired(): Boolean {
        val token = getAccessToken() ?: return true
        return JwtUtils.isExpired(token)
    }
    
    /**
     * Clear all authentication data (logout)
     */
    fun clearAuthData() {
        Log.d(TAG, "🧹 Clearing authentication data")
        
        prefs.edit().apply {
            remove(PREF_EMAIL)
            remove(PREF_ACCESS_TOKEN)
            remove(PREF_REFRESH_TOKEN)
            remove(PREF_TENANT_ID)
            remove(PREF_ED25519_PUBLIC_KEY)
            remove(PREF_ED25519_PRIVATE_KEY)
            putBoolean(PREF_DEVICE_REGISTERED, false)
            apply()
        }
        
        headersManager.clearAuthData()
        ed25519Crypto.clear()
    }
    
    /**
     * Parse error response from server
     */
    private suspend fun parseErrorResponse(response: HttpResponse): String {
        return try {
            // Try to parse as AuthError first
            val error = response.body<AuthError>()
            error.message
        } catch (e: Exception) {
            try {
                // If that fails, try ValidationError format (array)
                val validationError = response.body<ValidationError>()
                val messages = validationError.detail.map { it.msg }
                messages.joinToString("; ")
            } catch (e2: Exception) {
                try {
                    // Try SimpleError format (string detail)
                    val simpleError = response.body<SimpleError>()
                    simpleError.detail
                } catch (e3: Exception) {
                    Log.e(TAG, "❌ Error parsing error response", e3)
                    val responseText = response.bodyAsText()
                    Log.e(TAG, "❌ Raw error response: $responseText")
                    "Request failed: ${response.status}"
                }
            }
        }
    }
    
    private fun getAppVersion(): String = "1.0.0" // TODO: Get from BuildConfig
}

/**
 * Ed25519 Crypto Manager using Tink library
 * Handles Ed25519 keypair generation and signing operations
 */
class Ed25519CryptoManager {
    private var publicKeyBytes: ByteArray? = null
    private var privateKeyBytes: ByteArray? = null
    
    /**
     * Generate Ed25519 keypair using Tink library
     */
    fun generateKeypair(): ByteArray {
        // TODO: Implement using Google Tink library
        // Since Android Keystore doesn't support Ed25519
        
        // Pseudocode:
        // val keysetHandle = KeysetHandle.generateNew(Ed25519PrivateKeyManager.ed25519Template())
        // val publicKey = keysetHandle.getPublicKeysetHandle().getPrimitive(PublicKeySign::class.java)
        // Store keys and return public key bytes
        
        // For now, return placeholder
        val placeholder = ByteArray(32) { it.toByte() }
        this.publicKeyBytes = placeholder
        this.privateKeyBytes = ByteArray(32) { (it + 32).toByte() }
        
        return placeholder
    }
    
    /**
     * Restore keypair from stored bytes
     */
    fun restoreFromKeys(publicBytes: ByteArray, privateBytes: ByteArray) {
        this.publicKeyBytes = publicBytes
        this.privateKeyBytes = privateBytes
    }
    
    /**
     * Sign data with Ed25519 private key and return base64 signature
     */
    fun signDataBase64(data: String): String {
        // TODO: Implement Ed25519 signing using Tink
        // val signature = privateKeySign.sign(data.toByteArray())
        // return Base64.getEncoder().encodeToString(signature)
        
        // For now, return placeholder
        val placeholderSignature = ByteArray(64) { it.toByte() }
        return Base64.getEncoder().encodeToString(placeholderSignature)
    }
    
    fun getPublicKeyBytes(): ByteArray? = publicKeyBytes
    fun getPrivateKeyBytes(): ByteArray? = privateKeyBytes
    
    fun clear() {
        publicKeyBytes = null
        privateKeyBytes = null
    }
}

/**
 * Headers manager for comprehensive P8FS headers
 */
class HeadersManager(private val context: Context) {
    
    fun getBaseHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        // Device Context
        headers["X-Device-Type"] = "mobile"
        headers["X-Device-Platform"] = "Android"
        headers["X-Device-Model"] = android.os.Build.MODEL
        headers["X-Device-Version"] = android.os.Build.VERSION.RELEASE
        headers["X-App-Version"] = "1.0.0"
        headers["X-Platform"] = "Android"
        
        // Mobile-specific
        headers["X-Biometric-Available"] = isBiometricAvailable().toString()
        headers["X-Secure-Enclave"] = "false" // Android uses TEE, not Secure Enclave
        
        return headers
    }
    
    fun storeAuthData(
        accessToken: String,
        refreshToken: String,
        email: String?,
        userId: String?,
        tenantId: String
    ) {
        // Store authentication context for future API calls
        TODO("Implement auth data storage")
    }
    
    fun clearAuthData() {
        // Clear stored authentication context
        TODO("Implement auth data clearing")
    }
    
    private fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
}

// Usage Example:
/*
class RegistrationActivity : AppCompatActivity() {
    
    private lateinit var registrationManager: P8FSDeviceRegistrationManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        registrationManager = P8FSDeviceRegistrationManager(
            context = this,
            headersManager = HeadersManager(this)
        )
        
        // Check if already registered
        if (registrationManager.isDeviceRegistered()) {
            navigateToMainApp()
            return
        }
        
        setupRegistrationUI()
    }
    
    private fun performRegistration(email: String) {
        lifecycleScope.launch {
            val result = registrationManager.registerDevice(email)
            
            result.fold(
                onSuccess = { response ->
                    showMessage("Registration successful! Check your email for verification code.")
                    navigateToVerification(email)
                },
                onFailure = { error ->
                    showError("Registration failed: ${error.message}")
                }
            )
        }
    }
    
    private fun performVerification(code: String) {
        lifecycleScope.launch {
            val result = registrationManager.verifyEmailCode(code)
            
            result.fold(
                onSuccess = { response ->
                    showMessage("Verification successful! Welcome to P8FS.")
                    navigateToMainApp()
                },
                onFailure = { error ->
                    showError("Verification failed: ${error.message}")
                }
            )
        }
    }
}
*/