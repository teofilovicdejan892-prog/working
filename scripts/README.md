# Scripts

## Setup

```bash
pip install -r requirements.txt
```

## get_dev_jwt

Generates JWT token for P8FS development using dev endpoint.

```bash
export P8FS_DEV_TOKEN_SECRET='your-dev-token-secret'
./get_dev_jwt                           # Uses testing@percolationlabs.ai
./get_dev_jwt --email custom@email.com  # Custom email
./get_dev_jwt -o custom_token.json      # Custom output file
```

Creates `test_jwt_token.json` with access token, refresh token, and Ed25519 device keys.

## sample_jwt_sse_chat.py

Tests JWT authentication and SSE chat streaming with P8-Simulator agent.

```bash
python sample_jwt_sse_chat.py
```

Requires `test_jwt_token.json` from `get_dev_jwt`.

## test_s3_upload.py

Tests S3 write access to P8FS storage.

```bash
python test_s3_upload.py
```

Requires `.env` with:
```
S3_ACCESS_KEY=your_access_key
S3_SECRET_KEY=your_secret_key
P8FS_S3_URI=https://s3.percolationlabs.ai
```

Uploads to `existing-bucket/uploads/yyyy/mm/dd/test_timestamp.txt`.