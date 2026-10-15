#!/usr/bin/env python3
"""Build identity-stable motion clips from a neutral character frame."""

from __future__ import annotations

import argparse
import math
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image


def smoothstep(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def lerp(start: float, end: float, amount: float) -> float:
    return start + (end - start) * smoothstep(amount)


def motion(mode: str, seconds: float) -> tuple[float, float, float]:
    """Return horizontal scale, vertical scale, and vertical translation."""
    if mode == "expecting":
        if 1.0 <= seconds < 1.35:
            phase = (seconds - 1.0) / 0.35
            return 1.004, lerp(1.0, 1.014, phase), lerp(0.0, -18.0, phase)
        if 1.35 <= seconds < 1.8:
            phase = (seconds - 1.35) / 0.45
            return 1.004, lerp(1.014, 1.0, phase), lerp(-18.0, 0.0, phase)
        if 2.25 <= seconds < 2.55:
            phase = (seconds - 2.25) / 0.30
            return 1.003, lerp(1.0, 1.01, phase), lerp(0.0, -12.0, phase)
        if 2.55 <= seconds < 2.9:
            phase = (seconds - 2.55) / 0.35
            return 1.003, lerp(1.01, 1.0, phase), lerp(-12.0, 0.0, phase)
        return 1.0, 1.0, 0.0

    if mode == "welcome":
        if 0.85 <= seconds < 1.12:
            phase = (seconds - 0.85) / 0.27
            return lerp(1.0, 1.018, phase), lerp(1.0, 0.958, phase), 0.0
        if 1.12 <= seconds < 1.4:
            phase = (seconds - 1.12) / 0.28
            return lerp(1.018, 0.992, phase), lerp(0.958, 1.022, phase), lerp(0.0, -78.0, phase)
        if 1.4 <= seconds < 1.72:
            phase = (seconds - 1.4) / 0.32
            return lerp(0.992, 1.0, phase), lerp(1.022, 1.0, phase), lerp(-78.0, 0.0, phase)
        if 1.72 <= seconds < 1.9:
            phase = (seconds - 1.72) / 0.18
            return lerp(1.0, 1.008, phase), lerp(1.0, 0.982, phase), 0.0
        if 1.9 <= seconds < 2.18:
            phase = (seconds - 1.9) / 0.28
            return lerp(1.008, 1.0, phase), lerp(0.982, 1.0, phase), 0.0
        return 1.0, 1.0, 0.0

    raise ValueError(f"Unsupported mode: {mode}")


def render_frame(source: Image.Image, bg: tuple[int, int, int], transform: tuple[float, float, float]) -> Image.Image:
    scale_x, scale_y, translate_y = transform
    width, height = source.size
    scaled_width = max(1, round(width * scale_x))
    scaled_height = max(1, round(height * scale_y))
    resized = source.resize((scaled_width, scaled_height), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (width, height), bg)
    x = round((width - scaled_width) / 2)
    y = round(height - scaled_height + translate_y)
    canvas.paste(resized, (x, y))
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("expecting", "welcome"), required=True)
    parser.add_argument("--width", type=int, default=720)
    parser.add_argument("--height", type=int, default=1686)
    parser.add_argument("--fps", type=int, default=24)
    parser.add_argument("--duration", type=float, default=6.0)
    args = parser.parse_args()

    source = Image.open(args.input).convert("RGB").resize(
        (args.width, args.height),
        Image.Resampling.LANCZOS,
    )
    pixels = np.asarray(source)
    border = np.concatenate(
        (
            pixels[:12].reshape(-1, 3),
            pixels[:, :12].reshape(-1, 3),
            pixels[:, -12:].reshape(-1, 3),
        ),
        axis=0,
    )
    bg = tuple(int(value) for value in np.median(border, axis=0))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{args.width}x{args.height}",
        "-r",
        str(args.fps),
        "-i",
        "-",
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "slow",
        "-crf",
        "16",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        str(args.output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    try:
        frame_count = round(args.duration * args.fps)
        for frame_index in range(frame_count):
            seconds = frame_index / args.fps
            frame = render_frame(source, bg, motion(args.mode, seconds))
            process.stdin.write(frame.tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise SystemExit("ffmpeg failed")


if __name__ == "__main__":
    main()
