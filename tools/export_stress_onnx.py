#!/usr/bin/env python3
"""
Exports `aloudreader/uk-stress-token-classifier` (XLM-RoBERTa-uk + 2-layer vowel-ordinal head)
into a single ONNX file (`uk_stress_contextual.onnx`) compatible with HolosReader's
`ContextualStressOnnx` Android runtime.

Precision choices:
- `int8` (~110 MB, default): Smallest & fastest on CPU / XNNPACK.
- `fp16` (~220 MB): Best for mobile GPU / NPU via Android NNAPI (keeps I/O float32).
- `fp32` (~440 MB): Full reference precision.

Usage:
    pip install torch transformers huggingface_hub onnx onnxruntime onnxconverter-common
    python3 tools/export_stress_onnx.py --precision fp16 --out uk_stress_contextual.onnx
"""
import argparse
import os
import torch
import torch.nn as nn
from huggingface_hub import hf_hub_download
from transformers import AutoModel
from onnxruntime.quantization import QuantType, quantize_dynamic


class ContextualStressWrapper(nn.Module):
    def __init__(self, encoder: nn.Module, head: nn.Module):
        super().__init__()
        self.encoder = encoder
        self.head = head

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        word_mask: torch.Tensor,
    ) -> torch.Tensor:
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        hidden = out.last_hidden_state  # [B, T, H]
        w = word_mask.unsqueeze(-1)  # [B, T, 1]
        denom = w.sum(dim=1).clamp(min=1.0)
        pooled = (hidden * w).sum(dim=1) / denom  # [B, H]
        return self.head(pooled)  # [B, 8]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="aloudreader/uk-stress-token-classifier")
    ap.add_argument("--out", default="uk_stress_contextual.onnx")
    ap.add_argument(
        "--precision",
        choices=["int8", "fp16", "fp32"],
        default="int8",
        help="Target weight precision (int8=CPU/XNNPACK, fp16=GPU/NNAPI, fp32=full)",
    )
    ap.add_argument("--fp32", action="store_true", help="Alias for --precision fp32")
    args = ap.parse_args()
    precision = "fp32" if args.fp32 else args.precision

    encoder = AutoModel.from_pretrained(args.repo)
    head_path = hf_hub_download(repo_id=args.repo, filename="head.pt")
    head_state = torch.load(head_path, map_location="cpu", weights_only=False)

    if isinstance(head_state, nn.Module):
        head = head_state
    else:
        sd = head_state.get("state_dict", head_state)
        hdim = encoder.config.hidden_size
        head = nn.Sequential(nn.Linear(hdim, hdim), nn.GELU(), nn.Linear(hdim, 8))
        head.load_state_dict(sd, strict=False)

    wrapper = ContextualStressWrapper(encoder.eval(), head.eval()).eval()
    dummy_ids = torch.ones((1, 32), dtype=torch.long)
    dummy_mask = torch.ones((1, 32), dtype=torch.long)
    dummy_word_mask = torch.zeros((1, 32), dtype=torch.float32)
    dummy_word_mask[0, 3:5] = 1.0

    raw_path = args.out if precision == "fp32" else args.out + ".fp32.tmp"
    torch.onnx.export(
        wrapper,
        (dummy_ids, dummy_mask, dummy_word_mask),
        raw_path,
        input_names=["input_ids", "attention_mask", "word_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "word_mask": {0: "batch", 1: "seq"},
            "logits": {0: "batch"},
        },
        opset_version=17,
    )
    if precision == "int8":
        quantize_dynamic(raw_path, args.out, weight_type=QuantType.QInt8)
        os.remove(raw_path)
    elif precision == "fp16":
        import onnx
        from onnxconverter_common import float16

        model_fp32 = onnx.load(raw_path)
        model_fp16 = float16.convert_float_to_float16(model_fp32, keep_io_types=True)
        onnx.save(model_fp16, args.out)
        os.remove(raw_path)

    print(f"Exported {args.out} [{precision}] ({os.path.getsize(args.out) / (1024*1024):.1f} MB)")


if __name__ == "__main__":
    main()
