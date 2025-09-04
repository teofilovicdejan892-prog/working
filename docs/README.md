# P8FS Native Client Documentation

This directory contains documentation for the P8FS Native Client implementation, including server integration patterns and mobile application pseudo-code implementations.

## Overview

P8FS is a distributed file system with native mobile client support, featuring secure S3-compatible storage, tenant isolation, and seamless chat integration with audio transcription capabilities.

## Directory Structure

- **`p8fs/`** - Server documentation (Work in Progress)
- **`pseudo-code/`** - Concrete implementations of the 4 core P8FS flows:
  - S3 uploads with AWS V4 signing
  - Chat session management
  - Authentication flows
  - Audio recording and transcription

## Key Implementation Points

Fingerprint or Face ID biometrics should be used or the user ping should be used to access the app after sleep time out. The sense of the app being a secure vault should be maintained.

### Environment Configuration
- **API URL**: Configure base API endpoint via environment variables
- **S3 URL**: S3-compatible storage endpoint configuration
- **Development**: Current S3 tokens are **write-only** for dev server security

### Chat Sessions
- **Headers**: Send as many context headers as possible with each request
- **Session ID**: Always include session-id generated with each chat session
- **Message Handling**: Send entire message content with each submit (only the last submitted message)
- **Streaming**: Support rich markdown rendering for real-time responses

### Audio Processing
- **Recording**: Mobile apps can record audio directly
- **Transcription**: Server-side transcription using `is_audio` header flag
- **Integration**: Audio seamlessly integrates with text chat flows

### S3 Integration
- **Signing**: Proper AWS V4 signature implementation required (see samples)
- **Security**: Write-only tokens for tenant isolation
- **Path Structure**: `tenant-id/uploads/yyyy/mm/dd/filename_timestamp.ext`
- **Tenant Isolation**: Each tenant gets dedicated S3 bucket

### Authentication Flow
- **PIN UI**: Implement user-friendly PIN entry interface
- **Keychain Storage**: Store JWT tokens and generated keypairs securely
- **Device Approval**: Mobile devices can approve desktop clients via QR code
- **Cross-Device**: Seamless authentication across mobile and desktop


There is a deep link `p8fs://auth` that launches the device auth flow with an 8 digit code.
The settings can also launch this page for manual code entry and approval in case there are problems with the QR code.

### Security Best Practices
- **JWT Storage**: Use platform keychain/keystore for token persistence
- **Credential Derivation**: Future: configure tokens and API endpoints on-device
- **Device Context**: Include comprehensive device metadata in headers
- **Tenant Boundaries**: Strict isolation between tenant data


## Getting Started

1. Review the pseudo-code implementations in `pseudo-code/s3-uploads/`
2. Examine content header specifications in `p8fs/content_headers.md`
3. Test S3 integration using scripts in `../scripts/s3_test/`
4. Follow authentication patterns for secure token management

## Testing

Use the S3 test script to validate your implementation:
```bash
cd ../scripts/s3_test
python test_s3_upload.py
```

This will test upload functionality and verify that GET operations are properly denied for write-only credentials.