#!/usr/bin/env python3
"""
JWT Token + SSE Chat Completion Sample

Demonstrates the complete P8-FS development workflow:
1. Load JWT token created by ./get_dev_jwt
2. Stream chat completion from P8-Simulator agent
3. Display real-time markdown SSE responses

The P8-Simulator (p8-sim) agent endpoint provides:
- OpenAI-compatible streaming format
- Rich markdown responses with tables, code blocks, examples
- No external API calls (pure simulation)
- Perfect for development and testing

Usage:
    python sample_jwt_sse_chat.py

Prerequisites:  
    ./get_dev_jwt  # Creates test_jwt_token.json with JWT + device keys
"""
import asyncio
import json
import sys
import os
import httpx

API_BASE = "https://p8fs.percolationlabs.ai"
TOKEN_FILE = "test_jwt_token.json"

def load_jwt_token() -> str:
    """Load JWT token from file created by get_dev_jwt"""
    if not os.path.exists(TOKEN_FILE):
        print(f"ERROR: {TOKEN_FILE} not found", file=sys.stderr)
        print("Run: ./get_dev_jwt", file=sys.stderr)
        sys.exit(1)
    
    try:
        with open(TOKEN_FILE, 'r') as f:
            token_data = json.load(f)
        
        access_token = token_data.get("access_token")
        if not access_token:
            print(f"ERROR: No access_token in {TOKEN_FILE}", file=sys.stderr)
            sys.exit(1)
            
        return access_token
        
    except Exception as e:
        print(f"ERROR: Failed to load token: {e}", file=sys.stderr)
        sys.exit(1)

async def stream_chat_completion(token: str, messages: list) -> None:
    """Stream chat from P8-Simulator with SSE"""
    headers = {"Authorization": f"Bearer {token}"}
    payload = {
        "model": "gpt-4o-mini",  # Use standard model name
        "messages": messages,
        "stream": True,
        "temperature": 0.7,
    }

    # P8-Simulator endpoint - provides rich markdown simulation 
    endpoint = f"{API_BASE}/api/v1/agent/p8-sim/chat/completions"

    async with httpx.AsyncClient(timeout=60.0) as client:
        async with client.stream("POST", endpoint, json=payload, headers=headers) as response:
            if response.status_code != 200:
                error_text = await response.aread()
                print(f"ERROR {response.status_code}: {error_text.decode()}", file=sys.stderr)
                return

            # Process SSE stream
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    data = line[6:]  # Remove "data: " prefix
                    if data == "[DONE]":
                        break
                    try:
                        # Parse OpenAI-format chunk
                        chunk = json.loads(data)
                        content = chunk["choices"][0]["delta"].get("content", "")
                        if content:
                            print(content, end="", flush=True)
                    except (json.JSONDecodeError, KeyError, IndexError):
                        continue  # Skip malformed chunks

async def main():
    print("🔐 P8-FS JWT + SSE Streaming Sample")
    print("=" * 50)
    
    # Load token and show tenant info
    token = load_jwt_token()
    
    # Show token info
    try:
        with open(TOKEN_FILE, 'r') as f:
            token_data = json.load(f)
        print(f"📧 Email: {token_data.get('email', 'unknown')}")
        print(f"🔑 Tenant: {token_data.get('tenant_id', 'unknown')}")
    except:
        pass
    
    print(f"🤖 Streaming from P8-Simulator...")
    print("=" * 50)
    
    # Chat messages
    messages = [
        {"role": "system", "content": "You are the P8-FS simulator. Provide detailed responses with markdown formatting including code examples, tables, and structured information."},
        {"role": "user", "content": "What is P8-FS? Show me a practical example with code and explain the key features using tables and markdown."},
    ]
    
    # Stream response
    await stream_chat_completion(token, messages)
    print("\n" + "=" * 50)
    print("✅ Complete")

if __name__ == "__main__":
    asyncio.run(main())