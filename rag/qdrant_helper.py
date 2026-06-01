"""Qdrant client factory for AI Network RAG.

Supports local Qdrant and Cloudflare Access protected remote Qdrant by adding
CF-Access service token headers from environment. Secrets must stay in ENV_FILE
or local .env files and are never logged here.
"""
from __future__ import annotations

import os
import urllib.parse


def make_qdrant_client(url: str):
    from qdrant_client import QdrantClient

    cid = os.environ.get("QDRANT_CF_ACCESS_CLIENT_ID")
    secret = os.environ.get("QDRANT_CF_ACCESS_CLIENT_SECRET")
    headers = None
    if cid and secret:
        headers = {
            "CF-Access-Client-Id": cid,
            "CF-Access-Client-Secret": secret,
        }

    parsed = urllib.parse.urlparse(url)
    if parsed.hostname:
        port = parsed.port or (443 if parsed.scheme == "https" else 6333)
        return QdrantClient(
            host=parsed.hostname,
            port=port,
            https=(parsed.scheme == "https"),
            prefix=parsed.path or "",
            headers=headers,
            timeout=60,
            check_compatibility=False,
        )

    return QdrantClient(url=url, headers=headers, timeout=60, check_compatibility=False)
