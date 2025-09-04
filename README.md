# P8FS Native Client

A native client library for connecting to the P8FS distributed file system.

## Overview

P8FS is a distributed file system with mobile-first authentication, chat completions over files, device management, S3 storage, and settings management. This client provides the necessary tools to integrate with P8FS services.


Some env vars see .env.example illustrate some settings the client might e.g

- `P8_FS_API` - P8FS API endpoint (default: <https://p8fs.percolationlabs.ai>)
- `P8FS_S3_URI` - S3 storage endpoint (default: <https://s3.percolationlabs.ai>)

## Core Functionality

### 1. Authentication

P8FS uses a mobile-first OAuth 2.1 authentication system:

- **Mobile Registration**: Primary authentication via mobile device with Ed25519 keys
- **Desktop Pairing**: QR code-based device approval using X25519 key exchange
- **OAuth 2.1 Compliance**: PKCE-protected authorization flows
- **JWT Tokens**: ES256-signed tokens for API access

### 2. Chat Completions

OpenAI-compatible chat completion API with agent routing:

- **Standard Completions**: `/api/v1/chat/completions`
- **Agent-Specific**: `/api/v1/agent/{agent_key}/chat/completions`
- **Streaming Support**: Server-sent events for real-time responses
- **Function Calling**: Agent-based tool integration

_An example agent is the simulated one used in the scripts `p8-sim` which generates markdown SSE events in the Open AI streaming format but without calling an LLM.

### 3. Device Management

Secure device registration and approval flows:

- **Device Registration**: Ed25519 keypair generation and email verification
- **QR Code Pairing**: Secure desktop-mobile device linking
- **Session Management**: Credential derivation using HKDF
- **Device Approval**: Cryptographic proof of device ownership

### 4. File Storage

S3-compatible distributed storage with tenant isolation:

- **Secure Upload**: Tenant-isolated file storage
- **S3 Compatibility**: Standard S3 API endpoints
- **Credential Derivation**: Zero-storage credential system
- **Webhook Validation**: Real-time access validation

### 5. Settings Management

User and tenant configuration management:

- **User Preferences**: Per-user settings storage
- **Tenant Configuration**: Organization-level settings
- **Device Settings**: Device-specific configuration
- **Sync Support**: Multi-device settings synchronization

## Authentication Flow

### Mobile Device Setup

1. Install mobile app and generate Ed25519 keypair
2. Register with email address: `POST /api/v1/auth/register`
3. Verify email code: `POST /api/v1/auth/verify`
4. Receive OAuth tokens for API access

### Desktop Authentication

1. Request device code: `POST /oauth/device/code`
2. Display QR code with verification URL
3. Scan QR code with mobile app
4. Mobile app approves device: `POST /oauth/device/approve`
5. Poll for tokens: `POST /oauth/token`

### S3 Access

1. Get S3 credentials: `GET /api/v1/credentials/s3`
2. Use derived AWS-compatible credentials
3. Access tenant-isolated storage buckets

## API Endpoints

### Authentication

- `POST /api/v1/auth/register` - Mobile device registration
- `POST /api/v1/auth/verify` - Email verification
- `POST /oauth/device/code` - Desktop device authorization
- `POST /oauth/device/approve` - Mobile device approval
- `POST /oauth/token` - Token exchange

### Chat Completions

- `POST /api/v1/chat/completions` - Standard chat completion
- `POST /api/v1/agent/{agent}/chat/completions` - Agent-specific completion

### Storage

- `GET /api/v1/credentials/s3` - Get S3 credentials
- S3 API endpoints at P8FS_S3_URI

### Management

- Device management endpoints
- Settings management endpoints
- Tenant administration endpoints

## Security Features

- **Mobile-First Security**: Ed25519 cryptographic authentication
- **Zero-Trust Architecture**: Continuous credential verification
- **Tenant Isolation**: Complete data separation
- **Perfect Forward Secrecy**: Ephemeral key exchange
- **OAuth 2.1 Compliance**: Modern authentication standards

## Content Headers

The API supports rich content headers for user context, device information, and content classification. Key headers include:

- `Authorization: Bearer {token}` - API authentication
- `X-Tenant-ID` - Tenant context
- `X-Device-Type` - Device type (mobile/desktop)
- `X-Content-Tags` - Content classification
- `X-User-Email` - User identification

See `docs/p8fs/content_headers.md` for complete header reference.

## Documentation

### API References
- `docs/p8fs/authentication.md` - Complete authentication architecture
- `docs/p8fs/chat.md` - Chat completions API reference  
- `docs/p8fs/content_headers.md` - HTTP header specifications

### Implementation Examples
- `docs/pseudo-code/README.md` - Mobile app flow explanations with Mermaid diagrams
- `docs/pseudo-code/device-registration/` - Device setup with Ed25519 keypairs
- `docs/pseudo-code/device-approval/` - QR code scanning and device approval
- `docs/pseudo-code/s3-uploads/` - Secure file uploads with credential derivation
- `docs/pseudo-code/sse-chat-parsing/` - Real-time chat completions over SSE

## Getting Started

1. **Install Dependencies**
   ```bash
   pip install -r requirements.txt
   ```

2. **Environment Setup**
   ```bash
   cp .env.example .env
   # Edit .env with your P8FS endpoints
   ```

3. **Review Implementation Examples**  
   - Start with `docs/pseudo-code/README.md` for flow overviews
   - Study Kotlin/Swift implementations for your target platform
   - Follow the Mermaid diagrams to understand message flows

4. **Implement Core Flows**
   - **Device Registration**: Ed25519 keypair generation and email verification
   - **Device Approval**: QR code scanning for desktop client approval  
   - **S3 Uploads**: Secure file uploads with credential derivation
   - **SSE Chat**: Real-time chat completions with session management

5. **Security Integration**
   - Use platform secure storage (Android Keystore, iOS Keychain)
   - Implement proper OAuth token refresh and credential derivation
   - Ensure tenant isolation for secure multi-user operations

See the pseudo-code implementations for detailed security considerations and platform-specific implementation notes.
