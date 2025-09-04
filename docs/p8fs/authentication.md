# P8-FS Authentication Architecture

## Overview

P8-FS implements a comprehensive, mobile-first authentication system that combines OAuth 2.1 standards with innovative cryptographic approaches. The system treats mobile devices as the primary authentication factor while enabling secure access for desktop applications, APIs, and distributed storage systems.

## Core Principles

1. **Zero Trust Architecture**: All components start with no permissions until authenticated
2. **Mobile-First Security**: Mobile devices hold primary authentication keys
3. **OAuth 2.1 Compliance**: Full implementation of the latest OAuth security standards
4. **End-to-End Encryption**: No secrets transmitted in plaintext
5. **Tenant Isolation**: Complete separation between different user accounts
6. **Derived Credentials**: S3 and API keys generated deterministically from sessions

## Integration Test Framework

P8-FS includes a comprehensive integration test framework specifically for authentication flows. The tests serve as the **authoritative reference implementation** for all client libraries and validate the complete OAuth 2.1 Device Flow.

**Location**: `/tests/integration/auth/`

**Quick Commands**:
```bash
# Test against local deployment (default - from P8-FS root directory)
kubectl port-forward -n p8fs svc/p8fs-api 8000:8000 &
uv run pytest tests/integration/auth -v

# Test against production deployment  
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run pytest tests/integration/auth -v

# Run specific test categories
uv run pytest tests/integration/auth/test_mobile_flow.py -v
uv run pytest tests/integration/auth/test_auth_ping.py::TestAuthPing::test_ping_anonymous -v

# Interactive CLI for mobile testing (with QR codes)
uv run python tests/integration/auth/interactive_auth_test.py

# Working OAuth demo with QR codes (supports P8FS_AUTH_SERVER env var)
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run python working_oauth_demo.py
```

**Test Coverage**:
- ✅ Mobile device registration with Ed25519 validation
- ✅ OAuth 2.1 device authorization flow  
- ✅ QR code generation and scanning simulation
- ✅ S3 credential derivation and validation
- ✅ Production deployment verification
- ✅ Security validation (invalid keys rejected)

## Authentication Architecture Overview

```mermaid
graph TB
    subgraph "User Devices"
        Mobile[Mobile App<br/>Private Keys]
        Desktop[Desktop App<br/>Session Tokens]
        Browser[Web Browser<br/>OAuth Tokens]
    end
    
    subgraph "P8-FS Authentication Core"
        AuthAPI[Auth API<br/>OAuth 2.1 Server]
        SessionStore[(Session Store<br/>TiKV)]
        KeyDerivation[Key Derivation<br/>Service]
        WebhookValidator[Webhook<br/>Validator]
    end
    
    subgraph "Storage Systems"
        SeaweedFS[SeaweedFS<br/>S3 Gateway]
        S3API[S3 API<br/>Endpoints]
        DataStore[(Object Storage)]
    end
    
    subgraph "External Services"
        EmailProvider[Email Service<br/>SendGrid/SES]
        PushService[Push Notifications<br/>FCM/APNS]
    end
    
    Mobile -->|QR Scan| Desktop
    Mobile -->|OAuth 2.1| AuthAPI
    Desktop -->|Polling| AuthAPI
    Browser -->|OAuth 2.1| AuthAPI
    
    AuthAPI --> SessionStore
    AuthAPI --> KeyDerivation
    AuthAPI --> EmailProvider
    AuthAPI --> PushService
    
    KeyDerivation -->|Derived Keys| WebhookValidator
    
    SeaweedFS -->|Validate S3| WebhookValidator
    S3API --> SeaweedFS
    SeaweedFS --> DataStore
    
    Desktop -->|S3 Requests| S3API
    Browser -->|API Calls| AuthAPI
```

## OAuth 2.1 Implementation

### Supported Grant Types

P8-FS implements OAuth 2.1 with the following grant types:

1. **Authorization Code + PKCE** (Required for all clients)
2. **Refresh Token Grant** (With rotation for public clients)
3. **Device Authorization Grant** (For CLI and IoT devices)

### OAuth 2.1 Compliance Features

- **PKCE Required**: All authorization code flows use PKCE
- **Exact Redirect URI Matching**: No pattern matching allowed
- **No Implicit Grant**: Removed for security
- **No Password Grant**: Replaced with mobile authentication
- **Bearer Token Restrictions**: No tokens in query parameters
- **Sender-Constrained Refresh Tokens**: For public clients

## Authentication Flows

### 1. Mobile App Initial Setup

```mermaid
sequenceDiagram
    participant User
    participant Mobile
    participant AuthAPI
    participant Email
    
    User->>Mobile: 1.1 Install app
    Mobile->>Mobile: 1.2 Generate Ed25519 keypair
    User->>Mobile: 1.3 Enter email address
    Mobile->>AuthAPI: 1.4 POST /api/v1/auth/register
    Note right of AuthAPI: {<br/>  "email": "user@example.com",<br/>  "public_key": "ed25519_public_key",<br/>  "device_info": {...}<br/>}
    
    AuthAPI->>AuthAPI: 1.5 Create pending registration
    AuthAPI->>Email: 1.6 Send verification code
    AuthAPI->>Mobile: 1.7 202 Accepted
    
    Email->>User: 1.8 Verification code: 123456 + QR code link
    Note over Email,User: QR contains: p8fs://verify?code=123456&email=user@example.com
    
    alt Manual code entry
        User->>Mobile: 1.9a Enter code manually
    else QR code scan
        User->>Mobile: 1.9b Scan QR code from email
    end
    
    Mobile->>AuthAPI: 1.10 POST /api/v1/auth/verify
    Note right of AuthAPI: {<br/>  "email": "user@example.com",<br/>  "code": "123456",<br/>  "challenge": "random_challenge",<br/>  "signature": "ed25519_signature"<br/>}
    
    AuthAPI->>AuthAPI: 1.11 Verify code & signature
    AuthAPI->>AuthAPI: 1.12 Create tenant & bucket
    AuthAPI->>Mobile: 1.13 200 OK + OAuth tokens
    Note left of AuthAPI: {<br/>  "access_token": "...",<br/>  "refresh_token": "...",<br/>  "tenant_id": "...",<br/>  "expires_in": 3600<br/>}
```

### 2. Desktop Authentication via QR Code

```mermaid
sequenceDiagram
    participant Desktop
    participant AuthAPI
    participant Mobile
    participant User
    
    Desktop->>Desktop: 2.1 Generate X25519 ephemeral keypair
    Desktop->>AuthAPI: 2.2 POST /oauth/device/code
    Note right of AuthAPI: {<br/>  "client_id": "p8-node-desktop",<br/>  "scope": "read write sync"<br/>}
    
    AuthAPI->>AuthAPI: 2.3 Create device auth session
    AuthAPI->>Desktop: 2.4 Device code response
    Note left of AuthAPI: {<br/>  "device_code": "ABCD-1234",<br/>  "user_code": "WXYZ-5678",<br/>  "verification_uri": "p8fs://auth",<br/>  "expires_in": 600,<br/>  "interval": 5<br/>}
    
    Desktop->>Desktop: 2.5 Display QR code
    Desktop->>AuthAPI: 2.6 Start polling<br/>POST /oauth/device/token
    
    User->>Mobile: 2.7 Scan QR code
    Note over User,Mobile: QR code contains: p8fs://auth/device?code=WXYZ-5678
    Mobile->>Mobile: 2.8 App opens via deep link
    Mobile->>AuthAPI: 2.9 GET /oauth/device/{user_code}
    Note right of Mobile: Authorization: Bearer {mobile_access_token}
    AuthAPI->>Mobile: 2.10 Device details
    
    Mobile->>User: 2.11 Show device info
    User->>Mobile: 2.12 Approve
    
    Mobile->>Mobile: 2.13 Sign approval with Ed25519 private key
    Mobile->>AuthAPI: 2.14 POST /oauth/device/approve
    Note right of AuthAPI: Authorization: Bearer {mobile_access_token}<br/>Content-Type: application/x-www-form-urlencoded<br/>device_code=ABCD-1234&signature=ed25519_sig&encrypted_metadata=
    
    AuthAPI->>AuthAPI: 2.15 Verify signature with registered public key
    Note over AuthAPI: Ensures approval came from the<br/>same device that registered
    AuthAPI->>AuthAPI: 2.16 Generate tokens for desktop
    
    Desktop->>AuthAPI: 2.17 POST /oauth/device/token
    AuthAPI->>Desktop: 2.18 Access tokens
    Note left of AuthAPI: {<br/>  "access_token": "...",<br/>  "refresh_token": "...",<br/>  "token_type": "Bearer",<br/>  "expires_in": 3600,<br/>  "scope": "read write sync"<br/>}
```

**Note on QR Code Implementation**: The QR code displayed in step 2.5 contains a deep link URL (e.g., `p8fs://auth/device?code=WXYZ-5678`) that opens the P8-FS mobile app when scanned. This is essential because:
- The mobile app must be able to access its stored private key (step 2.12)
- The app needs to send its authenticated bearer token (step 2.8)
- The app can access device-specific information and the user's session

If the QR code pointed to a regular web URL, the browser wouldn't have access to the app's secure storage containing the private keys and authentication tokens, making the secure approval impossible.

**Important: Identity Transfer During Device Approval**: When a mobile device approves a desktop device (step 2.14), the following identity transfer occurs:
- The mobile device MUST send its JWT access token in the Authorization header
- The OAuth service extracts the mobile device's `tenant_id`, `sub` (user ID), and `email` from the JWT
- The approved desktop device receives tokens containing:
  - The same `tenant_id` as the mobile device (ensuring proper tenant isolation)
  - The mobile device's user ID instead of a placeholder device ID
  - Proper authorization scopes as requested

This ensures that desktop devices inherit the complete identity context from the approving mobile device, maintaining consistent tenant isolation and user identity across all authenticated devices.

### 3. S3 Credential Derivation

```mermaid
sequenceDiagram
    participant Desktop
    participant AuthAPI
    participant KDF
    participant SeaweedFS
    
    Desktop->>AuthAPI: 3.1 GET /api/v1/credentials/s3
    Note right of AuthAPI: Authorization: Bearer {access_token}
    
    AuthAPI->>AuthAPI: 3.2 Validate token
    AuthAPI->>KDF: 3.3 Derive S3 credentials
    Note right of KDF: HKDF with:<br/>- Session ID<br/>- Tenant ID<br/>- Device ID<br/>- Purpose: s3
    
    KDF->>KDF: 3.4 Generate credentials
    KDF->>AuthAPI: 3.5 S3 credentials
    
    AuthAPI->>Desktop: 3.6 Credential response
    Note left of AuthAPI: {<br/>  "access_key_id": "P8FSXXXXX",<br/>  "secret_access_key": "...",<br/>  "session_token": "...",<br/>  "expiration": "2024-01-16T10:00:00Z",<br/>  "bucket": "p8fs-tenant-12345",<br/>  "endpoint": "https://s3.example.com"<br/>}
    
    Desktop->>Desktop: 3.7 Cache credentials
    Desktop->>SeaweedFS: 3.8 S3 API request
    Note right of SeaweedFS: Authorization: AWS4-HMAC-SHA256...
    
    SeaweedFS->>AuthAPI: 3.9 Webhook validation
    AuthAPI->>AuthAPI: 3.10 Re-derive & validate
    AuthAPI->>SeaweedFS: 3.11 Validation response
    SeaweedFS->>Desktop: 3.12 S3 response
```

### 4. Email Verification Flow (Optional)

```mermaid
sequenceDiagram
    participant User
    participant Mobile
    participant AuthAPI
    participant Email
    
    User->>Mobile: 4.1 Add new email
    Mobile->>AuthAPI: 4.2 POST /api/v1/email/add
    Note right of AuthAPI: {<br/>  "email": "new@example.com",<br/>  "signature": "ed25519_sig"<br/>}
    
    AuthAPI->>AuthAPI: 4.3 Generate 6-digit code
    AuthAPI->>Email: 4.4 Send verification
    AuthAPI->>Mobile: 4.5 202 Accepted
    
    Email->>User: 4.6 Code: 456789
    User->>Mobile: 4.7 Enter code
    
    Mobile->>AuthAPI: 4.8 POST /api/v1/email/verify
    Note right of AuthAPI: {<br/>  "email": "new@example.com",<br/>  "code": "456789",<br/>  "signature": "ed25519_sig"<br/>}
    
    AuthAPI->>AuthAPI: 4.9 Verify & link email
    AuthAPI->>Mobile: 4.10 200 OK
```

## API Endpoints

### OAuth 2.1 Endpoints

| Endpoint | Method | Description | Authentication | Used in Flows |
|----------|--------|-------------|----------------|---------------|
| `/oauth/authorize` | GET | Authorization endpoint | None | - |
| `/oauth/token` | POST | Token endpoint | Client credentials | - |
| `/oauth/revoke` | POST | Token revocation | Client credentials | - |
| `/oauth/introspect` | POST | Token introspection | Client credentials | - |
| `/oauth/userinfo` | GET | User information | Bearer token | - |
| `/oauth/device/code` | POST | Device code request | Client ID | Flow 2 (step 2.2) |
| `/oauth/device/token` | POST | Device token polling | Device code | Flow 2 (steps 2.6, 2.17) |
| `/oauth/device/approve` | POST | Device approval (mobile) | Bearer token OR API key | Flow 2 (step 2.14) |
| `/oauth/device/api-key` | POST | Generate temporary API key | Bearer token | Testing/Development |
| `/.well-known/oauth-authorization-server` | GET | OAuth metadata | None | - |

#### Device Approval Request Format

The `/oauth/device/approve` endpoint supports **two authentication methods** and uses **form-encoded data**, not JSON:

##### Method 1: JWT Token + Ed25519 Signature (Standard Mobile Flow)

**Request Format**:
```http
POST /oauth/device/approve HTTP/1.1
Host: p8fs.percolationlabs.ai
Content-Type: application/x-www-form-urlencoded
Authorization: Bearer {mobile_access_token}
X-Device-ID: {device_id}
X-User-Email: {user_email}
X-Tenant-ID: {tenant_id}

device_code=dev__esAVL5AOGorKFpjrLBvF5tJY4B9lyrkJwxy6esrR2Q&user_code=4180-8305&signature=base64_encoded_ed25519_signature&encrypted_metadata=
```

**Required Form Fields**:
- `device_code` (string) - The full device code from the authorization session (e.g., `dev__...`) [optional - looked up from user_code]
- `user_code` (string) - The user code from the QR scan (e.g., `4180-8305`)
- `signature` (string) - Ed25519 signature of the device code using mobile device's private key

**Optional Form Fields**:
- `encrypted_metadata` (string) - Encrypted device metadata (can be empty string)

**Signature Process**:
1. Mobile device signs the `device_code` string (not user_code) with its Ed25519 private key
2. Signature is base64 encoded before sending
3. Server verifies signature using the device's registered public key

**Example with curl**:
```bash
curl -X POST https://p8fs.percolationlabs.ai/oauth/device/approve \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Bearer p8fs_at_mobile_token" \
  -d "device_code=dev__esAVL5AOGorKFpjrLBvF5tJY4B9lyrkJwxy6esrR2Q&user_code=4180-8305&signature=AZIhnlb1tXfI9ljUJI2ImKdKCr7LUUyHYbocFk&encrypted_metadata="
```

##### Method 2: Temporary API Key (Testing/Development Flow)

**IMPORTANT**: This is a temporary testing feature to unblock development scenarios where mobile approval may not be convenient.

**Step 1: Generate API Key**
```http
POST /oauth/device/api-key HTTP/1.1
Host: p8fs.percolationlabs.ai
Content-Type: application/x-www-form-urlencoded
Authorization: Bearer {mobile_access_token}

user_code=4180-8305
```

**Response**:
```json
{
  "api_key": "p8fs-akey-a1b2c3d4e5f6789012345678901234567890abcdef"
}
```

**Step 2: Use API Key for Approval**
```http
POST /oauth/device/approve HTTP/1.1
Host: p8fs.percolationlabs.ai
Content-Type: application/x-www-form-urlencoded

user_code=4180-8305&api_key=p8fs-akey-a1b2c3d4e5f6789012345678901234567890abcdef
```

**API Key Properties**:
- Format: `p8fs-akey-{32-character-hash}`
- Expiration: 15 minutes from generation
- Usage: One-time use only (consumed upon approval)
- Scope: Tied to specific user_code and tenant_id
- Purpose: Allows device approval without mobile signature

**Example with curl**:
```bash
# Step 1: Generate API key (requires mobile JWT)
API_KEY=$(curl -X POST https://p8fs.percolationlabs.ai/oauth/device/api-key \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Bearer p8fs_at_mobile_token" \
  -d "user_code=4180-8305" | jq -r .api_key)

# Step 2: Use API key for approval (no JWT required)
curl -X POST https://p8fs.percolationlabs.ai/oauth/device/approve \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "user_code=4180-8305&api_key=$API_KEY"
```

**Client Implementation Notes**:
- Show QR code for standard mobile scanning flow
- Also display option: "Enter temporary API key that was sent to your email"
- Users can generate API key via authenticated mobile app/web interface
- API key enables approval without device signing requirement

### Mobile Authentication Endpoints

| Endpoint | Method | Description | Authentication | Used in Flows |
|----------|--------|-------------|----------------|---------------|
| `/api/v1/auth/register` | POST | Register new mobile device | None | Flow 1 (step 1.4) |
| `/api/v1/auth/verify` | POST | Verify email code | Temp token | Flow 1 (step 1.10) |
| `/api/v1/auth/email/add` | POST | Add new email address | Bearer token | Flow 4 (step 4.2) |
| `/api/v1/auth/email/verify` | POST | Verify additional email | Bearer token | Flow 4 (step 4.8) |
| `/api/v1/auth/keypair/rotate` | POST | Rotate device keypair | Bearer token | - |
| `/api/v1/auth/devices` | GET | List authorized devices | Bearer token | - |
| `/api/v1/auth/devices/{id}` | DELETE | Revoke device access | Bearer token | - |

### Credential Management Endpoints

P8-FS implements a sophisticated credential management system that derives S3 credentials deterministically using HKDF (HMAC-based Key Derivation Function). This ensures zero-credential storage while providing secure, tenant-isolated access to storage systems.

#### Session-Based Credential Management

| Endpoint | Method | Description | Authentication | Used in Flows |
|----------|--------|-------------|----------------|---------------|
| `POST /api/v1/credentials/sessions` | POST | Create credential session | Bearer token | - |
| `GET /api/v1/credentials/sessions` | GET | List active sessions | Bearer token | - |
| `POST /api/v1/credentials/derive` | POST | Derive S3 credentials from session | Bearer token | - |
| `GET /api/v1/credentials/derive-quick` | GET | Quick credential derivation | Bearer token | - |

#### Direct Credential Access

| Endpoint | Method | Description | Authentication | Used in Flows |
|----------|--------|-------------|----------------|---------------|
| `/api/v1/credentials/s3` | GET | Get S3 credentials (direct) | Bearer token | Flow 3 (step 3.1) |
| `/api/v1/credentials/api` | GET | Get API key | Bearer token | - |
| `/api/v1/credentials/rotate` | POST | Force credential rotation | Bearer token | - |

#### Credential Derivation Process

The P8-FS credential system uses HKDF-SHA256 to derive S3 credentials deterministically:

```python
# Credential derivation inputs
session_id = "session_abc123"
tenant_id = "tenant_def456" 
device_id = "device_ghi789"
purpose = "s3"

# HKDF derivation
access_key = hkdf_derive(session_id, tenant_id, device_id, purpose, "access_key")
secret_key = hkdf_derive(session_id, tenant_id, device_id, purpose, "secret_key")

# Result format
{
  "access_key_id": "P8FSXXXXX",
  "secret_access_key": "derived_secret",
  "session_token": "p8fs_st_...",
  "bucket": "p8fs-tenant-{tenant_id}",
  "endpoint": "https://s3.example.com",
  "expiration": "2024-01-16T10:00:00Z"
}
```

#### Session Management

**Creating Sessions**:
```bash
POST /api/v1/credentials/sessions
{
  "purpose": "s3",
  "bucket_name": "optional-specific-bucket",
  "lifetime_hours": 12
}
```

**Session Response**:
```json
{
  "session_id": "cred_session_abc123",
  "purpose": "s3",
  "expires_at": "2024-01-16T22:00:00Z",
  "tenant_id": "tenant_def456",
  "device_id": "device_ghi789"
}
```

#### Security Features

- **Zero Storage**: No credentials stored in databases
- **Deterministic**: Same inputs always produce same credentials
- **Tenant Isolation**: Credentials scoped to tenant prefix
- **Time-Limited**: Sessions expire automatically
- **Revocable**: Session invalidation revokes all derived credentials

### Device Pairing Endpoints

P8-FS implements a QR code-based device pairing system that allows secure authentication between mobile and desktop devices using X25519 key exchange and ephemeral sessions.

#### QR Code Session Management

| Endpoint | Method | Description | Authentication | Response |
|----------|--------|-------------|----------------|----------|
| `POST /api/v1/device/qr/create` | POST | Create QR pairing session | Bearer token | QR session data |
| `POST /api/v1/device/qr/scan` | POST | Process QR code scan | Bearer token | Session approval |
| `GET /api/v1/device/session/{device_code}/status` | GET | Get pairing session status | None | Session status |

#### Device Pairing Flow

**1. Desktop Initiates Pairing**:
```bash
POST /api/v1/device/qr/create
{
  "device_name": "John's MacBook Pro",
  "client_id": "p8-node-desktop",
  "scope": "read write sync"
}
```

**Response**:
```json
{
  "device_code": "ABCD-1234-EFGH-5678",
  "user_code": "WXYZ-9012",
  "verification_uri": "p8fs://auth/device?code=WXYZ-9012",
  "qr_code_data": "p8fs://auth/device?code=WXYZ-9012&session_id=abc123",
  "expires_in": 600,
  "interval": 5
}
```

**2. Mobile Scans QR Code**:
```bash
POST /api/v1/device/qr/scan
Authorization: Bearer p8fs_at_mobile_token
{
  "qr_data": "p8fs://auth/device?code=WXYZ-9012&session_id=abc123",
  "device_public_key": "x25519_public_key_base64",
  "approve": true
}
```

**Response**:
```json
{
  "status": "approved",
  "encrypted_response": "encrypted_approval_data",
  "session_id": "abc123"
}
```

**3. Desktop Polls for Status**:
```bash
GET /api/v1/device/session/ABCD-1234-EFGH-5678/status
```

**Response (Approved)**:
```json
{
  "status": "approved",
  "access_token": "p8fs_at_desktop_token",
  "refresh_token": "p8fs_rt_desktop_token",
  "expires_in": 3600,
  "scope": "read write sync"
}
```

#### Security Features

- **X25519 Key Exchange**: Ephemeral key agreement between devices
- **Short-Lived Sessions**: QR codes expire in 10 minutes
- **Encrypted Approval**: Mobile approval data encrypted with shared secret
- **Device Validation**: Desktop device info verified before approval
- **Cryptographic Proof**: Ed25519 signatures prove mobile device identity

#### QR Code Format

The QR codes contain deep link URLs that open the P8-FS mobile app:
```
p8fs://auth/device?code=WXYZ-9012&session_id=abc123&desktop_key=x25519_pubkey
```

This ensures the mobile app can:
- Access stored private keys for signing
- Send authenticated bearer tokens
- Perform cryptographic operations securely

### Internal Webhook System

P8-FS implements a comprehensive internal webhook system for secure integration with SeaweedFS and other storage systems. The webhook system provides real-time validation and event processing.

#### Webhook Endpoints

| Endpoint | Method | Description | Authentication | Used in Flows |
|----------|--------|-------------|----------------|---------------|
| `/internal/s3/validate` | POST | S3 request validation | Webhook secret | Flow 3 (step 3.9) |
| `/internal/webhooks/seaweedfs` | POST | File event notifications | Webhook secret | - |
| `/internal/debug/usercode/{user_code}` | GET | Debug user code lookup | Internal only | - |
| `/internal/health` | GET | Internal health check | None | - |

#### S3 Request Validation

The `/internal/s3/validate` endpoint implements zero-credential validation for SeaweedFS S3 requests:

**Request Format**:
```json
{
  "access_key": "P8FSXXXXX",
  "signature": "AWS4-HMAC-SHA256 signature",
  "string_to_sign": "canonical_request_hash",
  "request_method": "GET",
  "request_uri": "/bucket/object",
  "request_headers": {
    "authorization": "AWS4-HMAC-SHA256 ...",
    "x-amz-date": "20240116T100000Z"
  }
}
```

**Validation Process**:
1. Extract tenant ID from access key pattern (`P8FS{tenant_hash}`)
2. Re-derive secret key using HKDF with session context
3. Calculate expected AWS v4 signature
4. Compare signatures using constant-time comparison
5. Validate tenant permissions for requested resource

**Response Format**:
```json
{
  "valid": true,
  "tenant_id": "tenant_abc123",
  "bucket_prefix": "tenants/tenant_abc123/",
  "permissions": ["read", "write"],
  "cache_ttl": 300
}
```

#### File Event Notifications

The `/internal/webhooks/seaweedfs` endpoint receives file system events:

**Event Types**:
- `file.created` - New file uploaded
- `file.updated` - File modified
- `file.deleted` - File removed
- `directory.created` - New directory created

**Event Format**:
```json
{
  "event_type": "file.created",
  "timestamp": "2024-01-16T10:00:00Z",
  "path": "/tenants/tenant_abc123/files/document.pdf",
  "size": 1048576,
  "content_type": "application/pdf",
  "etag": "abc123def456",
  "tenant_id": "tenant_abc123"
}
```

#### Webhook Security

**Authentication Methods**:
- **Shared Secret**: `X-SeaweedFS-Secret` header validation
- **Instance Verification**: `X-SeaweedFS-Instance` hostname validation
- **IP Allowlist**: Internal cluster IP range restriction
- **Request Signing**: HMAC signatures for critical operations

**Configuration Example**:
```toml
[s3.webhook]
url = "http://p8fs-api.p8fs.svc.cluster.local:8000/internal/s3/validate"
timeout = "5s"
cache_ttl = "300s"
retry_attempts = 3

[[s3.webhook.headers]]
name = "X-SeaweedFS-Secret"
value = "${SEAWEEDFS_WEBHOOK_SECRET}"

[[s3.webhook.headers]]
name = "X-SeaweedFS-Instance"
value = "${HOSTNAME}"
```

#### Performance Characteristics

- **Validation Latency**: < 50ms (suitable for webhook timeouts)
- **Cache Hit Rate**: 90%+ for repeated validations
- **Throughput**: 1000+ validations/second
- **Error Rate**: < 0.1% under normal conditions

#### Monitoring and Debugging

**Health Check**:
```bash
GET /internal/health
{
  "status": "healthy",
  "webhook_secret_configured": true,
  "tikv_connection": "connected",
  "validation_cache_size": 1024
}
```

**Debug Endpoints**:
```bash
GET /internal/debug/usercode/WXYZ-9012
{
  "user_code": "WXYZ-9012",
  "device_code": "ABCD-1234",
  "status": "pending",
  "expires_at": "2024-01-16T10:10:00Z"
}
```

### Advanced Authentication Dependencies

P8-FS provides sophisticated authentication dependencies that handle dual-algorithm JWT verification, automatic key management, and comprehensive logging for debugging authentication issues.

#### Authentication Dependency Functions

The authentication system provides several FastAPI dependency functions for different authentication requirements.

**Note:** As of August 2025, P8-FS uses a unified authentication system located in `src/p8fs/api/dependencies/auth.py`. Previous authentication implementations have been consolidated to eliminate redundancy and ensure consistent JWT token validation across all endpoints.

| Function | Purpose | Returns | Error Behavior |
|----------|---------|---------|----------------|
| `get_optional_token()` | Optional authentication | `TokenPayload \| None` | Returns `None` on invalid token |
| `require_auth()` | Mandatory authentication | `TokenPayload` | Raises `401` if no valid token |
| `require_mobile_auth()` | Mobile device only | `TokenPayload` | Raises `403` if not mobile device |
| `require_desktop_auth()` | Desktop device only | `TokenPayload` | Raises `403` if not desktop device |
| `get_tenant_id()` | Extract tenant context | `str` | Raises `400` if no tenant ID |

#### Dual-Algorithm JWT Support

The authentication system supports both ES256 (asymmetric) and HS256 (symmetric) JWT tokens:

**ES256 Tokens (Mobile/Production)**:
```python
# Token verification process
if unverified_header.get("alg") == "ES256":
    # Retrieve system JWT signing key from TiKV
    system_key_data = await tikv.aget("system:jwt:signing_key", "system")
    public_key_pem = system_key_data.get("public_key_pem")
    
    # Verify with system public key
    payload = jwt.decode(
        token,
        public_key_pem,
        algorithms=["ES256"],
        audience=unverified_payload.get("aud"),
        issuer=unverified_payload.get("iss")
    )
```

**HS256 Tokens (Legacy)**:
```python
# Symmetric key verification
payload = jwt.decode(
    token,
    settings.jwt_secret,
    algorithms=["HS256"],
    options={"verify_exp": True}
)
```

#### Token Payload Structure

The `TokenPayload` model contains validated token claims:

```python
class TokenPayload:
    sub: str                    # User ID
    tenant_id: str             # Tenant identifier
    device_id: Optional[str]   # Device identifier
    device_type: Optional[str] # "mobile" or "desktop"
    scope: Optional[str]       # OAuth scopes
    client_id: Optional[str]   # OAuth client ID
    exp: int                   # Expiration timestamp
    iat: int                   # Issued at timestamp
```

#### Comprehensive Authentication Logging

The system provides extensive logging for debugging authentication issues:

**Token Verification Logging**:
```
🔍 JWT VERIFICATION STARTING
   Token (first 50 chars): p8fs_at_eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9...
   Stripped p8fs_at_ prefix from token

📝 JWT HEADER:
   - typ: JWT
   - alg: ES256

📝 JWT PAYLOAD (unverified):
   - iss: https://p8fs.percolationlabs.ai
   - sub: testing@percolationlabs.ai
   - tenant_id: p8ptest
   - device_type: mobile

🔑 ES256 TOKEN DETECTED - Using SYSTEM JWT key for verification
   Retrieving system JWT signing key from TiKV...
   ✅ System JWT public key retrieved successfully
   Key ID: system:jwt:signing_key
   Algorithm: ES256

🔐 Verifying JWT signature with system key...
✅ JWT SIGNATURE VERIFIED SUCCESSFULLY!
   Tenant ID: p8ptest
   User ID: testing@percolationlabs.ai
   Device ID: device_test123
   Token Type: access_token
   Scope: read write sync
```

**Error Logging**:
```
❌ JWT VERIFICATION FAILED: Invalid signature
   This usually means the token was signed with a different key
   Ensure the mobile app received the token from THIS server

❌ NO SYSTEM JWT SIGNING KEY FOUND!
   The system JWT key should be at system:jwt:signing_key
   This key is created by JWTKeyManager on first use
```

#### Usage Examples

**Optional Authentication**:
```python
@router.get("/api/v1/data")
async def get_data(
    user: Optional[TokenPayload] = Depends(get_optional_token)
):
    if user:
        # Authenticated request - return user-specific data
        return get_user_data(user.tenant_id)
    else:
        # Anonymous request - return public data
        return get_public_data()
```

**Required Authentication with Device Type**:
```python
@router.post("/api/v1/mobile/sync")
async def mobile_sync(
    user: TokenPayload = Depends(require_mobile_auth)
):
    # Only mobile devices can access this endpoint
    return sync_mobile_data(user.tenant_id, user.device_id)
```

**Tenant Context Extraction**:
```python
@router.get("/api/v1/files/{file_id}")
async def get_file(
    file_id: str,
    tenant_id: str = Depends(get_tenant_id)
):
    # Works with either authenticated token or X-Tenant-ID header
    return get_tenant_file(tenant_id, file_id)
```

#### Security Features

- **Algorithm Validation**: Prevents algorithm confusion attacks
- **Key Rotation Support**: Automatic handling of rotated JWT keys  
- **Timing Attack Protection**: Constant-time signature verification
- **Comprehensive Validation**: Full JWT claim verification (exp, iat, iss, aud)
- **Error Isolation**: Different error messages for debugging vs. security
- **Development Mode**: Additional logging and validation in debug mode

### Note on Endpoint Implementation Status

Based on the authentication flow diagrams, the following endpoints are referenced but not yet listed in the implementation tables:

**From Flow 2 (Desktop Authentication via QR Code):**
- `GET /oauth/device?user_code=XXXX-YYYY` (step 2.9) - Used by mobile to get device details when scanning QR code

**From Flow 4 (Email Verification Flow):**
- `POST /api/v1/auth/email/add` (step 4.2) - Add new email address for verification
- `POST /api/v1/auth/email/verify` (step 4.8) - Verify email with code and signature

These endpoints are fully implemented in the current codebase.

## Security Configuration

### Cryptographic Parameters

```yaml
crypto:
  # Mobile device keys
  device_keys:
    algorithm: Ed25519
    key_size: 256
    
  # Key exchange for sessions
  key_exchange:
    algorithm: X25519
    ephemeral: true
    
  # Session encryption
  encryption:
    algorithm: ChaCha20-Poly1305
    nonce_size: 12
    
  # Key derivation
  kdf:
    algorithm: HKDF-SHA256
    salt: "p8fs-v1"
    info_separator: "-"
    
  # Token signing
  jwt:
    algorithm: ES256  # ECDSA with P-256
    issuer: "https://auth.example.com"
    
# OAuth 2.1 Configuration
oauth:
  # Authorization server metadata
  issuer: "https://auth.p8fs.io"
  authorization_endpoint: "/oauth/authorize"
  token_endpoint: "/oauth/token"
  userinfo_endpoint: "/oauth/userinfo"
  jwks_uri: "/.well-known/jwks.json"
  
  # Supported flows
  grant_types_supported:
    - authorization_code
    - refresh_token
    - urn:ietf:params:oauth:grant-type:device_code
    
  # PKCE requirements
  code_challenge_methods_supported:
    - S256  # SHA256 only, no plain
    
  # Token configuration
  access_token_lifetime: 3600      # 1 hour
  refresh_token_lifetime: 2592000  # 30 days
  device_code_lifetime: 600        # 10 minutes
  
  # Security settings
  require_pkce: true
  exact_redirect_uri: true
  refresh_token_rotation: true
```

### SeaweedFS Webhook Configuration

```toml
[s3]
auth_type = "webhook"

[s3.webhook]
url = "http://p8fs-auth.p8fs.svc.cluster.local:8000/internal/s3/validate"
timeout = "5s"
cache_ttl = "300s"
retry_attempts = 3
retry_delay = "100ms"

# Webhook authentication
[[s3.webhook.headers]]
name = "X-SeaweedFS-Secret"
value = "${SEAWEEDFS_WEBHOOK_SECRET}"

[[s3.webhook.headers]]
name = "X-SeaweedFS-Instance"
value = "${HOSTNAME}"

# Request validation
[s3.webhook.validation]
require_signature = true
allowed_methods = ["GET", "PUT", "POST", "DELETE", "HEAD"]
max_request_size = "5GB"
```

## Tenant Isolation

### Bucket Structure

```
s3://p8fs-storage/
├── tenants/
│   ├── tenant-12345/
│   │   ├── files/
│   │   ├── processed/
│   │   └── metadata/
│   └── tenant-67890/
│       ├── files/
│       ├── processed/
│       └── metadata/
└── system/
    ├── audit/
    └── metrics/
```

### Access Control

1. **Tenant Prefix Enforcement**: All S3 operations restricted to tenant prefix
2. **Webhook Validation**: Every request validated against session
3. **IAM Policies**: Generated dynamically based on tenant permissions
4. **Audit Logging**: All access logged with tenant context

## Implementation Examples

### Mobile Key Generation (Swift)

```swift
import CryptoKit

class AuthenticationManager {
    private var signingKey: Curve25519.Signing.PrivateKey?
    private var encryptionKey: Curve25519.KeyAgreement.PrivateKey?
    
    func generateKeypair() throws {
        // Generate Ed25519 signing key
        signingKey = Curve25519.Signing.PrivateKey()
        
        // Generate X25519 encryption key
        encryptionKey = Curve25519.KeyAgreement.PrivateKey()
        
        // Store in Secure Enclave if available
        try storeInKeychain()
    }
    
    func signChallenge(_ challenge: Data) throws -> Data {
        guard let key = signingKey else {
            throw AuthError.noKeyAvailable
        }
        return try key.signature(for: challenge)
    }
}
```

### S3 Webhook Validation (Rust)

```rust
use hkdf::Hkdf;
use sha2::Sha256;

#[derive(Deserialize)]
struct S3ValidationRequest {
    access_key: String,
    signature: String,
    string_to_sign: String,
    request_method: String,
    request_uri: String,
    request_headers: HashMap<String, String>,
}

async fn validate_s3_request(
    Json(req): Json<S3ValidationRequest>,
    Extension(auth_service): Extension<AuthService>,
) -> Result<Json<S3ValidationResponse>, Error> {
    // Find session by access key pattern
    let session = auth_service
        .find_session_by_access_key(&req.access_key)
        .await?;
    
    // Re-derive credentials
    let credentials = derive_s3_credentials(
        &session.token,
        &session.tenant_id,
        &session.device_id,
    )?;
    
    // Validate signature
    let expected_sig = calculate_aws_v4_signature(
        &req.string_to_sign,
        &credentials.secret_access_key,
        &req.request_headers,
    )?;
    
    if expected_sig != req.signature {
        return Ok(Json(S3ValidationResponse {
            valid: false,
            ..Default::default()
        }));
    }
    
    // Check permissions for requested operation
    let allowed = check_permissions(
        &session.permissions,
        &req.request_method,
        &req.request_uri,
    );
    
    Ok(Json(S3ValidationResponse {
        valid: allowed,
        tenant_id: Some(session.tenant_id),
        bucket_prefix: Some(format!("tenants/{}/", session.tenant_id)),
        permissions: Some(session.permissions),
    }))
}
```

### OAuth 2.1 Token Endpoint (Python)

```python
from fastapi import APIRouter, Form, HTTPException
from typing import Optional
import secrets
import hashlib
from datetime import datetime, timedelta

router = APIRouter()

@router.post("/oauth/token")
async def token_endpoint(
    grant_type: str = Form(...),
    code: Optional[str] = Form(None),
    refresh_token: Optional[str] = Form(None),
    client_id: str = Form(...),
    client_secret: Optional[str] = Form(None),
    code_verifier: Optional[str] = Form(None),
    device_code: Optional[str] = Form(None),
):
    """OAuth 2.1 token endpoint implementation"""
    
    if grant_type == "authorization_code":
        # Validate authorization code
        auth_code = await get_auth_code(code)
        if not auth_code or auth_code.client_id != client_id:
            raise HTTPException(400, "Invalid authorization code")
        
        # Verify PKCE (required in OAuth 2.1)
        if not code_verifier:
            raise HTTPException(400, "code_verifier required")
        
        challenge = hashlib.sha256(code_verifier.encode()).hexdigest()
        if challenge != auth_code.code_challenge:
            raise HTTPException(400, "Invalid code_verifier")
        
        # Generate tokens
        access_token = generate_access_token(auth_code.user_id)
        refresh_token = generate_refresh_token(auth_code.user_id)
        
        # Mark code as used
        await mark_code_used(code)
        
        return {
            "access_token": access_token,
            "token_type": "Bearer",
            "expires_in": 3600,
            "refresh_token": refresh_token,
            "scope": auth_code.scope,
        }
    
    elif grant_type == "refresh_token":
        # Validate refresh token
        token_data = await validate_refresh_token(refresh_token)
        if not token_data:
            raise HTTPException(400, "Invalid refresh token")
        
        # Rotate refresh token (required for public clients)
        new_access = generate_access_token(token_data.user_id)
        new_refresh = generate_refresh_token(token_data.user_id)
        
        # Revoke old refresh token
        await revoke_refresh_token(refresh_token)
        
        return {
            "access_token": new_access,
            "token_type": "Bearer",
            "expires_in": 3600,
            "refresh_token": new_refresh,
        }
    
    elif grant_type == "urn:ietf:params:oauth:grant-type:device_code":
        # Device flow token request
        device_session = await get_device_session(device_code)
        
        if not device_session:
            raise HTTPException(400, "Invalid device code")
        
        if device_session.status == "pending":
            raise HTTPException(400, "authorization_pending")
        
        if device_session.status == "approved":
            # Generate tokens
            access_token = generate_access_token(device_session.user_id)
            refresh_token = generate_refresh_token(device_session.user_id)
            
            # Mark as consumed
            await mark_device_code_used(device_code)
            
            return {
                "access_token": access_token,
                "token_type": "Bearer",
                "expires_in": 3600,
                "refresh_token": refresh_token,
            }
    
    raise HTTPException(400, "Unsupported grant type")
```

## Monitoring and Observability

### Key Metrics

```yaml
# Authentication metrics
auth_metrics:
  - name: auth_requests_total
    type: counter
    labels: [flow_type, status]
    
  - name: auth_latency_seconds
    type: histogram
    labels: [flow_type]
    
  - name: active_sessions_total
    type: gauge
    labels: [tenant_id, device_type]
    
  - name: s3_validations_total
    type: counter
    labels: [status, tenant_id]
    
  - name: token_refreshes_total
    type: counter
    labels: [client_type]
    
  - name: failed_auth_attempts_total
    type: counter
    labels: [reason, client_ip]
```

### Audit Events

```json
{
  "event_type": "auth.device.approved",
  "timestamp": "2024-01-15T10:30:00Z",
  "tenant_id": "tenant-12345",
  "user_id": "user-67890",
  "device_id": "device-abcdef",
  "ip_address": "192.168.1.100",
  "user_agent": "P8-Node/1.0.0",
  "metadata": {
    "device_name": "John's MacBook Pro",
    "auth_method": "qr_code",
    "session_id": "session-123456"
  }
}
```

## Security Best Practices

1. **Rate Limiting**
   - 5 failed auth attempts = 15 minute lockout
   - 10 requests/minute for token endpoints
   - 100 requests/minute for S3 validation webhook

2. **Token Lifetimes**
   - Access tokens: 1 hour
   - Refresh tokens: 30 days (mobile), 7 days (desktop)
   - Device codes: 10 minutes
   - Email verification codes: 15 minutes

3. **Encryption Requirements**
   - All API traffic over TLS 1.3
   - Certificate pinning in mobile apps
   - Encrypted storage for sensitive data
   - Key rotation every 90 days

4. **Audit Requirements**
   - Log all authentication events
   - Log all S3 access with tenant context
   - Retain logs for 90 days minimum
   - Real-time alerting for suspicious activity

## Future Enhancements

1. **WebAuthn Support**: Add FIDO2/passkeys as additional factor
2. **SSO Integration**: SAML/OIDC for enterprise customers
3. **Risk-Based Authentication**: ML-based anomaly detection
4. **Hardware Security Module**: HSM integration for key storage
5. **Biometric Binding**: Bind sessions to biometric data
6. **Zero-Knowledge Proofs**: Enhanced privacy for authentication
7. **Tenant Management API**: Comprehensive tenant administration endpoints
   - `GET /api/v1/tenant/info` - Get tenant information
   - `POST /api/v1/tenant/bucket` - Create new bucket
   - `GET /api/v1/tenant/usage` - Get usage statistics
   - `GET /api/v1/tenant/limits` - Get tenant limits

## Authentication Flow Testing Architecture

### Comprehensive Flow Validation Diagram

The P8-FS authentication system implements six critical flows that work together to provide secure, mobile-first authentication. This diagram illustrates the complete testing architecture and data flows validated during our comprehensive simulation.

```mermaid
graph TB
    subgraph "📱 Mobile Device (Primary Identity)"
        MobileApp[Mobile App]
        PrivateKey[Ed25519 Private Key<br/>🔐 Never Exported]
        Biometric[Biometric Unlock<br/>👆 TouchID/FaceID]
        MobileStorage[Secure Storage<br/>🛡️ Keychain/TrustZone]
    end
    
    subgraph "🖥️ Desktop Device (Secondary)"
        DesktopApp[Desktop App]
        QRDisplay[QR Code Display<br/>📱 Session + PubKey]
        DesktopSession[Session Storage<br/>💾 Encrypted Tokens]
    end
    
    subgraph "🔐 P8-FS Authentication Core"
        AuthAPI[Authentication API<br/>🌐 OAuth 2.1 Server]
        DeviceService[Device Pairing Service<br/>🔄 X25519 Key Exchange]
        CredentialService[Credential Service<br/>🗝️ HKDF Derivation]
        SessionStore[(Session Store<br/>📊 TiKV Distributed)]
    end
    
    subgraph "☁️ Storage & Services"
        SeaweedFS[SeaweedFS S3 Gateway<br/>🪣 Object Storage]
        S3Webhook[S3 Auth Webhook<br/>✅ Credential Validation]
        EmailService[Email Service<br/>✉️ Verification Codes]
    end
    
    subgraph "🧪 Test Flows Validated"
        Flow1[1️⃣ Mobile Registration<br/>📧 Email + Crypto Proof]
        Flow2[2️⃣ Desktop QR Pairing<br/>📱 X25519 Key Exchange]
        Flow3[3️⃣ OAuth PKCE Flow<br/>🔄 Authorization Code + PKCE]
        Flow4[4️⃣ S3 Credential Derivation<br/>🗝️ HKDF Zero-Storage]
        Flow5[5️⃣ Encryption Operations<br/>🔒 ChaCha20-Poly1305 AEAD]
        Flow6[6️⃣ Webhook Validation<br/>✅ Real-time Auth Check]
    end
    
    %% Mobile Registration Flow
    MobileApp -->|1. Register Device| AuthAPI
    AuthAPI -->|2. Send Verification| EmailService
    EmailService -->|3. 6-digit Code| MobileApp
    MobileApp -->|4. Signed Challenge| AuthAPI
    AuthAPI -->|5. Create Tenant| SessionStore
    
    %% Desktop Pairing Flow  
    DesktopApp -->|1. Create QR Session| DeviceService
    DeviceService -->|2. X25519 Ephemeral Key| QRDisplay
    MobileApp -->|3. Scan QR + Mobile PubKey| DeviceService
    DeviceService -->|4. Derive Shared Secret| SessionStore
    MobileApp -->|5. Approve Pairing| AuthAPI
    
    %% OAuth PKCE Flow
    DesktopApp -->|1. Auth Request + PKCE| AuthAPI
    AuthAPI -->|2. Authorization Code| DesktopApp
    DesktopApp -->|3. Code + Verifier| AuthAPI
    AuthAPI -->|4. Access + Refresh Tokens| DesktopApp
    
    %% Credential Derivation
    DesktopApp -->|1. Session ID| CredentialService
    CredentialService -->|2. HKDF: session-tenant-device| CredentialService
    CredentialService -->|3. S3 Access + Secret Key| DesktopApp
    
    %% S3 Access Validation
    DesktopApp -->|1. S3 Request + Credentials| SeaweedFS
    SeaweedFS -->|2. Validate Credentials| S3Webhook
    S3Webhook -->|3. Re-derive + Compare| CredentialService
    S3Webhook -->|4. Allow/Deny| SeaweedFS
    
    %% Encryption Testing
    MobileApp -->|Encrypt with ChaCha20-Poly1305| Flow5
    DesktopApp -->|Decrypt with Shared Secret| Flow5
    
    %% Security Properties Tested
    PrivateKey -.->|Never Leaves Device| MobileStorage
    Biometric -.->|Controls Key Usage| PrivateKey
    QRDisplay -.->|Ephemeral - 10min TTL| DeviceService
    CredentialService -.->|Zero Storage Principle| SessionStore
    
    %% Styling
    classDef mobile fill:#e1f5fe,stroke:#0277bd,stroke-width:2px
    classDef desktop fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef server fill:#e8f5e8,stroke:#2e7d32,stroke-width:2px
    classDef storage fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef flows fill:#f1f8e9,stroke:#558b2f,stroke-width:2px
    
    class MobileApp,PrivateKey,Biometric,MobileStorage mobile
    class DesktopApp,QRDisplay,DesktopSession desktop
    class AuthAPI,DeviceService,CredentialService server
    class SessionStore,SeaweedFS,S3Webhook,EmailService storage
    class Flow1,Flow2,Flow3,Flow4,Flow5,Flow6 flows
```

### Security Properties Validated

| Flow | Security Validation | Cryptographic Proof |
|------|-------------------|---------------------|
| **Mobile Registration** | Email ownership + Ed25519 signature | Private key proves device possession |
| **Desktop QR Pairing** | X25519 key exchange + ephemeral keys | Perfect forward secrecy via temporary keys |
| **OAuth PKCE Flow** | CSRF protection + code injection prevention | SHA256 PKCE challenge/verifier binding |
| **S3 Credential Derivation** | Zero-credential storage principle | HKDF deterministic key derivation |
| **Encryption Operations** | AEAD authenticated encryption | ChaCha20-Poly1305 confidentiality + integrity |
| **Webhook Validation** | Real-time credential verification | Re-derivation and constant-time comparison |

### Testing Methodology

1. **End-to-End Validation**: Each flow tested from user action to storage access
2. **Cryptographic Verification**: All signatures, key exchanges, and derivations validated
3. **Error Handling**: Negative test cases for invalid inputs and edge conditions
4. **Performance Benchmarking**: Timing analysis for all cryptographic operations
5. **Interoperability Testing**: Cross-platform compatibility (mobile ↔ desktop ↔ server)
6. **Security Boundary Testing**: Tenant isolation and access control verification

### Zero-Trust Validation

- ✅ **Never Trust**: Every request cryptographically verified
- ✅ **Always Verify**: Continuous validation of device identity and session state
- ✅ **Least Privilege**: Scope-based authorization with tenant isolation
- ✅ **Assume Breach**: Ephemeral keys and perfect forward secrecy
- ✅ **Verify Explicitly**: Multi-factor proof (email + cryptographic + biometric)

## Simulation Results and Observations

### Running the Authentication Simulation

The P8-FS authentication system includes a comprehensive simulation that demonstrates all authentication flows. To run the simulation:

```bash
cd /path/to/p8-fs

# Option 1: Run comprehensive integration tests
uv run pytest tests/integration/auth -v

# Option 2: Interactive CLI testing
uv run python tests/integration/auth/interactive_auth_test.py

# Option 3: OAuth flow demonstration
uv run python working_oauth_demo.py

# Option 4: Run auth simulation (comprehensive)
uv run python examples/run_auth_simulation.py
```

### Simulation Output

The simulation produces rich console output with visual indicators for each authentication stage:

#### 1. Mobile Registration Flow
```
📱 Mobile Registration Flow
============================================================
╭─────────────────────────── 🔐 Mobile Device Keys ────────────────────────────╮
│ ✓ Ed25519 Keypair Generated                                                  │
│                                                                              │
│ Public Key: 0abUB/+HUJiPiWpaLbzMs28WSaz1qi6n...                              │
│ Key Size: 256 bits                                                           │
│ Algorithm: EdDSA (Ed25519)                                                   │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- Ed25519 keypair generation is instantaneous (<1ms)
- Public key is base64 encoded for transmission (44 characters)
- Private key never leaves the simulated mobile device
- Email verification completes with 6-digit code

#### 2. Desktop QR Code Pairing
```
╭─────────────────────────── 🤝 ECDH Key Agreement ────────────────────────────╮
│ 🔐 Key Exchange (X25519)                                                     │
│ ├── Desktop Public: gpHJb4iHDOtmoAo/t0lCxZTjk21AZ7iS...                      │
│ ├── Mobile Public: GIdCvKLEYo8zM8AlHeaSGUpTSGlAs8ii...                       │
│ └── Shared Secret: aFza3mxw3DeRzTAAj8JL/TtQYYc8nNLG...                       │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- X25519 ECDH produces 32-byte shared secret
- QR code contains ephemeral public key and session info
- Device approval encrypts metadata with ChaCha20-Poly1305
- Perfect forward secrecy achieved with ephemeral keys

#### 3. OAuth 2.1 PKCE Flow
```
╭─────────────────────── 🔒 Proof Key for Code Exchange ───────────────────────╮
│ PKCE Parameters Generated                                                    │
│                                                                              │
│ Code Verifier: 36Ubftz4DABeBrg-22GIOSvOX1pfy3mv...                           │
│ Challenge Method: S256 (SHA-256)                                             │
│ Code Challenge: qW4FnjRXInfGxrQPcve-buf5wz6Wqhr2-Ov8MUHBc9c                  │
│ Length: 43 characters                                                        │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- Code verifier is 43 characters (32 bytes base64url encoded)
- Only S256 challenge method supported (no plain text)
- Authorization code flow completes in <100ms
- Tokens include tenant context for multi-tenancy

#### 4. S3 Credential Derivation
```
╭─────────────────────── 🗄️ AWS S3 Compatible Credentials ─────────────────────╮
│ S3 Credentials Generated                                                     │
│                                                                              │
│ Access Key ID: P8FSXRJ6KQGCQHZ6O                                              │
│ Secret Access Key: qHVq3EwTfqY7cFol************                               │
│ Session Token: p8fs_st_a1b2c3d4e5f6g7h8...                                    │
│ Bucket: p8fs-tenant_a1b2c3d4                                                  │
│ Region: us-east-1                                                            │
│ Endpoint: https://s3.example.com                                             │
│ Expires: 2025-07-19 21:31:39 UTC                                             │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- HKDF derives 60 bytes of key material
- Access key follows AWS format (P8FS prefix + 15 chars)
- Credentials are deterministic from session + tenant + device
- 12-hour expiration for derived credentials

#### 5. Encryption/Decryption Operations
```
╭──────────────────────── ✍️ Digital Signature (EdDSA) ─────────────────────────╮
│ Ed25519 Digital Signature                                                    │
│                                                                              │
│ Message: Authorization: Bearer p8fs_at_eyJ0ZW5hbnRfaWQiOi.....               │
│ Signature: AZIhnlb1tXfI9/ljUJI2ImKdKCr7LUUyHYbocFk/XIg3u++z...              │
│ Verification: ✓ Valid                                                        │
│ Signature Size: 64 bytes                                                     │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- ChaCha20-Poly1305 AEAD encryption with 96-bit nonce
- Ed25519 signatures are always 64 bytes
- Authentication tags prevent tampering
- All cryptographic operations complete in <5ms

#### 6. SeaweedFS Webhook Validation
```
╭────────────────────────── 🎯 S3 Access Validation ───────────────────────────╮
│ 🔍 Webhook Validation Process                                                │
│ ├── Access Key: P8FSABCDEF123456                                             │
│ ├── Steps:                                                                   │
│ ├── 1️⃣ Extract tenant from access key                                         │
│ │   ├── 2️⃣ Re-derive secret key using HKDF                                    │
│ │   ├── 3️⃣ Verify AWS Signature V4                                            │
│ │   ├── 4️⃣ Check tenant permissions                                           │
│ │   └── 5️⃣ Validate bucket access                                             │
│ └── ✓ Validation Result: APPROVED                                            │
╰──────────────────────────────────────────────────────────────────────────────╯
```

**Observations:**
- Zero credential storage in SeaweedFS
- Validation completes in <50ms (suitable for webhook timeout)
- Tenant isolation enforced at bucket prefix level
- Caching reduces repeated validations

### Performance Metrics

Based on the simulation runs:

| Operation | Average Time | Notes |
|-----------|-------------|-------|
| Ed25519 Keypair Generation | <1ms | Hardware accelerated where available |
| X25519 Key Exchange | <2ms | Includes public key serialization |
| PKCE Challenge Generation | <1ms | SHA-256 hashing |
| Token Generation | <5ms | JWT creation with ES256 |
| S3 Credential Derivation | <3ms | HKDF-SHA256 |
| Webhook Validation | <50ms | Includes key derivation + signature check |
| ChaCha20-Poly1305 Encryption | <5ms | For typical metadata payload |
| Ed25519 Signature | <2ms | Constant time operation |

### Security Validation

The simulation confirms:

1. **No Secrets in Transit**: Only public keys and encrypted data transmitted
2. **Perfect Forward Secrecy**: Each session uses unique ephemeral keys
3. **Tenant Isolation**: Complete separation at storage and API levels
4. **OAuth 2.1 Compliance**: PKCE mandatory, no deprecated flows
5. **Cryptographic Best Practices**: Modern algorithms, proper key sizes
6. **Defense in Depth**: Multiple layers of authentication and authorization

### Implementation Status

| Component | Status | Test Coverage |
|-----------|--------|---------------|
| OAuth 2.1 Server | ✅ Implemented | Simulated |
| Mobile Auth Flow | ✅ Implemented | Simulated |
| Desktop QR Pairing | ✅ Implemented | Simulated |
| S3 Credential Derivation | ✅ Implemented | Simulated |
| SeaweedFS Webhook | ✅ Implemented | Simulated |
| Encryption Layer | ✅ Implemented | Simulated |

### Future Enhancements Based on Simulation

1. **Performance Optimizations**
   - Pre-compute HKDF expansion for frequently accessed tenants
   - Implement webhook response caching in SeaweedFS
   - Batch validation requests for high-throughput scenarios

2. **Security Hardening**
   - Add rate limiting to prevent timing attacks
   - Implement key rotation reminders
   - Add anomaly detection for unusual access patterns

3. **User Experience**
   - Reduce QR code density with compression
   - Add progress indicators for multi-step flows
   - Implement retry logic for network failures

## Development and Testing

### Authentication Flow Simulation

For development and testing purposes, P8-FS provides tools to simulate the mobile device authentication flow without requiring a real mobile app.

#### Overview

The authentication simulation tools allow developers to:
- Simulate mobile device registration and verification
- Create pre-configured test devices for faster testing
- Approve desktop authentication requests programmatically
- Test the complete authentication flow end-to-end

#### Quick Start

##### Option 1: Pre-configured Test Device (Recommended for Development)

If your dev server has TiKV access, you can create a pre-configured test device:

```bash
# From the P8-FS root directory
# Register a test device and save keys for testing
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run python setup_device_for_testing.py

# This creates test_device_keys.json with device credentials
# Set the private key for environment-based testing
export P8FS_DEVICE_KEY="<private_key_from_registration_output>"
```

Then approve desktop authentication:

```bash
# Test complete flow with working OAuth demo
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run python working_oauth_demo.py

# Or use the interactive test suite
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run python tests/integration/auth/interactive_auth_test.py
```

##### Option 2: Full Registration Flow

Simulate the complete mobile registration process:

```bash
# Run all authentication integration tests
uv run pytest tests/integration/auth -v

# Run specific test categories
uv run pytest tests/integration/auth/test_mobile_flow.py -v
uv run pytest tests/integration/auth/test_oauth_direct.py -v

# Run against production deployment
P8FS_AUTH_SERVER=https://p8fs.percolationlabs.ai uv run pytest tests/integration/auth -v
```

##### Option 3: Interactive Test Script

For guided testing with real QR codes:

```bash
# Interactive CLI with menu options
uv run python tests/integration/auth/interactive_auth_test.py

# Available options:
# 1. Register Test Mobile Device
# 2. Start Desktop Authentication 
# 3. Simulate Mobile Approval
# 4. Test S3 Credential Derivation
# 5. Check Server Status
```

#### How It Works

##### Mobile Device Simulation

The `simulate_mobile_device.py` script simulates a mobile device by:

1. **Generating Ed25519 Keypairs**: Creates signing keys just like a real mobile app
2. **Storing Keys Securely**: Saves keys to `mobile_device_keys.json` or `test_device_keys.json`
3. **Signing Challenges**: Uses the private key to sign authentication challenges
4. **Managing Sessions**: Stores access and refresh tokens

##### Desktop Authentication Flow

1. **Desktop Initiates**: P8-Node calls `/oauth/device/code` to start device flow
2. **QR Code Display**: Shows QR code with verification URL
3. **Mobile Approval**: Mobile device (simulator) approves with signature
4. **Token Exchange**: Desktop polls and receives OAuth tokens
5. **S3 Credentials**: Tokens used to derive S3 access keys

##### Test Device Setup

The `setup_test_device.py` script creates pre-authenticated devices by:

1. **Creating Tenant**: Sets up tenant in TiKV with email mapping
2. **Storing Device**: Saves device info with public key
3. **Pre-creating Session**: Generates long-lived test session
4. **Generating Tokens**: Creates access/refresh tokens for immediate use

#### File Formats

##### mobile_device_keys.json / test_device_keys.json

```json
{
  "private_key": "base64_encoded_ed25519_private_key",
  "public_key": "base64_encoded_ed25519_public_key",
  "access_token": "p8fs_at_...",
  "refresh_token": "p8fs_rt_...",
  "tenant_id": "tenant_12345678",
  "device_id": "device_abcdef123456"
}
```

#### Troubleshooting

##### "Cannot connect to auth server"

Make sure the P8-FS dev server is running:
```bash
./ps run-api
```

##### "Invalid or expired user code"

The QR code expires after 10 minutes. Generate a new one in P8-Node.

##### "Not authenticated"

For Option 2, make sure you've completed registration and verification first.

##### "No test device found"

For Option 1, ensure TiKV is running and accessible from your dev environment.

#### Security Notes

- **Development Only**: These scripts are for development/testing only
- **Keys Are Sensitive**: The private keys in JSON files should never be committed
- **Test Isolation**: Use separate test emails to avoid conflicts

#### Advanced Usage

##### Custom Device Names

```bash
python scripts/simulate_mobile_device.py register --email dev@test.com
python scripts/setup_test_device.py create --email dev@test.com --name "Dev iPhone"
```

##### Multiple Test Devices

Create multiple devices for testing multi-device scenarios:

```bash
python scripts/setup_test_device.py create --email device1@test.com --save-to device1.json
python scripts/setup_test_device.py create --email device2@test.com --save-to device2.json
```

##### Cleanup

Remove test devices:

```bash
python scripts/setup_test_device.py cleanup --email test@example.com
```

#### Integration with P8-Node

When P8-Node shows the authentication screen:

1. Click "Authenticate" in the system tray menu
2. A QR code appears with a verification URL
3. Copy the URL (it includes the user_code parameter)
4. Run the approve command with that URL
5. P8-Node will receive tokens and can access S3

#### Next Steps

- The authenticated P8-Node can now sync files to S3
- S3 credentials are automatically derived from the session
- File processing will include tenant context for isolation

## Notes on Authentication Challenge Implementation

### Challenge-Response Authentication in /api/v1/auth/verify

The authentication system uses a cryptographic challenge-response mechanism during the email verification process to ensure that only the legitimate device holder can complete registration. This provides an additional layer of security beyond the email verification code.

#### How the Challenge Works

1. **During Registration** (`/api/v1/auth/register`):
   - Mobile device generates an Ed25519 keypair locally
   - Sends only the public key to the server (private key never leaves device)
   - Server stores the public key and sends a 6-digit verification code via email

2. **During Verification** (`/api/v1/auth/verify`):
   - User enters the 6-digit code received via email
   - Mobile app creates a challenge (typically a timestamp or random data)
   - Mobile app signs the challenge with its Ed25519 private key
   - Sends to server: email, code, challenge, and signature (as separate fields)

3. **Server Validation**:
   - Verifies the 6-digit code matches what was sent
   - Uses the stored public key from registration to verify the signature
   - Ensures the signature matches the challenge using Ed25519 verification
   - Only succeeds if the signature was created by the corresponding private key

#### Challenge vs Signature Clarification

The challenge and signature are two distinct components:

**The Challenge**:
- Generated by the client (mobile app), not the server
- Can be any unique data: timestamp, random bytes, or structured string
- Examples: `"2024-01-15T10:30:00Z"` or `base64(random_32_bytes)` or `"VERIFY:user123:timestamp"`
- Purpose: Provides unique data to sign, preventing replay attacks

**The Signature**:
- The cryptographic signature of the challenge
- Created by signing the challenge with the Ed25519 private key
- Example: `"AZIhnlb1tXfI9/ljUJI2ImKdKCr7LUUyHYbocFk..."` (base64 encoded)
- Purpose: Proves possession of the private key

**The Process**:
```python
# 1. Client creates a challenge (any unique data)
challenge = "2024-01-15T10:30:00Z"  # or secrets.token_urlsafe(16)

# 2. Client signs the challenge with private key
signature = private_key.sign(challenge.encode())
signature_b64 = base64.b64encode(signature).decode()

# 3. Client sends both to server
{
  "email": "user@example.com",
  "code": "123456",
  "challenge": "2024-01-15T10:30:00Z",        # The original data
  "signature": "AZIhnlb1tXfI9/ljUJI2ImKd..."  # The signature of that data
}

# 4. Server verifies: public_key.verify(signature, challenge)
```

The server doesn't generate or dictate the challenge content - it only verifies that the provided signature matches the provided challenge using the stored public key. This design is stateless and flexible while maintaining strong security.

#### Security Benefits

- **Proof of Device Possession**: Cryptographically proves the request comes from the device that registered
- **Replay Attack Prevention**: Each challenge is unique, preventing reuse of old signatures
- **Man-in-the-Middle Protection**: Even if someone intercepts the 6-digit email code, they cannot complete verification without the private key
- **Zero-Knowledge Property**: The server never sees or stores the private key

#### Implementation Details

The actual implementation uses separate `challenge` and `signature` fields in the request:

```json
{
  "email": "user@example.com",
  "code": "123456",
  "challenge": "unique_challenge_data",
  "signature": "base64_encoded_ed25519_signature"
}
```

Note: The documentation may show `base64_signed_challenge` but the actual implementation correctly uses two separate fields for the challenge and its signature.

#### Cryptographic Verification

The signature verification uses Ed25519, which provides:

- Strong security with small key sizes (32 bytes)
- Fast signature generation and verification
- Deterministic signatures (same input always produces same signature)
- Protection against various cryptographic attacks

This challenge-response mechanism is a crucial component of the mobile-first authentication architecture, ensuring that possession of an email account alone is not sufficient to compromise an account - the attacker would also need access to the device's private key.

## References

- [OAuth 2.1 Draft Specification](https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/)
- [RFC 8628: OAuth 2.0 Device Authorization Grant](https://datatracker.ietf.org/doc/html/rfc8628)
- [RFC 7636: Proof Key for Code Exchange (PKCE)](https://datatracker.ietf.org/doc/html/rfc7636)
- [RFC 9449: OAuth 2.0 DPoP](https://datatracker.ietf.org/doc/html/rfc9449)
- [NIST SP 800-63B: Digital Identity Guidelines](https://pages.nist.gov/800-63-3/sp800-63b.html)
- [SeaweedFS S3 API Documentation](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API)

## Implementation Notes

### TiKV JWT Public Key Storage

The P8-FS authentication system uses **asymmetric cryptography** for JWT tokens. JWT tokens are **signed by the server** using ES256 (ECDSA with P-256) private keys and **verified by clients and middleware** using a **system-wide public key** stored in TiKV.

**JWT Token Flow**:
1. **Server Signs**: Authentication service signs JWTs with ES256 private key (`src/p8fs/utils/tokens.py`)
2. **Client Receives**: Clients get signed JWT tokens (format: `p8fs_at_{jwt}`)
3. **Client Sends**: Clients include JWT in `Authorization: Bearer` headers
4. **Server Verifies**: Middleware verifies JWT signature using the **system-wide public key** from TiKV

Understanding the key storage structure is essential for JWT validation and debugging authentication issues.

#### Key Storage Schema

**Tenant Data Key Pattern**: `tenant:{tenant_id}`

Tenant metadata is stored using this key pattern:
- `tenant:p8ptest` - Test tenant for development
- `tenant:tenant_u2A6RmFi_1c` - Production tenant example
- `tenant:{any_tenant_id}` - General pattern

**Note**: Tenant records store email mappings and metadata but **NOT JWT keys**. JWT verification uses the system-wide key.

**System JWT Key**: `system:jwt:signing_key`

The master JWT signing key pair used by the authentication service:

```json
{
  "key_id": "system:jwt:signing_key",
  "algorithm": "ES256",
  "private_key_pem": "-----BEGIN PRIVATE KEY-----\n...",
  "public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "created_at": "2025-08-03T09:52:20.852316",
  "description": "System JWT signing key for ES256"
}
```

#### Tenant Record Structure

Each tenant record contains metadata and email mappings:

```json
{
  "tenant_id": "p8ptest",
  "primary_email": "testing@percolationlabs.ai", 
  "emails": ["testing@percolationlabs.ai"],
  "created_at": "2025-08-03T09:52:20.852316",
  "updated_at": "2025-08-03T09:52:20.852316"
}
```

**Note**: The tenant record does **NOT** contain JWT keys. All JWT operations use the system-wide key.

#### JWT Verification Process

**Critical Security Flow**: JWT tokens sent by clients are **cryptographically signed** using ES256 (ECDSA with P-256) and **must be verified** using the **system-wide public key** stored in TiKV.

The authentication middleware performs JWT verification as follows:

1. **Parse JWT Header**: Check algorithm (ES256 vs HS256) without verification
2. **Extract Tenant ID**: Decode JWT payload to get `tenant_id` (unverified)
3. **Retrieve System Key**: Query TiKV for `system:jwt:signing_key` or use environment variable
4. **Verify Signature**: Use system-wide `public_key_pem` to cryptographically validate JWT

```python
# Code reference: src/p8fs/api/dependencies/auth.py:60-142
# Step 1: Inspect token without verification
unverified_header = jwt.get_unverified_header(jwt_token)
unverified_payload = jwt.get_unverified_claims(jwt_token)

# Step 2: Check if this is an ES256 token (requires public key verification)
if unverified_header.get("alg") == "ES256":
    # Step 3: Retrieve SYSTEM JWT signing key (NOT tenant-specific)
    # First check environment variable (K8s secret)
    jwt_public_key_pem = self.settings.jwt_public_key_pem
    
    if jwt_public_key_pem:
        public_key_pem = jwt_public_key_pem
    else:
        # Fall back to TiKV system key
        system_key_data = await self.tikv.get("system:jwt:signing_key", "system")
        public_key_pem = system_key_data.get("public_key_pem")
    
    if public_key_pem:
        # Step 4: Cryptographically verify JWT signature
        verified_payload = jwt.decode(
            jwt_token,
            public_key_pem,                    # SYSTEM ES256 public key
            algorithms=["ES256"],              # Only allow ES256
            audience=unverified_payload.get("aud"),
            issuer=unverified_payload.get("iss")
        )
```

**Security Properties**:
- **Asymmetric Cryptography**: Private key signs JWTs, public key verifies them
- **Centralized Key Management**: Single system-wide JWT key pair for all tenants
- **Tamper Protection**: Invalid signatures are immediately rejected
- **Algorithm Enforcement**: Only ES256 tokens accepted (no algorithm confusion attacks)
- **Tenant Isolation**: Achieved through JWT claims, not separate keys

**Code Implementation Details**:
- **File**: `src/p8fs/api/dependencies/auth.py` (lines 60-142)
- **Entry Point**: `require_auth()` dependency function
- **Token Format**: Clients send `Authorization: Bearer p8fs_at_{jwt_token}`
- **Verification**: `jwt.decode()` with tenant's public key from TiKV
- **Fallback**: Development mode uses unverified tokens for testing

#### Key Management During Registration

**Mobile Device Registration Flow** (`src/p8fs/services/auth/mobile_service.py:289-303`):

1. **Device Keys**: Mobile device generates Ed25519 keypair for device authentication
2. **JWT Keys**: Server generates ES256 keypair for JWT signing/verification 
3. **Key Storage**: During tenant creation, server stores ES256 public key in tenant record
4. **Dual Purpose Keys**:
   - **Ed25519**: Device authentication challenges and mobile app signing (device-specific)
   - **ES256**: JWT token signing and verification (tenant-wide, shared across devices)

```python
# Server generates JWT signing key pair
jwt_public_key_pem = self.tokens.public_pem.decode('utf-8')  # ES256 public key

# Store in tenant record for JWT verification
tenant_data = {
    "tenant_id": tenant_id,
    "primary_email": email,
    "public_key_pem": jwt_public_key_pem,  # <-- Used to verify JWT tokens
    "public_key_algorithm": "ES256",
    "issuer": self.tokens.issuer
}
```

**System JWT Key Creation** (`src/p8fs/services/auth/jwt_key_manager.py`):

The `JWTKeyManager` ensures persistent, consistent JWT signing keys:
- Checks for existing system key at startup
- Generates new ES256 keypair if none exists
- Stores both private and public keys in TiKV
- Provides public key to tenants during registration

#### Debugging JWT Issues

**Common Issues and Solutions**:

1. **"No public key found for tenant"**
   - Check if tenant exists: `kubectl exec -it tikv-pod -- tikv-ctl get tenant:{tenant_id}`
   - Verify `public_key_pem` field is present in tenant data

2. **"JWT signature verification failed"**
   - Ensure tenant's public key matches the private key used for signing
   - Check if JWT was issued before service restart (old key used)

3. **"Tenant not found"**
   - Verify tenant_id in JWT payload matches stored tenant
   - Check if registration completed successfully

**Useful TiKV Queries**:

```bash
# Check if tenant exists
tikv-ctl get "tenant:p8ptest"

# Check system JWT key
tikv-ctl get "system:jwt:signing_key"

# Scan all tenants
tikv-ctl scan --from "tenant:" --to "tenant:\xff" --limit 10
```

#### Key Rotation Considerations

- **System Key Rotation**: Invalidates all existing JWTs
- **Tenant Key Updates**: Should only occur during security incidents
- **Backward Compatibility**: Consider grace periods for key transitions
- **Monitoring**: Track JWT verification failures after key changes

This implementation ensures that JWT tokens can be verified efficiently using tenant-specific public keys while maintaining the security and isolation guarantees of the P8-FS authentication system.

## OAuth 2.0 Device Authorization Grant Flow Examples

P8-FS implements the complete OAuth 2.0 Device Authorization Grant flow (RFC 8628) with device metadata support for improved user experience during mobile-desktop pairing.

### Complete Flow Example

#### 1. Desktop Initiates Authorization (with Device Metadata)

```bash
POST /oauth/device/code
Content-Type: application/x-www-form-urlencoded

client_id=p8-node-desktop
&scope=read write sync
&device_name=John's MacBook Pro
&device_type=desktop
&platform=macOS
```

**Response:**
```json
{
  "device_code": "dev_SEHKTRlNTI-2Wf7q...",
  "user_code": "0191-1405",
  "verification_uri": "https://p8fs.percolationlabs.ai/oauth/device",
  "verification_uri_complete": "https://p8fs.percolationlabs.ai/oauth/device?user_code=0191-1405",
  "expires_in": 600,
  "interval": 5
}
```

#### 2. QR Code Content

Desktop displays QR code containing the verification URI:
```
https://p8fs.percolationlabs.ai/oauth/device?user_code=0191-1405
```

#### 3. Mobile Scans QR and Gets Device Details

```bash
GET /oauth/device?user_code=0191-1405
Authorization: Bearer {mobile_jwt_token}
```

**Response (with device metadata):**
```json
{
  "message": "Device verification request received",
  "user_code": "0191-1405",
  "instructions": "Approve this device from your P8-FS mobile app",
  "status": "pending",
  "client_id": "p8-node-desktop",
  "scope": "read write sync",
  "device_name": "John's MacBook Pro",
  "device_type": "desktop",
  "platform": "macOS",
  "requested_at": "2025-08-17T07:26:14.292461",
  "expires_at": "2025-08-17T07:36:14.292466"
}
```

#### 4. Mobile Approves Device

**Option A: JWT Token + Ed25519 Signature (Primary Flow)**
```bash
POST /oauth/device/approve
Authorization: Bearer {mobile_jwt_token}
Content-Type: application/x-www-form-urlencoded

user_code=0191-1405
&signature={ed25519_signature_of_device_code}
&encrypted_metadata={optional_session_data}
```

**Option B: Temporary API Key (Testing/Development)**
```bash
# First generate API key
POST /oauth/device/api-key
Authorization: Bearer {mobile_jwt_token}
Content-Type: application/x-www-form-urlencoded

user_code=0191-1405
```

```bash
# Then use API key for approval
POST /oauth/device/approve
Content-Type: application/x-www-form-urlencoded

user_code=0191-1405
&api_key={temporary_api_key}
```

#### 5. Desktop Polls for Token

```bash
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:device_code
&device_code=dev_SEHKTRlNTI-2Wf7q...
&client_id=p8-node-desktop
```

**Success Response:**
```json
{
  "access_token": "p8fs_at_...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "p8fs_rt_...",
  "scope": "read write sync",
  "tenant_id": "tenant_from_mobile_device"
}
```

### User Experience Improvements

With device metadata support, mobile users see meaningful device information instead of generic messages:

**Before (generic):**
- "Approve this device from your P8-FS mobile app"
- No context about what device is requesting access

**After (with metadata):**
- "John's MacBook Pro (desktop, macOS) is requesting access"
- Clear identification of the requesting device
- Platform and device type for additional context

### Key OAuth Endpoints

| Endpoint | Method | Purpose | Device Metadata |
|----------|--------|---------|-----------------|
| `/oauth/device/code` | POST | Desktop requests device code | ✅ Accepts device_name, device_type, platform |
| `/oauth/device` | GET | Mobile gets device details | ✅ Returns stored metadata |
| `/oauth/device/approve` | POST | Mobile approves device | ✅ Transfers mobile identity |
| `/oauth/token` | POST | Desktop polls for token | ❌ Standard OAuth flow |
| `/oauth/device/api-key` | POST | Generate temp API key | ❌ Testing/development only |

### Testing the Flow

```bash
# 1. Start port forwarding to test locally
kubectl port-forward -n p8fs svc/p8fs-api 8000:8000 &

# 2. Test device authorization with metadata
curl -X POST http://localhost:8000/oauth/device/code \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=p8-node-desktop&device_name=Test MacBook&device_type=desktop&platform=macOS"

# 3. Test device verification (use user_code from step 2)
curl "http://localhost:8000/oauth/device?user_code=XXXX-YYYY"

# 4. Verify device metadata is returned in response
```

This enhanced device flow provides a more user-friendly experience while maintaining the security guarantees of the OAuth 2.1 specification.