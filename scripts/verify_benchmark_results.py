#!/usr/bin/env python3
"""Fail when the Hermes send benchmark contains a frozen CPU frame."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def benchmark_files(root: Path) -> list[Path]:
    if root.is_file():
        return [root]
    return sorted(root.rglob("*benchmarkData.json"))


def maximum_frame_duration(path: Path, benchmark_name: str) -> float:
    payload = json.loads(path.read_text(encoding="utf-8"))
    matches = [item for item in payload.get("benchmarks", []) if item.get("name") == benchmark_name]
    if not matches:
        raise ValueError(f"{benchmark_name!r} was not found in {path}")
    runs = matches[0].get("sampledMetrics", {}).get("frameDurationCpuMs", {}).get("runs", [])
    samples = [float(value) for run in runs for value in run]
    if not samples:
        raise ValueError(f"{benchmark_name!r} has no frameDurationCpuMs samples in {path}")
    return max(samples)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path, help="Benchmark JSON file or directory containing one")
    parser.add_argument("--benchmark", default="sendAndRenderFirstProgress")
    parser.add_argument("--max-frame-ms", type=float, default=700.0)
    args = parser.parse_args()

    files = benchmark_files(args.results)
    if not files:
        parser.error(f"no benchmarkData.json file found under {args.results}")
    measured = max(maximum_frame_duration(path, args.benchmark) for path in files)
    print(f"{args.benchmark}: maximum CPU frame {measured:.1f} ms (limit {args.max_frame_ms:.1f} ms)")
    return 0 if measured <= args.max_frame_ms else 1


if __name__ == "__main__":
    raise SystemExit(main())
