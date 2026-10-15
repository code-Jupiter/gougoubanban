#!/usr/bin/env python3
"""Convert the approved pet videos into transparent Android Animated WebP resources.

The source videos use a warm off-white chroma background. This script only
performs deterministic media conversion: it keys that background, downsizes
the existing frames, and packages them as animated WebP with alpha.
"""

from __future__ import annotations

import argparse
import gc
import json
import subprocess
from pathlib import Path

from PIL import Image


WIDTH = 360
HEIGHT = 843
FPS = 24
KEY_COLOR = "0xf5ede2"
KEY_SIMILARITY = "0.08"
KEY_BLEND = "0.02"


def read_manifest(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return payload["animations"]


def iter_rgba_frames(source: Path):
    filter_graph = (
        f"colorkey={KEY_COLOR}:{KEY_SIMILARITY}:{KEY_BLEND},"
        f"scale={WIDTH}:{HEIGHT}:flags=lanczos,format=rgba"
    )
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(source),
        "-vf",
        filter_graph,
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE)
    assert process.stdout is not None
    frame_size = WIDTH * HEIGHT * 4
    try:
        while True:
            raw = process.stdout.read(frame_size)
            if not raw:
                break
            if len(raw) != frame_size:
                raise RuntimeError(f"truncated frame from {source}")
            yield Image.frombytes("RGBA", (WIDTH, HEIGHT), raw)
    finally:
        process.stdout.close()
        return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"ffmpeg failed for {source}")


def convert_animation(source: Path, destination: Path, loop: int) -> int:
    frames = list(iter_rgba_frames(source))
    if not frames:
        raise RuntimeError(f"no frames found in {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        destination,
        format="WEBP",
        save_all=True,
        append_images=frames[1:],
        duration=round(1000 / FPS),
        loop=loop,
        quality=82,
        method=4,
    )
    frame_count = len(frames)
    del frames
    gc.collect()
    return frame_count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--app-res-dir", type=Path, required=True)
    parser.add_argument("--app-manifest", type=Path, required=True)
    args = parser.parse_args()

    animations = read_manifest(args.source_manifest)
    app_entries = []
    for animation in animations:
        animation_id = animation["id"]
        source = args.source_dir / animation["file"]
        filename = f"{animation_id}.webp" if animation_id.startswith("dog_") else f"dog_{animation_id}.webp"
        output = args.output_dir / filename
        # Idle loops continuously. Action clips are packaged as a single pass;
        # the app-side player returns to default_idle after the source duration.
        loop = 0 if animation_id == "default_idle" else 1
        frame_count = convert_animation(source, output, loop)
        app_target = args.app_res_dir / filename
        app_target.parent.mkdir(parents=True, exist_ok=True)
        app_target.write_bytes(output.read_bytes())
        app_entries.append(
            {
                "animation_id": animation_id,
                "resource": f"@drawable/{filename.removesuffix('.webp')}",
                "file": filename,
                "format": "animated_webp",
                "alpha": True,
                "width": WIDTH,
                "height": HEIGHT,
                "fps": FPS,
                "frame_count": frame_count,
                "duration_ms": round(frame_count * 1000 / FPS),
                "loop": "infinite" if loop == 0 else "single_pass",
                "widget_compatible": True,
                "fallback": "default_idle",
                "source": str(source),
            }
        )

    args.app_manifest.parent.mkdir(parents=True, exist_ok=True)
    args.app_manifest.write_text(
        json.dumps(
            {
                "version": 2,
                "character": "测试小狗",
                "format": "Android Animated WebP",
                "canvas": {"width": WIDTH, "height": HEIGHT, "alpha": True},
                "chroma_key": {
                    "color": "#f5ede2",
                    "similarity": float(KEY_SIMILARITY),
                    "blend": float(KEY_BLEND),
                },
                "animations": app_entries,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
