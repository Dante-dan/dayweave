#!/usr/bin/env bash
# 下载 App 需要的大文件（不进仓库）：sherpa-onnx 推理库、中英通用 CAM++ 声纹模型、Silero 人声检测模型。
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=1.13.8
mkdir -p app/libs app/src/main/assets/speaker app/src/main/assets/vad
[ -f app/libs/sherpa-onnx-static-link-onnxruntime-$VERSION.aar ] || \
  gh release download v$VERSION --repo k2-fsa/sherpa-onnx \
    -p "sherpa-onnx-static-link-onnxruntime-$VERSION.aar" -D app/libs
[ -f app/src/main/assets/speaker/campplus.onnx ] || {
  gh release download speaker-recongition-models --repo k2-fsa/sherpa-onnx \
    -p "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx" -O app/src/main/assets/speaker/campplus.onnx
}
[ -f app/src/main/assets/vad/silero_vad.onnx ] || \
  gh release download asr-models --repo k2-fsa/sherpa-onnx -p "silero_vad.onnx" -O app/src/main/assets/vad/silero_vad.onnx
echo "deps ready"
