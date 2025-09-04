#!/usr/bin/env python3
"""
Get Development JWT Token

This script generates a complete JWT authentication package for P8-FS development:

1. **Generates Ed25519 key pair** - Creates device identity keys for authentication
2. **Calls dev registration endpoint** - Uses P8FS_DEV_TOKEN_SECRET to bypass email verification  
3. **Gets JWT tokens** - Returns access_token + refresh_token for API calls
4. **Saves everything** - Token + private/public keys for device operations

SECURITY: 
- Requires P8FS_DEV_TOKEN_SECRET environment variable (strong dev token)
- testing@percolationlabs.ai gets deterministic tenant-edc26ee6dd63f00e (idempotent)
- Other emails get random tenant IDs

FLOW:
- Server validates dev token → creates/reuses tenant → issues JWT → returns tokens
- Client saves: JWT tokens + Ed25519 key pair for signing future requests

Usage:
    ./get_dev_jwt                           # Use testing@percolationlabs.ai (deterministic tenant)
    ./get_dev_jwt --email someone@gmail.com # Use custom email (random tenant)  
    ./get_dev_jwt -o custom_token.json      # Save to custom location
"""

import asyncio
import json
import sys
import os
import argparse
import base64
from datetime import datetime
from typing import Dict, Any, Optional
import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

from dotenv import load_dotenv


# Configuration
BASE_URL = "https://p8fs.percolationlabs.ai"
DEFAULT_EMAIL = "testing@percolationlabs.ai"
DEFAULT_OUTPUT = "test_jwt_token.json"

load_dotenv()

async def get_dev_jwt_token(email: str, output_file: str) -> Optional[Dict[str, Any]]:
    """Get development JWT token via dev endpoint"""
    
    # Get dev token from environment
    dev_token = os.getenv("P8FS_DEV_TOKEN_SECRET")
    if not dev_token:
        print("ERROR: P8FS_DEV_TOKEN_SECRET not set", file=sys.stderr)
        print("export P8FS_DEV_TOKEN_SECRET='p8fs-dev-dHMZAB_dK8JR6ps-zLBSBTfBeoNdXu2KcpNywjDfD58'", file=sys.stderr)
        return None
    
    # Generate Ed25519 key pair
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key()
    
    # Serialize keys
    private_key_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    ).decode('utf-8')
    
    public_key_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode('utf-8')
    
    public_key_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw
    )
    public_key_b64 = base64.b64encode(public_key_bytes).decode('utf-8')
    
    # Make request
    async with httpx.AsyncClient(timeout=30.0) as client:
        try:
            response = await client.post(
                f"{BASE_URL}/api/v1/auth/dev/register",
                json={
                    "email": email,
                    "public_key": public_key_b64,
                    "device_info": {
                        "device_name": "Dev JWT Generator",
                        "device_type": "desktop",
                        "os_version": "Development",
                        "app_version": "dev-1.0.0",
                    },
                },
                headers={
                    "X-Dev-Token": dev_token,
                    "X-Dev-Email": email,
                    "X-Dev-Code": "123456",
                }
            )
            
            if response.status_code != 200:
                print(f"ERROR: Request failed {response.status_code}: {response.text}", file=sys.stderr)
                return None
                
            token_data = response.json()
            access_token = token_data.get("access_token")
            
            if not access_token:
                print("ERROR: No access token received", file=sys.stderr)
                return None
                
        except Exception as e:
            print(f"ERROR: {e}", file=sys.stderr)
            return None
    
    # Save token with key pair
    token_info = {
        "access_token": access_token,
        "refresh_token": token_data.get("refresh_token"),
        "token_type": "Bearer",
        "expires_in": token_data.get("expires_in", 3600),
        "tenant_id": token_data.get("tenant_id"),
        "created_at": datetime.now().isoformat(),
        "email": email,
        "device_keys": {
            "private_key_pem": private_key_pem,
            "public_key_pem": public_key_pem,
            "public_key_b64": public_key_b64
        }
    }
    
    try:
        with open(output_file, 'w') as f:
            json.dump(token_info, f, indent=2)
        print(f"Token saved to {output_file}")
        return token_info
    except Exception as e:
        print(f"ERROR: Failed to save token: {e}", file=sys.stderr)
        return None

def main():
    parser = argparse.ArgumentParser(description="Get P8FS development JWT token")
    parser.add_argument(
        "--email", 
        default=DEFAULT_EMAIL,
        help=f"Email to use (default: {DEFAULT_EMAIL})"
    )
    parser.add_argument(
        "-o", "--output",
        default=DEFAULT_OUTPUT,
        help=f"Output file (default: {DEFAULT_OUTPUT})"
    )
    args = parser.parse_args()
    
    result = asyncio.run(get_dev_jwt_token(args.email, args.output))
    
    if not result:
        sys.exit(1)

if __name__ == "__main__":
    main()

# Example: Using saved device keys for message signing
# 
# import json
# import base64
# from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
# from cryptography.hazmat.primitives import serialization
# 
# def sign_message_with_device_key(message: str, token_file: str = "test_jwt_token.json") -> str:
#     """Sign a message with the saved device private key"""
#     with open(token_file, 'r') as f:
#         token_data = json.load(f)
#     
#     private_key_pem = token_data["device_keys"]["private_key_pem"]
#     private_key = Ed25519PrivateKey.from_private_bytes(
#         serialization.load_pem_private_key(
#             private_key_pem.encode('utf-8'),
#             password=None
#         ).private_bytes(
#             encoding=serialization.Encoding.Raw,
#             format=serialization.PrivateFormat.Raw,
#             encryption_algorithm=serialization.NoEncryption()
#         )
#     )
#     
#     signature = private_key.sign(message.encode('utf-8'))
#     return base64.b64encode(signature).decode('utf-8')
# 
# # Usage:
# # signature = sign_message_with_device_key("Hello, P8-FS!")
# # print(f"Signature: {signature}")