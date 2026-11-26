#!/usr/bin/env python3
"""
Exports `patriotyk/HolosTTS` `voice_encoder.pt` to `voice_encoder.onnx`
(`audio` [1, N] float32 @ 24 kHz -> `embedding` [1, 256] float32) for on-device
WAV voice cloning inside HolosReader.

Usage:
    pip install torch huggingface_hub onnx
    python3 tools/export_voice_encoder_onnx.py --out voice_encoder.onnx
"""
import argparse
import os
import torch
from huggingface_hub import hf_hub_download


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="patriotyk/HolosTTS")
    ap.add_argument("--out", default="voice_encoder.onnx")
    args = ap.parse_args()

    pt_path = hf_hub_download(repo_id=args.repo, filename="voice_encoder.pt")
    model = torch.jit.load(pt_path, map_location="cpu").eval()
    dummy_audio = torch.randn(1, 24000 * 3, dtype=torch.float32)

    torch.onnx.export(
        model,
        (dummy_audio,),
        args.out,
        input_names=["audio"],
        output_names=["embedding"],
        dynamic_axes={"audio": {0: "batch", 1: "samples"}, "embedding": {0: "batch"}},
        opset_version=17,
    )
    print(f"Exported {args.out} ({os.path.getsize(args.out) / (1024*1024):.1f} MB)")


if __name__ == "__main__":
    main()
