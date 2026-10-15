#!/usr/bin/env python3
"""Public-edition real provider verification.

Reads the two API keys from the gitignored `secrets.properties`, makes one very
small real chat request per provider, and cross-checks that each key only works
against its own provider. It never prints a key, an Authorization header, a full
request body or a full response body: only status codes, host names, whether a
reply was parseable, and the reply length.

Usage:
    python3 scripts/verify_public_providers.py

Exit code 0 only when every required check passes.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRETS = ROOT / "secrets.properties"

MINIMAX_ENDPOINT = os.environ.get("MINIMAX_ENDPOINT", "https://api.minimaxi.com/v1/text/chatcompletion_v2")
MINIMAX_MODEL = os.environ.get("MINIMAX_MODEL", "MiniMax-M2")
BAILIAN_ENDPOINT = os.environ.get(
    "BAILIAN_ENDPOINT", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
)
BAILIAN_MODEL = os.environ.get("BAILIAN_MODEL", "deepseek-v4-flash")

PROMPT = "用一句话介绍一下你自己。"


def read_secret(name: str) -> str:
    """Reads one value from the gitignored secrets file without echoing it."""
    if not SECRETS.exists():
        return ""
    for line in SECRETS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            return value.strip().strip("'\"")
    return ""


def host_of(url: str) -> str:
    try:
        from urllib.parse import urlparse

        return urlparse(url).hostname or url
    except Exception:  # pragma: no cover - defensive
        return url


def post(url: str, key: str, model: str, style: str) -> dict:
    """One minimal real request. Returns only non-sensitive metadata."""
    if style == "minimax":
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": "你是陪聊助手。"},
                {"role": "user", "content": PROMPT},
            ],
            "temperature": 0.2,
            "max_completion_tokens": 256,
        }
    else:
        body = {
            "model": model,
            "messages": [
                {"role": "system", "content": "你是陪聊助手。"},
                {"role": "user", "content": PROMPT},
            ],
            "temperature": 0.2,
            "max_tokens": 256,
        }

    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
        method="POST",
    )
    started = time.time()
    result = {"url_host": host_of(url), "model": model}
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8", errors="replace")
            result["status"] = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        result["status"] = error.code
    except Exception as error:  # network failure
        result["status"] = None
        result["error_class"] = type(error).__name__
        result["latency_ms"] = int((time.time() - started) * 1000)
        return result

    result["latency_ms"] = int((time.time() - started) * 1000)
    result["response_bytes"] = len(raw)
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        result["parse"] = "invalid_json"
        return result

    if style == "minimax":
        provider_status = (parsed.get("base_resp") or {}).get("status_code")
        content = (
            ((parsed.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        )
        result["provider_status"] = provider_status
    else:
        content = (
            ((parsed.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        )
    result["reply_length"] = len(content.strip())
    result["reply_parseable"] = bool(content.strip())
    result["finish_reason"] = ((parsed.get("choices") or [{}])[0] or {}).get("finish_reason")
    return result


def report(label: str, outcome: dict) -> bool:
    ok = outcome.get("status") == 200 and outcome.get("reply_parseable") is True
    if outcome.get("provider_status") not in (None, 0):
        ok = False
    state = "PASS" if ok else "FAIL"
    print(
        f"[{state}] {label}: status={outcome.get('status')} "
        f"host={outcome.get('url_host')} model={outcome.get('model')} "
        f"reply_len={outcome.get('reply_length')} latency_ms={outcome.get('latency_ms')}"
        + (f" provider_status={outcome.get('provider_status')}" if outcome.get("provider_status") is not None else "")
        + (f" parse={outcome.get('parse')}" if outcome.get("parse") else "")
        + (f" finish={outcome.get('finish_reason')}" if outcome.get("finish_reason") else "")
        + (f" error={outcome.get('error_class')}" if outcome.get("error_class") else "")
    )
    return ok


def main() -> int:
    minimax_key = read_secret("MINIMAX_API_KEY")
    bailian_key = read_secret("BAILIAN_API_KEY")

    print("Keys present (values never printed):")
    print(f"  MINIMAX_API_KEY: {'yes' if minimax_key else 'no'} (len={len(minimax_key)})")
    print(f"  BAILIAN_API_KEY: {'yes' if bailian_key else 'no'} (len={len(bailian_key)})")
    print()

    checks: list[bool] = []

    if minimax_key:
        print("== MiniMax official (independent run) ==")
        checks.append(report("MiniMax chat", post(MINIMAX_ENDPOINT, minimax_key, MINIMAX_MODEL, "minimax")))
        # Same key against the other provider's host must not succeed: this is the
        # "never borrow the other provider's credential" contract.
        cross = post(BAILIAN_ENDPOINT, minimax_key, BAILIAN_MODEL, "openai")
        cross_ok = cross.get("status") not in (200,) or cross.get("reply_parseable") is not True
        print(
            f"[{'PASS' if cross_ok else 'FAIL'}] MiniMax key rejected by Bailian endpoint: "
            f"status={cross.get('status')} reply_len={cross.get('reply_length')}"
        )
        checks.append(cross_ok)
    else:
        print("[SKIP] MiniMax key not configured")
        checks.append(False)

    print()
    if bailian_key:
        print("== Alibaba Bailian DeepSeek (independent run) ==")
        checks.append(report("Bailian chat", post(BAILIAN_ENDPOINT, bailian_key, BAILIAN_MODEL, "openai")))
        cross = post(MINIMAX_ENDPOINT, bailian_key, MINIMAX_MODEL, "minimax")
        cross_ok = cross.get("status") not in (200,) or cross.get("reply_parseable") is not True
        print(
            f"[{'PASS' if cross_ok else 'FAIL'}] Bailian key rejected by MiniMax endpoint: "
            f"status={cross.get('status')} reply_len={cross.get('reply_length')}"
        )
        checks.append(cross_ok)
    else:
        print("[SKIP] Bailian key not configured")
        checks.append(False)

    print()
    passed = sum(1 for c in checks if c)
    print(f"Result: {passed}/{len(checks)} checks passed")
    if passed != len(checks):
        print("Public provider verification did NOT fully pass.", file=sys.stderr)
        return 1
    print("Public provider verification passed (both providers run independently, no cross-provider key use).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
