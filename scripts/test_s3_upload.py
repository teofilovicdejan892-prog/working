#!/usr/bin/env python3
"""
Minimal P8FS S3 upload test
Tests write access to tenant-test/uploads/yyyy/mm/dd/test.ext
"""

import boto3
import os
from datetime import datetime
from botocore.client import Config
from dotenv import load_dotenv

# Load environment variables
load_dotenv()


def main():
    # Read credentials from .env (fallback to hardcoded for testing)
    access_key = os.getenv("S3_ACCESS_KEY")
    secret_key = os.getenv("S3_SECRET_KEY")
    endpoint = os.getenv("P8FS_S3_URI", "https://s3.percolationlabs.ai")

    print(f"Access Key: {access_key}")
    print(f"Endpoint: {endpoint}")

    # S3 client with proper configuration
    s3 = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name="us-east-1",
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )

    # Upload path format: existing-bucket/uploads/yyyy/mm/dd/test.ext
    now = datetime.utcnow()
    s3_key = f"uploads/{now.strftime('%Y/%m/%d')}/test_{int(now.timestamp())}.txt"
    bucket = "existing-bucket"

    # Test content
    test_data = f"P8FS test upload {now.isoformat()}\n"

    try:
        # Test list objects first
        try:
            response = s3.list_objects_v2(Bucket=bucket)
            print(
                f"List objects successful - found {response.get('KeyCount', 0)} objects"
            )
        except Exception as e:
            print(f"List objects failed: {e}")

        # Upload
        s3.put_object(
            Bucket=bucket, Key=s3_key, Body=test_data, ContentType="text/plain"
        )
        print(f"Upload successful: {endpoint}/{bucket}/{s3_key}")

        # Test read (should fail for write-only user)
        try:
            s3.get_object(Bucket=bucket, Key=s3_key)
            print("WARNING: Read access succeeded (expected to fail)")
        except Exception as e:
            print(f"Read access denied (correct): {e}")

    except Exception as e:
        print(f"Upload failed: {e}")


if __name__ == "__main__":
    main()
