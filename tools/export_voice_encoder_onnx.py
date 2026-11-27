#!/usr/bin/env python3
"""
Qualification-gated exporter for `patriotyk/HolosTTS` `voice_encoder.pt` (H27).

NOTE (Upstream Contract):
`patriotyk/HolosTTS` (`voice_encoder.pt` at commit 86c5b32cbb217ca030a15f8331f36ea9b85ff7d5)
stores a PyTorch `state_dict` for two StyleEncoders operating on normalized log-mel features,
NOT a raw-waveform TorchScript module. This script refuses to emit an unverified raw-waveform
graph and validates checkpoint format before exporting.
"""
import argparse
import sys
import torch
from huggingface_hub import hf_hub_download


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="patriotyk/HolosTTS")
    ap.add_argument("--revision", default="main")
    ap.add_argument("--out", default="voice_encoder.onnx")
    args = ap.parse_args()

    pt_path = hf_hub_download(repo_id=args.repo, filename="voice_encoder.pt", revision=args.revision)
    try:
        model = torch.jit.load(pt_path, map_location="cpu").eval()
    except Exception as exc:
        print(
            "ERROR: Upstream voice_encoder.pt is a PyTorch state_dict (log-mel StyleEncoder), "
            "not a TorchScript raw-waveform graph. Refusing to emit an unqualified ONNX export.\n"
            f"Details: {exc}",
            file=sys.stderr,
        )
        sys.exit(2)

    dummy_audio = torch.randn(1, 24000 * 3, dtype=torch.float32)
    with torch.no_grad():
        ref = model(dummy_audio)
    if ref.ndim != 2 or ref.shape[-1] != 256 or not torch.isfinite(ref).all():
        print(f"ERROR: Unexpected output shape/values: {tuple(ref.shape)}", file=sys.stderr)
        sys.exit(3)

    torch.onnx.export(
        model,
        (dummy_audio,),
        args.out,
        input_names=["audio"],
        output_names=["embedding"],
        dynamic_axes={"audio": {0: "batch", 1: "samples"}, "embedding": {0: "batch"}},
        opset_version=17,
    )
    print(f"Verified and exported {args.out}")


if __name__ == "__main__":
    main()
