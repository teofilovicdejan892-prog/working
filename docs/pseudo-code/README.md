# P8FS Mobile Flows - Pseudo Code

This directory contains pseudo-code implementations for the four core P8FS mobile application flows in both Kotlin (Android) and Swift (iOS).

## Overview

P8FS mobile apps implement four critical flows for secure, distributed file system interaction:

1. **Device Registration** - Initial app setup with Ed25519 keypair generation
2. **Device Approval** - QR code scanning to approve desktop/web clients
3. **S3 Uploads** - Secure file uploads with credential derivation
4. **SSE Chat Parsing** - Real-time chat completions over Server-Sent Events

## Flow Descriptions

### 1. Device Registration Flow

The initial setup flow when a user first opens the mobile app.

```mermaid
sequenceDiagram
    participant User
    participant MobileApp
    participant Keychain
    participant P8FS_API
    participant Email
    
    User->>MobileApp: Open app for first time
    MobileApp->>MobileApp: Check if device registered
    MobileApp->>User: Show registration screen
    User->>MobileApp: Enter email address
    
    MobileApp->>MobileApp: Generate Ed25519 keypair
    MobileApp->>Keychain: Store private key securely
    
    MobileApp->>P8FS_API: POST /api/v1/auth/register
    Note over P8FS_API: {email, public_key, device_info}
    
    P8FS_API->>P8FS_API: Create pending registration
    P8FS_API->>Email: Send 6-digit verification code
    P8FS_API->>MobileApp: 202 Accepted
    
    Email->>User: Verification code: 123456
    User->>MobileApp: Enter verification code
    
    MobileApp->>MobileApp: Create challenge (timestamp)
    MobileApp->>MobileApp: Sign challenge with private key
    MobileApp->>P8FS_API: POST /api/v1/auth/verify
    Note over P8FS_API: {email, code, challenge, signature}
    
    P8FS_API->>P8FS_API: Verify code and signature
    P8FS_API->>P8FS_API: Create tenant and bucket
    P8FS_API->>MobileApp: OAuth tokens + tenant_id
    
    MobileApp->>Keychain: Store OAuth tokens
    MobileApp->>User: Registration complete
```

**Key Implementation Points:**
- Generate Ed25519 keypair locally (never export private key)
- Store private key in secure keychain/keystore
- Sign challenges with private key to prove device ownership
- Handle OAuth token storage and refresh

### 2. Device Approval Flow

Mobile app scans QR codes to approve desktop/web applications.

```mermaid
sequenceDiagram
    participant Desktop
    participant P8FS_API
    participant MobileApp
    participant User
    participant Keychain
    
    Desktop->>P8FS_API: POST /oauth/device/code
    Note over P8FS_API: {client_id, scope, device_name}
    
    P8FS_API->>Desktop: Device code response
    Note over Desktop: {device_code, user_code, verification_uri}
    
    Desktop->>Desktop: Display QR code with user_code
    
    User->>MobileApp: Scan QR code
    MobileApp->>MobileApp: Parse QR code -> user_code
    
    MobileApp->>Keychain: Retrieve stored OAuth token
    MobileApp->>P8FS_API: GET /oauth/device?user_code={code}
    Note over MobileApp: Authorization: Bearer {mobile_token}
    
    P8FS_API->>MobileApp: Device details
    Note over P8FS_API: {device_name, platform, client_id, scope}
    
    MobileApp->>User: Show approval dialog
    Note over User: "Approve John's MacBook Pro (desktop)?"
    User->>MobileApp: Tap "Approve"
    
    MobileApp->>Keychain: Retrieve Ed25519 private key
    MobileApp->>MobileApp: Sign device_code with private key
    
    MobileApp->>P8FS_API: POST /oauth/device/approve
    Note over P8FS_API: {user_code, signature, encrypted_metadata}
    
    P8FS_API->>P8FS_API: Verify signature with stored public key
    P8FS_API->>P8FS_API: Mark device as approved
    P8FS_API->>MobileApp: Approval confirmed
    
    Desktop->>P8FS_API: POST /oauth/token (polling)
    P8FS_API->>Desktop: OAuth tokens for desktop
```

**Key Implementation Points:**
- Parse QR codes to extract user codes
- Retrieve mobile OAuth tokens from secure storage
- Sign device codes with Ed25519 private key
- Handle approval confirmation and error states

### 3. S3 Uploads Flow

Secure file uploads using derived S3 credentials.

```mermaid
sequenceDiagram
    participant User
    participant MobileApp
    participant P8FS_API
    participant S3Gateway
    participant Keychain
    
    User->>MobileApp: Select file to upload
    MobileApp->>Keychain: Retrieve OAuth access token
    
    MobileApp->>P8FS_API: GET /api/v1/credentials/s3
    Note over MobileApp: Authorization: Bearer {access_token}
    
    P8FS_API->>P8FS_API: Derive S3 credentials using HKDF
    Note over P8FS_API: HKDF(session_id, tenant_id, device_id, "s3")
    
    P8FS_API->>MobileApp: S3 credentials
    Note over P8FS_API: {access_key, secret_key, bucket, endpoint}
    
    MobileApp->>MobileApp: Prepare file upload
    MobileApp->>MobileApp: Calculate AWS Signature V4
    Note over MobileApp: HMAC-SHA256(secret_key, string_to_sign)
    
    MobileApp->>S3Gateway: PUT /bucket/file
    Note over S3Gateway: Authorization: AWS4-HMAC-SHA256 signature
    
    S3Gateway->>P8FS_API: Webhook validation
    Note over P8FS_API: Re-derive credentials and verify signature
    
    P8FS_API->>S3Gateway: Validation approved
    S3Gateway->>S3Gateway: Store file in tenant bucket
    S3Gateway->>MobileApp: Upload success
    
    MobileApp->>User: File uploaded successfully
```

**Key Implementation Points:**
- Request S3 credentials from P8FS API
- Implement AWS Signature Version 4 signing
- Handle tenant-isolated bucket paths
- Cache credentials until expiration

### 4. SSE Chat Parsing Flow

Real-time chat completions using Server-Sent Events with session management.

```mermaid
sequenceDiagram
    participant User
    participant MobileApp
    participant P8FS_API
    participant Keychain
    
    User->>MobileApp: Start new chat conversation
    MobileApp->>MobileApp: Generate new session ID (UUID)
    MobileApp->>MobileApp: Initialize chat thread locally
    
    User->>MobileApp: Type message "What is P8FS?"
    MobileApp->>Keychain: Retrieve OAuth access token
    MobileApp->>MobileApp: Prepare content headers
    
    MobileApp->>P8FS_API: POST /api/v1/chat/completions
    Note over P8FS_API: Headers:<br/>Authorization: Bearer {token}<br/>X-Tenant-ID: {tenant}<br/>X-Device-Type: mobile<br/>X-Device-ID: {device_id}<br/>X-User-Email: {email}<br/>X-Session-ID: {session_id}<br/>X-Device-Model: {model}<br/>X-Platform: iOS/Android
    Note over P8FS_API: Body:<br/>{<br/>  "messages": [{"role": "user", "content": "What is P8FS?"}],<br/>  "model": "gpt-4.1-mini",<br/>  "stream": true<br/>}
    
    P8FS_API->>P8FS_API: Server manages thread using session_id
    P8FS_API->>MobileApp: SSE Stream opens
    Note over P8FS_API: Content-Type: text/event-stream
    
    P8FS_API->>MobileApp: data: {"id":"chatcmpl-abc","choices":[{"delta":{"content":"P8"}}]}
    MobileApp->>MobileApp: Parse JSON chunk
    MobileApp->>User: Display "P8"
    
    P8FS_API->>MobileApp: data: {"id":"chatcmpl-abc","choices":[{"delta":{"content":"-FS"}}]}
    MobileApp->>MobileApp: Parse JSON chunk  
    MobileApp->>User: Append "-FS" -> "P8-FS"
    
    P8FS_API->>MobileApp: data: {"id":"chatcmpl-abc","choices":[{"delta":{"content":" is..."}}]}
    MobileApp->>MobileApp: Parse JSON chunk
    MobileApp->>User: Append " is..." -> "P8-FS is..."
    
    P8FS_API->>MobileApp: data: {"id":"chatcmpl-abc","choices":[{"finish_reason":"stop"}]}
    MobileApp->>MobileApp: Detect completion
    MobileApp->>MobileApp: Store complete message in thread
    
    P8FS_API->>MobileApp: data: [DONE]
    MobileApp->>MobileApp: Close SSE connection
    
    User->>MobileApp: Type follow-up "Tell me more"
    MobileApp->>P8FS_API: POST /api/v1/chat/completions
    Note over P8FS_API: Same session_id + headers<br/>Only new user message:<br/>{"messages": [{"role": "user", "content": "Tell me more"}]}
    
    P8FS_API->>P8FS_API: Server uses session_id to retrieve full thread context
    P8FS_API->>MobileApp: SSE response continues conversation
```

**Key Implementation Points:**
- Generate unique session ID (UUID) for each new chat conversation
- Send comprehensive content headers on every request (device info, user context)
- Only send the latest user message, not entire chat history
- Server maintains thread context using session ID
- Store complete assistant responses locally for UI display
- Handle connection errors and reconnection with same session ID
- Parse streaming JSON chunks in real-time with proper error handling

## File Structure

```
docs/pseudo-code/
├── README.md                           # This file - flow explanations and Mermaid diagrams
├── device-registration/
│   ├── DeviceRegistration.kt          ✅ Android implementation with Ed25519 + secure storage
│   └── DeviceRegistration.swift       🚧 iOS implementation (pending)
├── device-approval/
│   ├── DeviceApproval.kt              ✅ Android implementation with QR code scanning
│   └── DeviceApproval.swift           🚧 iOS implementation (pending)
├── s3-uploads/
│   ├── S3Upload.kt                    ✅ Android implementation with AWS V4 signatures
│   └── S3Upload.swift                 ✅ iOS implementation with CryptoKit
└── sse-chat-parsing/
    ├── SSEChatParser.kt               ✅ Android implementation with session management
    └── SSEChatParser.swift            ✅ iOS implementation with URLSession
```

## Key Security Considerations

### Cryptographic Operations
- **Ed25519 Keypairs**: Generate locally, never export private keys
- **Secure Storage**: Use Android Keystore and iOS Keychain exclusively
- **Challenge Signing**: Sign unique challenges to prevent replay attacks
- **AWS Signature V4**: Proper HMAC-SHA256 implementation for S3 uploads

### Network Security
- **TLS 1.3**: All API communications over encrypted connections
- **Certificate Pinning**: Pin P8FS API certificates in mobile apps
- **Token Management**: Secure storage and automatic refresh of OAuth tokens
- **Tenant Isolation**: Ensure all operations are scoped to user's tenant

### Error Handling
- **Graceful Degradation**: Handle network errors and API timeouts
- **Retry Logic**: Exponential backoff for failed operations
- **User Feedback**: Clear error messages without exposing internals
- **Security Logging**: Log security events without sensitive data

## Platform-Specific Notes

### Android (Kotlin)
- Use **Android Keystore** for private key storage
- Implement **OkHttp** for HTTP/SSE connections
- Handle **Android lifecycle** for background operations
- Use **WorkManager** for background uploads

### iOS (Swift)
- Use **Keychain Services** for secure storage
- Implement **URLSession** for HTTP/SSE connections
- Handle **iOS app lifecycle** and background modes
- Use **Background Tasks** for upload completion

## Implementation Files

### Device Registration Flow
- **[DeviceRegistration.kt](device-registration/DeviceRegistration.kt)** - Android implementation with Ed25519 keypair generation, secure storage in EncryptedSharedPreferences, email verification with challenge-response, and comprehensive error handling
- **DeviceRegistration.swift** *(Coming Soon)* - iOS implementation with Keychain Services, CryptoKit Ed25519, and biometric authentication

### Device Approval Flow  
- **[DeviceApproval.kt](device-approval/DeviceApproval.kt)** - Android implementation with deep link handling for QR codes (p8fs://auth), 8-digit PIN field UI, device details fetching and display, and Ed25519 signature-based approval
- **DeviceApproval.swift** *(Coming Soon)* - iOS implementation with URL schemes, biometric approval, and Keychain integration

### S3 Uploads Flow
- **[S3Upload.kt](s3-uploads/S3Upload.kt)** - Android implementation with AWS V4 signature generation, hardcoded SeaweedFS credentials, JWT parsing for tenant isolation, and HKDF-based credential derivation concepts
- **[S3Upload.swift](s3-uploads/S3Upload.swift)** - iOS implementation with CryptoKit HMAC, URLSession uploads, and background task completion

### SSE Chat Parsing Flow
- **[SSEChatParser.kt](sse-chat-parsing/SSEChatParser.kt)** - Android implementation with session ID generation (UUID), comprehensive header management including X-Session-ID, SSE parsing with proper error handling, and support for X-CHAT-IS-AUDIO header
- **[SSEChatParser.swift](sse-chat-parsing/SSEChatParser.swift)** - iOS implementation with URLSessionDataTask, real-time JSON parsing, and background mode support

## API Documentation References

For complete API specifications, see:
- **[../p8fs/authentication.md](../p8fs/authentication.md)** - OAuth 2.1 device flow, Ed25519 key management, and JWT token handling
- **[../p8fs/chat.md](../p8fs/chat.md)** - OpenAI-compatible chat completions API with streaming support
- **[../p8fs/content_headers.md](../p8fs/content_headers.md)** - Complete HTTP header reference for user context and device information

## Next Steps

1. **Review Implementation Files**: Study the Kotlin and Swift pseudo-code in each flow directory
2. **Adapt Architecture**: Modify code patterns to fit your specific mobile app architecture  
3. **Error Handling**: Implement proper error handling and user experience flows
4. **Testing**: Add unit tests for cryptographic operations and network layer
5. **Security Audit**: Perform security audits of key management and network operations

The pseudo-code provides a foundation for implementing P8FS mobile client functionality while maintaining security best practices and user experience standards.