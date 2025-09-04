/**
 * P8FS S3 Uploads Flow - Android Implementation
 * 
 * Based on reference implementation from T1 app
 * Key features:
 * 1. AWS Signature Version 4 implementation for S3 uploads
 * 2. Tenant-isolated bucket structure with CRITICAL path requirements
 * 3. Hardcoded SeaweedFS credentials for write-only access (P8FS namespace)
 * 4. JWT token parsing for user context (tenant ID, email)
 * 5. Comprehensive header support from content_headers.md
 * 
 * CRITICAL: Tenant Isolation Upload Paths
 * =====================================
 * 
 * S3 Bucket Structure: tenant-hash IS the S3 bucket name
 * Upload Path Format: uploads/yyyy/mm/dd/file.ext
 * 
 * Example:
 * - S3 Bucket:  tenant-abc123def456
 * - File Path:  uploads/2024/01/15/document_1705123456789.pdf
 * - Full S3 URL: https://s3.percolationlabs.ai/tenant-abc123def456/uploads/2024/01/15/document_1705123456789.pdf
 * 
 * ALL files use the same upload path format: uploads/yyyy/mm/dd/filename_timestamp.ext
 * 
 * The JWT token contains the tenant-hash which becomes the S3 bucket name for complete isolation.
 * Each tenant gets their own dedicated S3 bucket in the SeaweedFS cluster (P8FS namespace).
 */

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JWT payload structure for extracting user context
 */
@Serializable
data class JwtPayload(
    val sub: String? = null,
    val user_id: String? = null,
    val email: String? = null,
    val tenant_id: String? = null,
    val exp: Long? = null,
    val iat: Long? = null
)

/**
 * S3 upload result
 */
data class S3UploadResult(
    val s3Key: String,
    val url: String,
    val size: Long,
    val bucket: String
)

data class S3UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
    val percentage: Int
)

data class S3FileMetadata(
    val name: String,
    val size: Long,
    val mimeType: String
)

/**
 * P8FS S3 Upload Client
 * Uses hardcoded SeaweedFS write-only credentials and AWS V4 signing
 */
class P8FSS3UploadClient(
    private val context: Context,
    private val authManager: AuthenticationManager,
    private val headersManager: HeadersManager
) {
    companion object {
        private const val TAG = "P8FSS3UploadClient"
        private const val UPLOAD_TIMEOUT_SECONDS = 300L // 5 minutes
        
        // AWS V4 Signature constants
        private const val AWS_SERVICE = "s3"
        private const val AWS_REQUEST_TYPE = "aws4_request"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        
        // SeaweedFS Write-Only Credentials (hardcoded for security)
        private const val S3_ACCESS_KEY = "P8FS0AC3180375CA16A2"
        private const val S3_SECRET_KEY = "Rx5oOULvHXBMQcCzT3ptsfEC1Kfr5juj"
        private const val S3_ENDPOINT = "https://s3.percolationlabs.ai"
        private const val S3_REGION = "us-east-1"
    }
    
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = UPLOAD_TIMEOUT_SECONDS * 1000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Upload file to P8FS S3 storage with tenant isolation
     */
    suspend fun uploadFile(
        fileUri: Uri,
        fileName: String,
        mimeType: String,
        customHeaders: Map<String, String> = emptyMap(),
        onProgress: ((S3UploadProgress) -> Unit)? = null
    ): S3UploadResult = withContext(Dispatchers.IO) {
        
        try {
            Log.d(TAG, "🚀 Starting S3 upload for file: $fileName")
            
            // Step 1: Get tenant ID from stored JWT token
            val tenantId = getTenantIdFromToken()
                ?: throw IllegalStateException("No tenant ID found - user not authenticated")
            
            val userEmail = getUserEmailFromToken() ?: ""
            Log.d(TAG, "📧 User email: $userEmail")
            Log.d(TAG, "🏢 Tenant ID: $tenantId")
            
            // Step 2: Generate tenant-isolated S3 path
            // Format: uploads/YYYY/MM/DD/filename_timestamp.ext
            val s3Key = generateS3Key(fileName)
            Log.d(TAG, "🗂️ S3 Key: $s3Key")
            
            // Step 3: Prepare file for upload
            val tempFile = createTempFileFromUri(fileUri, fileName)
            val fileSize = tempFile.length()
            val fileBytes = tempFile.readBytes()
            
            Log.d(TAG, "📁 File size: $fileSize bytes")
            
            try {
                // Step 4: Build S3 URL (tenant ID becomes bucket name)
                val s3Url = "$S3_ENDPOINT/$tenantId/$s3Key"
                Log.d(TAG, "🔗 S3 URL: $s3Url")
                
                // Step 5: Create AWS Signature Version 4
                val (authorizationHeader, dateHeader, contentHash) = createAwsV4Signature(
                    method = "PUT",
                    uri = "/$tenantId/$s3Key",
                    contentType = mimeType,
                    contentLength = fileSize,
                    userEmail = userEmail,
                    fileBytes = fileBytes
                )
                
                Log.d(TAG, "🔐 AWS V4 Authorization created")
                Log.d(TAG, "🔐 Content SHA256: $contentHash")
                
                // Step 6: Execute upload with signed headers
                onProgress?.invoke(S3UploadProgress(0, fileSize, 0))
                
                val response = httpClient.put(s3Url) {
                    // Required content headers
                    contentType(ContentType.parse(mimeType))
                    
                    // AWS V4 signature headers (required for authentication)
                    header("Authorization", authorizationHeader)
                    header("x-amz-date", dateHeader)
                    header("x-amz-content-sha256", contentHash)
                    
                    // User context header (for audit/tracking)
                    header("X-User-Email", userEmail)
                    
                    // Add P8FS content headers from content_headers.md
                    val p8fsHeaders = headersManager.getS3UploadHeaders(
                        fileName = fileName,
                        mimeType = mimeType,
                        fileSize = fileSize
                    )
                    p8fsHeaders.forEach { (key, value) ->
                        header(key, value)
                    }
                    
                    // Add custom headers if provided
                    customHeaders.forEach { (key, value) ->
                        header(key, value)
                    }
                    
                    // Upload file content
                    setBody(ByteReadChannel(fileBytes))
                }
                
                // Step 7: Handle response
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "❌ S3 upload failed: ${response.status} - $errorBody")
                    throw IOException("S3 upload failed: ${response.status}")
                }
                
                // Complete progress
                onProgress?.invoke(S3UploadProgress(fileSize, fileSize, 100))
                
                Log.d(TAG, "✅ S3 upload successful")
                Log.d(TAG, "📊 Status: ${response.status}")
                Log.d(TAG, "🏷️ ETag: ${response.headers["ETag"]}")
                
                // Return upload result
                S3UploadResult(
                    s3Key = s3Key,
                    url = s3Url,
                    size = fileSize,
                    bucket = tenantId
                )
                
            } finally {
                // Clean up temp file
                tempFile.delete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ S3 upload error", e)
            throw e
        }
    }
    
    /**
     * Generate S3 key with date-based structure
     * Format: uploads/YYYY/MM/DD/filename_timestamp.ext
     */
    private fun generateS3Key(fileName: String): String {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val datePath = dateFormat.format(Date())
        val timestamp = System.currentTimeMillis()
        
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".")
        val uniqueFileName = "${nameWithoutExt}_${timestamp}.${extension}"
        
        return "uploads/$datePath/$uniqueFileName"
    }
    
    /**
     * Create temporary file from URI
     */
    private suspend fun createTempFileFromUri(uri: Uri, fileName: String): File = 
        withContext(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "s3_uploads").apply { mkdirs() }
            val tempFile = File(tempDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to read file from URI")
            
            tempFile
        }
    
    /**
     * Create AWS Signature Version 4 for S3 PUT request
     * Based on AWS documentation and SeaweedFS compatibility
     */
    private fun createAwsV4Signature(
        method: String,
        uri: String,
        contentType: String,
        contentLength: Long,
        userEmail: String,
        fileBytes: ByteArray
    ): Triple<String, String, String> {
        
        // Step 1: Create timestamp and date
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateTimeFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        
        val dateStamp = dateFormat.format(now)
        val amzDate = dateTimeFormat.format(now)
        
        // Step 2: Create canonical request
        val payloadHash = sha256Hex(fileBytes)
        
        // Headers must be in alphabetical order for AWS V4 signing
        val canonicalHeaders = buildString {
            append("content-type:$contentType\n")
            append("host:s3.percolationlabs.ai\n")
            append("x-amz-content-sha256:$payloadHash\n")
            append("x-amz-date:$amzDate\n")
            append("x-user-email:$userEmail\n")
        }
        
        val signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date;x-user-email"
        
        val canonicalRequest = buildString {
            append("$method\n")
            append("$uri\n")
            append("\n") // No query parameters
            append(canonicalHeaders)
            append("\n")
            append(signedHeaders)
            append("\n")
            append(payloadHash)
        }
        
        // Step 3: Create string to sign
        val credentialScope = "$dateStamp/$S3_REGION/$AWS_SERVICE/$AWS_REQUEST_TYPE"
        val stringToSign = buildString {
            append("$ALGORITHM\n")
            append("$amzDate\n")
            append("$credentialScope\n")
            append(sha256Hex(canonicalRequest.toByteArray()))
        }
        
        // Step 4: Calculate signature using HMAC-SHA256 chain
        val kDate = hmacSha256(("AWS4" + S3_SECRET_KEY).toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, S3_REGION)
        val kService = hmacSha256(kRegion, AWS_SERVICE)
        val kSigning = hmacSha256(kService, AWS_REQUEST_TYPE)
        val signature = hmacSha256Hex(kSigning, stringToSign)
        
        // Step 5: Create authorization header
        val authorizationHeader = "$ALGORITHM Credential=$S3_ACCESS_KEY/$credentialScope," +
                "SignedHeaders=$signedHeaders,Signature=$signature"
        
        Log.d(TAG, "🔐 AWS V4 Signature Details:")
        Log.d(TAG, "📅 AMZ Date: $amzDate")
        Log.d(TAG, "🔒 Payload Hash: $payloadHash")
        Log.d(TAG, "✍️ Signature: $signature")
        
        return Triple(authorizationHeader, amzDate, payloadHash)
    }
    
    /**
     * Get tenant ID from stored JWT token
     */
    private fun getTenantIdFromToken(): String? {
        val accessToken = authManager.getAccessToken() ?: return null
        return JwtUtils.getTenantId(accessToken)
    }
    
    /**
     * Get user email from stored JWT token
     */
    private fun getUserEmailFromToken(): String? {
        val accessToken = authManager.getAccessToken() ?: return null
        return JwtUtils.getEmail(accessToken)
    }
    
    /**
     * Get file metadata from URI
     */
    fun getFileMetadata(uri: Uri): S3FileMetadata? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    
                    S3FileMetadata(
                        name = cursor.getString(nameIndex) ?: "unknown",
                        size = cursor.getLong(sizeIndex),
                        mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file metadata", e)
            null
        }
    }
    
    // Cryptographic utility functions
    
    /**
     * Calculate SHA256 hash and return as hex string
     */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Calculate HMAC-SHA256
     */
    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray())
    }
    
    /**
     * Calculate HMAC-SHA256 and return as hex string
     */
    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }
    
    fun close() {
        httpClient.close()
    }
}

/**
 * JWT utilities for token parsing
 */
object JwtUtils {
    private const val TAG = "JwtUtils"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Extract payload from JWT token
     */
    fun extractPayload(token: String): JwtPayload? {
        return try {
            // JWT format: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) {
                Log.e(TAG, "Invalid JWT format")
                return null
            }
            
            // Decode payload (add padding if needed for Base64)
            var payload = parts[1]
            val padding = 4 - (payload.length % 4)
            if (padding < 4) {
                payload += "=".repeat(padding)
            }
            
            val decodedBytes = java.util.Base64.getUrlDecoder().decode(payload)
            val payloadJson = String(decodedBytes, Charsets.UTF_8)
            
            Log.d(TAG, "JWT payload decoded: $payloadJson")
            json.decodeFromString<JwtPayload>(payloadJson)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JWT", e)
            null
        }
    }
    
    /**
     * Get tenant ID from JWT token
     */
    fun getTenantId(token: String): String? {
        return extractPayload(token)?.tenant_id
    }
    
    /**
     * Get email from JWT token
     */
    fun getEmail(token: String): String? {
        return extractPayload(token)?.email
    }
    
    /**
     * Check if token is expired
     */
    fun isExpired(token: String): Boolean {
        val payload = extractPayload(token) ?: return true
        val exp = payload.exp ?: return false
        
        val expirationTime = exp * 1000 // Convert to milliseconds
        val currentTime = System.currentTimeMillis()
        
        return currentTime >= expirationTime
    }
}

/**
 * Headers manager for S3 upload headers
 */
class HeadersManager {
    
    /**
     * Get comprehensive headers for S3 uploads
     * Based on content_headers.md specifications
     */
    fun getS3UploadHeaders(
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        // Device Context
        headers["X-Device-ID"] = getDeviceId()
        headers["X-Device-Type"] = "mobile"
        headers["X-Device-Platform"] = "Android"
        headers["X-Device-Model"] = android.os.Build.MODEL
        headers["X-App-Version"] = getAppVersion()
        headers["X-Platform"] = "Android"
        
        // Content Classification
        headers["X-Content-Type"] = mimeType
        headers["X-Content-Category"] = determineContentCategory(mimeType)
        headers["X-Content-Language"] = getDeviceLanguage()
        
        // Processing Context
        headers["X-Processing-Context"] = "s3-upload"
        headers["X-Processing-Priority"] = "medium"
        
        Log.d("HeadersManager", "📋 Generated ${headers.size} S3 upload headers")
        return headers
    }
    
    private fun determineContentCategory(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "media"
            mimeType.startsWith("video/") -> "media"
            mimeType.startsWith("audio/") -> "media"
            mimeType.startsWith("text/") -> "documents"
            mimeType.contains("pdf") -> "documents"
            mimeType.contains("document") -> "documents"
            mimeType.contains("spreadsheet") -> "documents"
            mimeType.contains("presentation") -> "documents"
            else -> "data"
        }
    }
    
    private fun getDeviceId(): String = TODO("Get unique device identifier")
    private fun getAppVersion(): String = TODO("Get from BuildConfig")
    private fun getDeviceLanguage(): String = TODO("Get device locale")
}

// Usage Example:
/*
class FileUploadManager(
    private val context: Context
) {
    private val authManager = AuthenticationManager(context)
    private val headersManager = HeadersManager()
    private val s3Client = P8FSS3UploadClient(context, authManager, headersManager)
    
    suspend fun uploadFile(fileUri: Uri): S3UploadResult {
        // Get file metadata
        val metadata = s3Client.getFileMetadata(fileUri)
            ?: throw IllegalArgumentException("Cannot read file metadata")
        
        Log.d("FileUpload", "Uploading: ${metadata.name} (${metadata.size} bytes)")
        
        // Upload with progress tracking
        return s3Client.uploadFile(
            fileUri = fileUri,
            fileName = metadata.name,
            mimeType = metadata.mimeType,
            customHeaders = mapOf(
                "X-Content-Tags" -> "mobile-upload,user-content",
                "X-Content-Sensitivity" -> "internal"
            )
        ) { progress ->
            Log.d("FileUpload", "Progress: ${progress.percentage}%")
        }
    }
}
*/