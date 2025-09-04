/**
 * P8FS S3 Uploads Flow - iOS Implementation
 *
 * Based on reference Android implementation from T1 app
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

import Foundation
import Combine
import CryptoKit
import os.log

// MARK: - Data Models

struct JwtPayload: Codable {
    let sub: String?
    let userId: String?
    let email: String?
    let tenantId: String?
    let exp: TimeInterval?
    let iat: TimeInterval?
    
    enum CodingKeys: String, CodingKey {
        case sub, email, exp, iat
        case userId = "user_id"
        case tenantId = "tenant_id"
    }
}

struct S3UploadResult {
    let s3Key: String
    let url: String
    let size: Int64
    let bucket: String
}

struct S3UploadProgress {
    let bytesUploaded: Int64
    let totalBytes: Int64
    let percentage: Int
}

struct S3FileMetadata {
    let name: String
    let size: Int64
    let mimeType: String
}

// MARK: - Main S3 Upload Client

@MainActor
class P8FSS3UploadClient: ObservableObject {
    
    private let logger = Logger(subsystem: "app.p8fs", category: "S3Upload")
    private let authManager: AuthenticationManager
    private let headersManager: HeadersManager
    
    // AWS V4 Signature constants
    private struct AWSConstants {
        static let service = "s3"
        static let requestType = "aws4_request"
        static let algorithm = "AWS4-HMAC-SHA256"
        
        // SeaweedFS Write-Only Credentials (hardcoded for security)
        static let accessKey = "P8FS0AC3180375CA16A2"
        static let secretKey = "Rx5oOULvHXBMQcCzT3ptsfEC1Kfr5juj"
        static let endpoint = "https://s3.percolationlabs.ai"
        static let region = "us-east-1"
    }
    
    private var urlSession: URLSession
    
    init(authManager: AuthenticationManager, headersManager: HeadersManager) {
        self.authManager = authManager
        self.headersManager = headersManager
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 300.0 // 5 minutes for large files
        config.timeoutIntervalForResource = 300.0
        
        self.urlSession = URLSession(configuration: config)
        
        logger.info("🚀 P8FS S3 Upload Client initialized")
    }
    
    /**
     * Upload file to P8FS S3 storage with tenant isolation
     */
    func uploadFile(
        fileURL: URL,
        fileName: String,
        mimeType: String,
        customHeaders: [String: String] = [:],
        onProgress: ((S3UploadProgress) -> Void)? = nil
    ) async throws -> S3UploadResult {
        
        logger.info("🚀 Starting S3 upload for file: \(fileName)")
        
        // Step 1: Get tenant ID and user email from stored JWT token
        guard let tenantId = getTenantIdFromToken() else {
            throw S3UploadError.noTenantId
        }
        
        let userEmail = getUserEmailFromToken() ?? ""
        logger.info("📧 User email: \(userEmail)")
        logger.info("🏢 Tenant ID: \(tenantId)")
        
        // Step 2: Generate tenant-isolated S3 path
        let s3Key = generateS3Key(fileName: fileName)
        logger.info("🗂️ S3 Key: \(s3Key)")
        
        // Step 3: Prepare file for upload
        let fileData = try Data(contentsOf: fileURL)
        let fileSize = Int64(fileData.count)
        
        logger.info("📁 File size: \(fileSize) bytes")
        
        // Step 4: Build S3 URL (tenant ID becomes bucket name)
        let s3URLString = "\(AWSConstants.endpoint)/\(tenantId)/\(s3Key)"
        guard let s3URL = URL(string: s3URLString) else {
            throw S3UploadError.invalidURL
        }
        
        logger.info("🔗 S3 URL: \(s3URLString)")
        
        // Step 5: Create AWS Signature Version 4
        let (authorizationHeader, dateHeader, contentHash) = try createAwsV4Signature(
            method: "PUT",
            uri: "/\(tenantId)/\(s3Key)",
            contentType: mimeType,
            userEmail: userEmail,
            fileData: fileData
        )
        
        logger.info("🔐 AWS V4 Authorization created")
        logger.info("🔐 Content SHA256: \(contentHash)")
        
        // Step 6: Create URL request with signed headers
        var request = URLRequest(url: s3URL)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        
        // AWS V4 signature headers (required for authentication)
        request.setValue(authorizationHeader, forHTTPHeaderField: "Authorization")
        request.setValue(dateHeader, forHTTPHeaderField: "x-amz-date")
        request.setValue(contentHash, forHTTPHeaderField: "x-amz-content-sha256")
        
        // User context header (for audit/tracking)
        request.setValue(userEmail, forHTTPHeaderField: "X-User-Email")
        
        // Add P8FS content headers from content_headers.md
        let p8fsHeaders = headersManager.getS3UploadHeaders(
            fileName: fileName,
            mimeType: mimeType,
            fileSize: fileSize
        )
        for (key, value) in p8fsHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        // Add custom headers if provided
        for (key, value) in customHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        // Set request body
        request.httpBody = fileData
        
        // Step 7: Execute upload
        onProgress?(S3UploadProgress(bytesUploaded: 0, totalBytes: fileSize, percentage: 0))
        
        let (data, response) = try await urlSession.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw S3UploadError.invalidResponse
        }
        
        guard httpResponse.statusCode == 200 else {
            let errorMessage = String(data: data, encoding: .utf8) ?? "Unknown error"
            logger.error("❌ S3 upload failed: \(httpResponse.statusCode) - \(errorMessage)")
            throw S3UploadError.httpError(httpResponse.statusCode)
        }
        
        // Complete progress
        onProgress?(S3UploadProgress(bytesUploaded: fileSize, totalBytes: fileSize, percentage: 100))
        
        logger.info("✅ S3 upload successful")
        logger.info("📊 Status: \(httpResponse.statusCode)")
        
        if let etag = httpResponse.value(forHTTPHeaderField: "ETag") {
            logger.info("🏷️ ETag: \(etag)")
        }
        
        // Return upload result
        return S3UploadResult(
            s3Key: s3Key,
            url: s3URLString,
            size: fileSize,
            bucket: tenantId
        )
    }
    
    /**
     * Generate S3 key with date-based structure
     * Format: uploads/YYYY/MM/DD/filename_timestamp.ext
     */
    private func generateS3Key(fileName: String) -> String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy/MM/dd"
        dateFormatter.timeZone = TimeZone(identifier: "UTC")
        
        let datePath = dateFormatter.string(from: Date())
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000) // milliseconds
        
        let nameWithoutExt = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        let uniqueFileName = "\(nameWithoutExt)_\(timestamp).\(ext)"
        
        return "uploads/\(datePath)/\(uniqueFileName)"
    }
    
    /**
     * Create AWS Signature Version 4 for S3 PUT request
     * Based on AWS documentation and SeaweedFS compatibility
     */
    private func createAwsV4Signature(
        method: String,
        uri: String,
        contentType: String,
        userEmail: String,
        fileData: Data
    ) throws -> (String, String, String) {
        
        // Step 1: Create timestamp and date
        let now = Date()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyyMMdd"
        dateFormatter.timeZone = TimeZone(identifier: "UTC")
        
        let dateTimeFormatter = DateFormatter()
        dateTimeFormatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        dateTimeFormatter.timeZone = TimeZone(identifier: "UTC")
        
        let dateStamp = dateFormatter.string(from: now)
        let amzDate = dateTimeFormatter.string(from: now)
        
        // Step 2: Create canonical request
        let payloadHash = sha256Hex(data: fileData)
        
        // Headers must be in alphabetical order for AWS V4 signing
        let canonicalHeaders = [
            "content-type:\(contentType)",
            "host:s3.percolationlabs.ai",
            "x-amz-content-sha256:\(payloadHash)",
            "x-amz-date:\(amzDate)",
            "x-user-email:\(userEmail)"
        ].joined(separator: "\n") + "\n"
        
        let signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date;x-user-email"
        
        let canonicalRequest = [
            method,
            uri,
            "", // No query parameters
            canonicalHeaders,
            "",
            signedHeaders,
            payloadHash
        ].joined(separator: "\n")
        
        // Step 3: Create string to sign
        let credentialScope = "\(dateStamp)/\(AWSConstants.region)/\(AWSConstants.service)/\(AWSConstants.requestType)"
        let stringToSign = [
            AWSConstants.algorithm,
            amzDate,
            credentialScope,
            sha256Hex(data: canonicalRequest.data(using: .utf8)!)
        ].joined(separator: "\n")
        
        // Step 4: Calculate signature using HMAC-SHA256 chain
        let kDate = hmacSha256(key: ("AWS4" + AWSConstants.secretKey).data(using: .utf8)!, data: dateStamp.data(using: .utf8)!)
        let kRegion = hmacSha256(key: kDate, data: AWSConstants.region.data(using: .utf8)!)
        let kService = hmacSha256(key: kRegion, data: AWSConstants.service.data(using: .utf8)!)
        let kSigning = hmacSha256(key: kService, data: AWSConstants.requestType.data(using: .utf8)!)
        let signature = hmacSha256Hex(key: kSigning, data: stringToSign.data(using: .utf8)!)
        
        // Step 5: Create authorization header
        let authorizationHeader = "\(AWSConstants.algorithm) Credential=\(AWSConstants.accessKey)/\(credentialScope)," +
                "SignedHeaders=\(signedHeaders),Signature=\(signature)"
        
        logger.info("🔐 AWS V4 Signature Details:")
        logger.info("📅 AMZ Date: \(amzDate)")
        logger.info("🔒 Payload Hash: \(payloadHash)")
        logger.info("✍️ Signature: \(signature)")
        
        return (authorizationHeader, amzDate, payloadHash)
    }
    
    /**
     * Get tenant ID from stored JWT token
     */
    private func getTenantIdFromToken() -> String? {
        guard let accessToken = authManager.getAccessToken() else { return nil }
        return JwtUtils.getTenantId(from: accessToken)
    }
    
    /**
     * Get user email from stored JWT token
     */
    private func getUserEmailFromToken() -> String? {
        guard let accessToken = authManager.getAccessToken() else { return nil }
        return JwtUtils.getEmail(from: accessToken)
    }
    
    /**
     * Get file metadata from file URL
     */
    func getFileMetadata(from fileURL: URL) -> S3FileMetadata? {
        do {
            let resourceValues = try fileURL.resourceValues(forKeys: [
                .fileSizeKey,
                .nameKey,
                .contentTypeKey
            ])
            
            let size = Int64(resourceValues.fileSize ?? 0)
            let name = resourceValues.name ?? fileURL.lastPathComponent
            let mimeType = resourceValues.contentType?.identifier ?? "application/octet-stream"
            
            return S3FileMetadata(name: name, size: size, mimeType: mimeType)
            
        } catch {
            logger.error("Failed to get file metadata: \(error.localizedDescription)")
            return nil
        }
    }
    
    // MARK: - Cryptographic Utilities
    
    /**
     * Calculate SHA256 hash and return as hex string
     */
    private func sha256Hex(data: Data) -> String {
        let hash = SHA256.hash(data: data)
        return hash.map { String(format: "%02x", $0) }.joined()
    }
    
    /**
     * Calculate HMAC-SHA256
     */
    private func hmacSha256(key: Data, data: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
        return Data(mac)
    }
    
    /**
     * Calculate HMAC-SHA256 and return as hex string
     */
    private func hmacSha256Hex(key: Data, data: Data) -> String {
        let mac = hmacSha256(key: key, data: data)
        return mac.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - JWT Utilities

class JwtUtils {
    
    private static let logger = Logger(subsystem: "app.p8fs", category: "JwtUtils")
    
    /**
     * Extract payload from JWT token
     */
    static func extractPayload(from token: String) -> JwtPayload? {
        let parts = token.components(separatedBy: ".")
        guard parts.count == 3 else {
            logger.error("Invalid JWT format")
            return nil
        }
        
        // Decode payload (add padding if needed for Base64)
        var payload = parts[1]
        let padding = 4 - (payload.count % 4)
        if padding < 4 {
            payload += String(repeating: "=", count: padding)
        }
        
        guard let decodedData = Data(base64Encoded: payload, options: .ignoreUnknownCharacters),
              let payloadJson = String(data: decodedData, encoding: .utf8) else {
            logger.error("Failed to decode JWT payload")
            return nil
        }
        
        logger.info("JWT payload decoded: \(payloadJson)")
        
        do {
            let decoder = JSONDecoder()
            return try decoder.decode(JwtPayload.self, from: decodedData)
        } catch {
            logger.error("Error parsing JWT: \(error.localizedDescription)")
            return nil
        }
    }
    
    /**
     * Get tenant ID from JWT token
     */
    static func getTenantId(from token: String) -> String? {
        return extractPayload(from: token)?.tenantId
    }
    
    /**
     * Get email from JWT token
     */
    static func getEmail(from token: String) -> String? {
        return extractPayload(from: token)?.email
    }
    
    /**
     * Check if token is expired
     */
    static func isExpired(_ token: String) -> Bool {
        guard let payload = extractPayload(from: token),
              let exp = payload.exp else { return true }
        
        let expirationTime = exp
        let currentTime = Date().timeIntervalSince1970
        
        return currentTime >= expirationTime
    }
}

// MARK: - Headers Manager

class HeadersManager {
    
    private let logger = Logger(subsystem: "app.p8fs", category: "HeadersManager")
    
    /**
     * Get comprehensive headers for S3 uploads
     * Based on content_headers.md specifications
     */
    func getS3UploadHeaders(
        fileName: String,
        mimeType: String,
        fileSize: Int64
    ) -> [String: String] {
        var headers: [String: String] = [:]
        
        // Device Context
        headers["X-Device-ID"] = getDeviceId()
        headers["X-Device-Type"] = "mobile"
        headers["X-Device-Platform"] = "iOS"
        headers["X-Device-Model"] = UIDevice.current.model
        headers["X-App-Version"] = getAppVersion()
        headers["X-Platform"] = "iOS"
        
        // Content Classification
        headers["X-Content-Type"] = mimeType
        headers["X-Content-Category"] = determineContentCategory(mimeType: mimeType)
        headers["X-Content-Language"] = getDeviceLanguage()
        
        // Processing Context
        headers["X-Processing-Context"] = "s3-upload"
        headers["X-Processing-Priority"] = "medium"
        
        logger.info("📋 Generated \(headers.count) S3 upload headers")
        return headers
    }
    
    private func determineContentCategory(mimeType: String) -> String {
        switch true {
        case mimeType.hasPrefix("image/"),
             mimeType.hasPrefix("video/"),
             mimeType.hasPrefix("audio/"):
            return "media"
        case mimeType.hasPrefix("text/"),
             mimeType.contains("pdf"),
             mimeType.contains("document"),
             mimeType.contains("spreadsheet"),
             mimeType.contains("presentation"):
            return "documents"
        default:
            return "data"
        }
    }
    
    private func getDeviceId() -> String {
        return UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
    }
    
    private func getAppVersion() -> String {
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }
    
    private func getDeviceLanguage() -> String {
        return Locale.preferredLanguages.first ?? "en-US"
    }
}

// MARK: - Error Types

enum S3UploadError: LocalizedError {
    case noTenantId
    case invalidURL
    case invalidResponse
    case httpError(Int)
    
    var errorDescription: String? {
        switch self {
        case .noTenantId:
            return "No tenant ID found - user not authenticated"
        case .invalidURL:
            return "Invalid S3 URL"
        case .invalidResponse:
            return "Invalid server response"
        case .httpError(let code):
            return "HTTP error: \(code)"
        }
    }
}

// Usage Example:
/*
@MainActor
class FileUploadManager: ObservableObject {
    
    @Published var uploadProgress: S3UploadProgress?
    @Published var isUploading = false
    
    private let authManager = AuthenticationManager()
    private let headersManager = HeadersManager()
    private let s3Client: P8FSS3UploadClient
    
    init() {
        self.s3Client = P8FSS3UploadClient(
            authManager: authManager,
            headersManager: headersManager
        )
    }
    
    func uploadFile(from fileURL: URL) async throws -> S3UploadResult {
        // Get file metadata
        guard let metadata = s3Client.getFileMetadata(from: fileURL) else {
            throw S3UploadError.invalidResponse
        }
        
        print("Uploading: \(metadata.name) (\(metadata.size) bytes)")
        
        isUploading = true
        uploadProgress = nil
        
        defer {
            isUploading = false
            uploadProgress = nil
        }
        
        // Upload with progress tracking
        return try await s3Client.uploadFile(
            fileURL: fileURL,
            fileName: metadata.name,
            mimeType: metadata.mimeType,
            customHeaders: [
                "X-Content-Tags": "mobile-upload,user-content",
                "X-Content-Sensitivity": "internal"
            ]
        ) { progress in
            Task { @MainActor in
                self.uploadProgress = progress
                print("Upload progress: \(progress.percentage)%")
            }
        }
    }
}
*/