/**
 * P8FS Device Approval Flow - Android Implementation
 * 
 * Based on reference implementation from T1 app DeviceApprovalActivity
 * Key features:
 * 1. Deep link handling for QR code scanning (p8fs://auth)
 * 2. Manual 8-digit code entry with PIN field UI
 * 3. Device details fetching and display
 * 4. Ed25519 signature-based device approval
 * 5. Comprehensive error handling and user feedback
 */

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText 
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

// MARK: - Data Models

@Serializable
data class DeviceDetailsResponse(
    val message: String,
    val user_code: String,
    val instructions: String,
    val status: String,
    // Enhanced fields from server
    val device_name: String? = null,
    val device_type: String? = null,
    val platform: String? = null,
    val requested_at: String? = null,
    val expires_at: String? = null,
    // Legacy fields
    val device_code: String? = null,
    val client_id: String? = null,
    val scope: String? = null
)

@Serializable
data class DeviceApprovalRequest(
    val device_code: String,
    val user_code: String,
    val signature: String,
    val encrypted_metadata: String = ""
)

/**
 * P8FS Device Approval Manager
 * Handles OAuth device approval flow for desktop/web clients
 */
class P8FSDeviceApprovalManager(
    private val authManager: AuthenticationManager,
    private val headersManager: HeadersManager
) {
    companion object {
        private const val TAG = "P8FSDeviceApproval"
    }
    
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    /**
     * Get device details for OAuth device approval flow
     * Fetches information about the device requesting approval
     */
    suspend fun getDeviceDetails(
        userCode: String,
        verificationUri: String? = null
    ): Result<DeviceDetailsResponse> {
        return try {
            Log.d(TAG, "🔍 Getting device details for user code: $userCode")
            
            // Format user code as XXXX-XXXX if it's not already formatted
            val formattedUserCode = if (userCode.length == 8 && !userCode.contains("-")) {
                "${userCode.substring(0, 4)}-${userCode.substring(4)}"
            } else {
                userCode
            }
            
            // Always use the OAuth device details endpoint
            val fullUrl = "${AppConfig.baseUrl}/oauth/device"
            Log.d(TAG, "📡 GET Device Details - URL: $fullUrl")
            Log.d(TAG, "📝 User Code: $userCode -> Formatted: $formattedUserCode")
            
            val response = httpClient.get(fullUrl) {
                parameter("user_code", formattedUserCode)
                
                // Add required authentication headers
                val authHeaders = headersManager.getAuthHeaders()
                Log.d(TAG, "📋 Headers: ${authHeaders.keys.joinToString(", ")}")
                authHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            
            Log.d(TAG, "📡 Device details response status: ${response.status}")
            
            // Always log the raw response for debugging
            val rawResponseBody = response.bodyAsText()
            Log.d(TAG, "📄 Raw response: $rawResponseBody")
            
            when (response.status) {
                HttpStatusCode.OK -> {
                    try {
                        val json = Json { ignoreUnknownKeys = true; isLenient = true }
                        val deviceResponse = json.decodeFromString<DeviceDetailsResponse>(rawResponseBody)
                        
                        Log.d(TAG, "✅ Device details retrieved: ${deviceResponse.device_name ?: deviceResponse.message}")
                        Log.d(TAG, "📱 Device: ${deviceResponse.device_type} (${deviceResponse.platform})")
                        Log.d(TAG, "📊 Status: ${deviceResponse.status}")
                        
                        Result.success(deviceResponse)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing device details response", e)
                        Result.failure(Exception("Unexpected response format: ${e.message}"))
                    }
                }
                HttpStatusCode.NotFound -> {
                    Log.e(TAG, "❌ Device code not found or expired")
                    Result.failure(Exception("Device code not found or expired"))
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    Log.e(TAG, "❌ Device details unauthorized")
                    Result.failure(Exception("Unauthorized - please authenticate first"))
                }
                else -> {
                    Log.e(TAG, "❌ Device details failed: ${response.status}")
                    Result.failure(Exception("Request failed: ${response.status}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Device details error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Approve device for OAuth device flow
     * Signs the device approval with Ed25519 private key
     */
    suspend fun approveDevice(
        deviceCode: String,
        userCode: String,
        encryptedMetadata: String = "",
        verificationUri: String? = null
    ): Result<Boolean> {
        return try {
            Log.d(TAG, "✅ Approving device with code: $userCode")
            
            // Test basic connectivity first
            try {
                val testResponse = httpClient.get("${AppConfig.baseUrl}/")
                Log.d(TAG, "🔗 Connectivity test: ${testResponse.status}")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Connectivity test failed", e)
            }
            
            // Format user code as XXXX-XXXX if it's not already formatted
            val formattedUserCode = if (userCode.length == 8 && !userCode.contains("-")) {
                "${userCode.substring(0, 4)}-${userCode.substring(4)}"
            } else {
                userCode
            }
            
            // Sign the device code with Ed25519 private key
            val signature = authManager.signChallenge(deviceCode)
            Log.d(TAG, "✍️ Created signature: ${signature.take(20)}... (${signature.length} chars)")
            
            // Prepare form data for OAuth device approval
            val requestBody = buildString {
                append("device_code=$deviceCode")
                append("&user_code=$formattedUserCode")
                append("&signature=$signature")
                append("&encrypted_metadata=$encryptedMetadata")
            }
            
            // Use hardcoded OAuth device approval endpoint
            val fullUrl = "${AppConfig.baseUrl}/oauth/device/approve"
            Log.d(TAG, "📡 POST Device Approval - URL: $fullUrl")
            Log.d(TAG, "📝 Device Code: $deviceCode")
            Log.d(TAG, "📝 User Code: $formattedUserCode")
            
            // Check authentication status
            val accessToken = authManager.getAccessToken()
            Log.d(TAG, "🔐 Access Token Present: ${accessToken != null}")
            
            val response = httpClient.post(fullUrl) {
                // Add required authentication headers
                val authHeaders = headersManager.getAuthHeaders()
                Log.d(TAG, "📋 Request headers:")
                authHeaders.forEach { (key, value) ->
                    val displayValue = if (key == "Authorization") "${value.take(20)}..." else value
                    Log.d(TAG, "    $key: $displayValue")
                    header(key, value)
                }
                
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(requestBody)
                
                Log.d(TAG, "📤 Request body: $requestBody")
            }
            
            Log.d(TAG, "📡 Device approval response status: ${response.status}")
            
            // Always log the response body for debugging
            val responseBody = response.bodyAsText()
            Log.d(TAG, "📄 Response body: $responseBody")
            
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.NoContent -> {
                    Log.d(TAG, "✅ Device approval successful")
                    Result.success(true)
                }
                HttpStatusCode.BadRequest -> {
                    Log.e(TAG, "❌ Device approval bad request: $responseBody")
                    Result.failure(Exception("Invalid request: $responseBody"))
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    Log.e(TAG, "❌ Device approval unauthorized: $responseBody")
                    Result.failure(Exception("Unauthorized - please authenticate first"))
                }
                HttpStatusCode.NotFound -> {
                    Log.e(TAG, "❌ Device code not found: $responseBody")
                    Result.failure(Exception("Device code not found or expired"))
                }
                else -> {
                    Log.e(TAG, "❌ Device approval failed: ${response.status} - $responseBody")
                    Result.failure(Exception("Approval failed: ${response.status}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Device approval error: ${e.message}", e)
            Result.failure(e)
        }
    }
}

/**
 * Device Approval Activity
 * Handles OAuth device approval flow with deep link and manual entry support
 */
class P8FSDeviceApprovalActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "P8FSDeviceApproval"
        const val EXTRA_USER_CODE = "user_code"
    }
    
    private lateinit var approvalManager: P8FSDeviceApprovalManager
    private lateinit var pinFields: List<EditText>
    private var verificationUri: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize managers
        val authManager = AuthenticationManager(this)
        val headersManager = HeadersManager(this)
        approvalManager = P8FSDeviceApprovalManager(authManager, headersManager)
        
        setupUI()
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }
    
    private fun setupUI() {
        // Set up toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Approve Device"
        
        // Initialize PIN fields (8 digits for OAuth user codes)
        pinFields = listOf(
            findViewById(R.id.pin1), findViewById(R.id.pin2), findViewById(R.id.pin3), findViewById(R.id.pin4),
            findViewById(R.id.pin5), findViewById(R.id.pin6), findViewById(R.id.pin7), findViewById(R.id.pin8)
        )
        
        setupPinFields()
        
        // Handle approve button click
        findViewById<Button>(R.id.approveButton).setOnClickListener {
            val userCode = getPinCode()
            if (userCode.length == 8) {
                performDeviceApproval(userCode)
            } else {
                Toast.makeText(this, "Please enter a valid 8-digit code", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Check authentication status
        if (!authManager.isDeviceRegistered()) {
            showAuthenticationRequired()
        }
    }
    
    private fun setupPinFields() {
        pinFields.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1) {
                        // Move to next field
                        if (index < pinFields.size - 1) {
                            pinFields[index + 1].requestFocus()
                        }
                    }
                    updateApproveButtonState()
                }
                
                override fun afterTextChanged(s: Editable?) {}
            })
            
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        // Move to previous field and clear it
                        pinFields[index - 1].requestFocus()
                        pinFields[index - 1].setText("")
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
        
        // Focus on first field
        pinFields[0].requestFocus()
    }
    
    private fun getPinCode(): String {
        return pinFields.joinToString("") { it.text.toString() }
    }
    
    private fun setPinCode(code: String) {
        val cleanCode = code.replace("-", "").uppercase()
        cleanCode.forEachIndexed { index, char ->
            if (index < pinFields.size) {
                pinFields[index].setText(char.toString())
            }
        }
        updateApproveButtonState()
    }
    
    private fun updateApproveButtonState() {
        val code = getPinCode()
        val isValidCode = code.length == 8 && code.all { it.isLetterOrDigit() }
        
        findViewById<Button>(R.id.approveButton).isEnabled = isValidCode
        
        // Auto-fetch device info when we have a complete valid code
        if (isValidCode) {
            fetchDeviceInfoInBackground(code)
        } else {
            // Hide device info card when code is incomplete
            findViewById<View>(R.id.deviceDetailsCard).visibility = View.GONE
        }
    }
    
    /**
     * Handle deep link intents from QR code scanning
     * Supports formats:
     * - p8fs://auth?verification_uri={url}&user_code={code} (new format)
     * - p8fs://auth/device?code=XXXX-XXXX (legacy format)
     */
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri?.scheme == "p8fs" && uri.host == "auth") {
                Log.d(TAG, "📱 Received device approval deep link: $uri")
                
                var userCode: String? = null
                var verificationUri: String? = null
                
                // Check for new format: p8fs://auth?verification_uri={encoded_url}&user_code={code}
                if (uri.path.isNullOrEmpty()) {
                    userCode = uri.getQueryParameter("user_code")
                    verificationUri = uri.getQueryParameter("verification_uri")
                    
                    if (verificationUri != null) {
                        // URL decode the verification_uri
                        verificationUri = java.net.URLDecoder.decode(verificationUri, "UTF-8")
                        this.verificationUri = verificationUri
                        Log.d(TAG, "🔗 New format - verification_uri: $verificationUri")
                    }
                }
                // Check for legacy format: p8fs://auth/device?code=XXXX-XXXX
                else if (uri.path == "/device") {
                    userCode = uri.getQueryParameter("code")
                    Log.d(TAG, "🔗 Legacy format - code: $userCode")
                }
                
                if (!userCode.isNullOrBlank()) {
                    val cleanCode = userCode.replace("-", "").uppercase()
                    if (cleanCode.length == 8) {
                        setPinCode(cleanCode)
                        Log.d(TAG, "✅ User code set from deep link: $cleanCode")
                    } else {
                        Toast.makeText(this, "Invalid verification code format", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "No verification code found in deep link", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Handle manual entry from Settings
            val userCode = intent.getStringExtra(EXTRA_USER_CODE)
            if (!userCode.isNullOrBlank()) {
                setPinCode(userCode)
            }
        }
    }
    
    /**
     * Fetch device information in background for display
     */
    private fun fetchDeviceInfoInBackground(userCode: String) {
        if (!authManager.isDeviceRegistered()) return
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "📋 Fetching device info for code: $userCode")
                
                val deviceDetailsResult = approvalManager.getDeviceDetails(userCode, verificationUri)
                if (deviceDetailsResult.isSuccess) {
                    val deviceDetails = deviceDetailsResult.getOrNull()!!
                    Log.d(TAG, "📱 Device info retrieved: ${deviceDetails.device_name ?: "Unknown Device"}")
                    
                    // Only show device info if we have meaningful data
                    if (deviceDetails.device_name != null || 
                        deviceDetails.device_type != null || 
                        deviceDetails.platform != null) {
                        populateDeviceDetailsCard(deviceDetails)
                    }
                } else {
                    Log.d(TAG, "ℹ️ No device info available for code: $userCode")
                    // Silently fail - user can still approve without device info
                }
            } catch (e: Exception) {
                Log.d(TAG, "ℹ️ Could not fetch device info: ${e.message}")
                // Silently fail - user can still approve
            }
        }
    }
    
    /**
     * Perform device approval with the entered code
     */
    private fun performDeviceApproval(userCode: String) {
        if (!authManager.isDeviceRegistered()) {
            showAuthenticationRequired()
            return
        }
        
        findViewById<View>(R.id.progressBar).visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "✅ Performing device approval for code: $userCode")
                
                // Use the user code as device code (server will map it)
                val approvalResult = approvalManager.approveDevice(
                    deviceCode = userCode,
                    userCode = userCode,
                    encryptedMetadata = "",
                    verificationUri = verificationUri
                )
                
                if (approvalResult.isSuccess) {
                    Log.d(TAG, "✅ Device approval successful")
                    
                    Toast.makeText(this@P8FSDeviceApprovalActivity, 
                        "Device approved successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val error = approvalResult.exceptionOrNull()?.message ?: "Approval failed"
                    showError("Approval Failed", error)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during device approval", e)
                showError("Error", e.message ?: "Unknown error occurred")
            } finally {
                findViewById<View>(R.id.progressBar).visibility = View.GONE
            }
        }
    }
    
    /**
     * Populate device details card with fetched information
     */
    private fun populateDeviceDetailsCard(deviceDetails: DeviceDetailsResponse) {
        // Show the device details card
        val deviceDetailsCard = findViewById<View>(R.id.deviceDetailsCard)
        deviceDetailsCard.visibility = View.VISIBLE
        
        // Populate device information with graceful fallbacks
        val deviceName = deviceDetails.device_name ?: "Unknown Device"
        val deviceType = deviceDetails.device_type?.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
        } ?: "Unknown"
        val platform = deviceDetails.platform ?: "Unknown Platform"
        
        findViewById<TextView>(R.id.deviceNameText).text = deviceName
        findViewById<TextView>(R.id.deviceTypeText).text = "$deviceType • $platform"
        findViewById<TextView>(R.id.deviceMessage).text = deviceDetails.message
        findViewById<TextView>(R.id.deviceInstructions).text = deviceDetails.instructions
        
        // Format and display status
        val status = deviceDetails.status.replace("_", " ").split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        findViewById<TextView>(R.id.deviceStatus).text = status
        
        // Display user code
        findViewById<TextView>(R.id.deviceUserCode).text = "Code: ${deviceDetails.user_code}"
        
        // Format and display requested time
        val requestedAt = deviceDetails.requested_at
        if (requestedAt != null) {
            try {
                // Parse ISO 8601 format (2025-08-17T07:30:56Z)
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                inputFormat.timeZone = TimeZone.getTimeZone("UTC")
                val outputFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
                
                val date = inputFormat.parse(requestedAt)
                val formattedDate = if (date != null) outputFormat.format(date) else requestedAt
                findViewById<TextView>(R.id.deviceRequestedAt).text = "Requested at: $formattedDate"
            } catch (e: Exception) {
                // Fallback to raw timestamp if parsing fails
                findViewById<TextView>(R.id.deviceRequestedAt).text = "Requested at: $requestedAt"
            }
        } else {
            findViewById<TextView>(R.id.deviceRequestedAt).text = "Request time: Unknown"
        }
        
        Log.d(TAG, "📱 Device details card populated for: $deviceName ($deviceType)")
    }
    
    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
        
        findViewById<Button>(R.id.approveButton).isEnabled = true
        findViewById<View>(R.id.progressBar).visibility = View.GONE
    }
    
    private fun showAuthenticationRequired() {
        AlertDialog.Builder(this)
            .setTitle("Authentication Required")
            .setMessage("You must be logged in to approve devices. Please authenticate first.")
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// Usage Example:
/*
class MainActivity : AppCompatActivity() {
    
    fun openDeviceApproval(userCode: String? = null) {
        val intent = Intent(this, P8FSDeviceApprovalActivity::class.java)
        if (userCode != null) {
            intent.putExtra(P8FSDeviceApprovalActivity.EXTRA_USER_CODE, userCode)
        }
        startActivity(intent)
    }
    
    // Handle deep links in manifest:
    // <activity android:name=".ui.approval.P8FSDeviceApprovalActivity"
    //          android:exported="true">
    //     <intent-filter>
    //         <action android:name="android.intent.action.VIEW" />
    //         <category android:name="android.intent.category.DEFAULT" />
    //         <category android:name="android.intent.category.BROWSABLE" />
    //         <data android:scheme="p8fs" android:host="auth" />
    //     </intent-filter>
    // </activity>
}
*/