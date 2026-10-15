#!/usr/bin/env python3
"""BYOK live smoke. Reads only presence/value mapping from the gitignored
secrets.properties at runtime. It never prints the key, request headers or
full reply text. Excluded from Gradle unit tests by living under scripts/."""
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRETS = ROOT / "secrets.properties"
import os
CN_ENDPOINT = os.environ.get("BYOK_ENDPOINT", "https://api.minimaxi.com/v1/text/chatcompletion_v2")
MODEL = "MiniMax-M2"


def read_secret(key):
    if not SECRETS.exists():
        return ""
    for line in SECRETS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() == key:
            value = v.strip()
            return value.strip(chr(39) + chr(34))
    return ""


def call(key, prompt):
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": "你是测试小狗。请直接回复，不输出 JSON。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.3,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        CN_ENDPOINT,
        data=body,
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw, int((time.time() - start) * 1000)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace"), int((time.time() - start) * 1000)
    except Exception as exc:
        return -1, type(exc).__name__, int((time.time() - start) * 1000)


def extract_text(raw):
    try:
        data = json.loads(raw)
        base = (data.get("base_resp") or {})
        if base.get("status_code", 0) != 0:
            msg = str(base.get("status_msg") or "")[:140]
            return "", "base_resp_" + str(base.get("status_code", 0)), msg
        choices = data.get("choices") or []
        content = (choices[0].get("message") or {}).get("content") if choices else ""
        return (content or "").strip(), "ok", ""
    except Exception as exc:
        return "", type(exc).__name__, ""


def main():
    key = read_secret("minimax.chat.api_key")
    configured = "CONFIGURED" if key else "MISSING"
    print("minimax.chat.api_key:", configured)
    if not key:
        return 2
    prompts = [
        "只回复：OK",
        "用两三句中文回答：周末想去公园散步，应该注意什么？",
        "用清晰的三点说明：Android Kotlin 中 suspend 函数和普通函数的主要区别。",
    ]
    max_prompts = int(os.environ.get("BYOK_MAX_PROMPTS", str(len(prompts))))
    failed = False
    for index, prompt in enumerate(prompts[:max_prompts], 1):
        status, raw, latency = call(key, prompt)
        text, parse_state, status_msg = extract_text(raw)
        chinese = any('\u4e00' <= ch <= '\u9fff' for ch in text)
        ok = status == 200 and parse_state == "ok" and len(text) > 0
        print(f"prompt={index} http={status} latency_ms={latency} chars={len(text)} chinese={chinese} parse={parse_state} ok={ok} status_msg_snippet={status_msg}")
        failed = failed or (not ok)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
