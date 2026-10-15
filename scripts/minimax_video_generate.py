#!/usr/bin/env python3
"""Generate and download a MiniMax video without exposing the API key."""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_API_BASE = "https://api.minimaxi.com/v1"


def parse_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key.strip()] = value
    return values


def image_data_url(path: Path) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "image/png"
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def request_json(
    url: str,
    api_key: str,
    *,
    method: str = "GET",
    payload: dict[str, object] | None = None,
) -> dict[str, object]:
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(
        url,
        data=body,
        method=method,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"MiniMax HTTP {error.code}: {detail}") from error


def require_success(response: dict[str, object]) -> None:
    base_resp = response.get("base_resp")
    if isinstance(base_resp, dict) and base_resp.get("status_code") not in (None, 0):
        raise RuntimeError(f"MiniMax API error: {json.dumps(base_resp, ensure_ascii=False)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--secrets", type=Path, default=Path("secrets.properties"))
    parser.add_argument("--key-property", default="minimax.chat.api_key")
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--first-frame", type=Path, required=True)
    parser.add_argument("--last-frame", type=Path)
    parser.add_argument("--prompt-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--model", default="MiniMax-Hailuo-02")
    parser.add_argument("--duration", type=int, default=6)
    parser.add_argument("--resolution", default="768P")
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    args = parser.parse_args()

    props = parse_properties(args.secrets)
    api_key = props.get(args.key_property, "")
    if not api_key:
        raise SystemExit(f"Missing {args.key_property} in {args.secrets}")

    prompt = args.prompt_file.read_text(encoding="utf-8").strip()
    payload: dict[str, object] = {
        "model": args.model,
        "prompt": prompt,
        "first_frame_image": image_data_url(args.first_frame),
        "duration": args.duration,
        "resolution": args.resolution,
        "prompt_optimizer": False,
    }
    if args.last_frame is not None:
        payload["last_frame_image"] = image_data_url(args.last_frame)

    api_base = args.api_base.rstrip("/")
    created = request_json(f"{api_base}/video_generation", api_key, method="POST", payload=payload)
    require_success(created)
    task_id = str(created.get("task_id", ""))
    if not task_id:
        raise RuntimeError(f"MiniMax response did not include task_id: {created}")
    print(f"Created MiniMax task {task_id}", flush=True)

    deadline = time.monotonic() + args.timeout_seconds
    query_url = f"{api_base}/query/video_generation?{urllib.parse.urlencode({'task_id': task_id})}"
    result: dict[str, object] | None = None
    last_status = ""
    while time.monotonic() < deadline:
        result = request_json(query_url, api_key)
        require_success(result)
        status = str(result.get("status", ""))
        if status != last_status:
            print(f"Task {task_id}: {status}", flush=True)
            last_status = status
        if status == "Success":
            break
        if status == "Fail":
            raise RuntimeError(f"MiniMax video generation failed: {result}")
        time.sleep(args.poll_seconds)
    else:
        raise TimeoutError(f"MiniMax task {task_id} exceeded {args.timeout_seconds}s")

    assert result is not None
    file_id = str(result.get("file_id", ""))
    if not file_id:
        raise RuntimeError(f"Successful task did not include file_id: {result}")
    retrieve_url = f"{api_base}/files/retrieve?{urllib.parse.urlencode({'file_id': file_id})}"
    file_result = request_json(retrieve_url, api_key)
    require_success(file_result)
    file_info = file_result.get("file")
    if not isinstance(file_info, dict) or not file_info.get("download_url"):
        raise RuntimeError(f"MiniMax response did not include download_url: {file_result}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(str(file_info["download_url"]), timeout=180) as response:
        args.output.write_bytes(response.read())

    metadata_path = args.metadata or args.output.with_suffix(".json")
    metadata = {
        "task_id": task_id,
        "file_id": file_id,
        "api_base": api_base,
        "model": args.model,
        "duration": args.duration,
        "resolution": args.resolution,
        "first_frame": str(args.first_frame),
        "last_frame": str(args.last_frame) if args.last_frame else None,
        "prompt_file": str(args.prompt_file),
        "video_width": result.get("video_width"),
        "video_height": result.get("video_height"),
        "output": str(args.output),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Downloaded {args.output}", flush=True)


if __name__ == "__main__":
    main()
