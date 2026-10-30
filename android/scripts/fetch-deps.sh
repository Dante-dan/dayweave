#!/usr/bin/env bash
# 下载 demo 需要的大文件（不进仓库）：sherpa-onnx 推理库和中英通用 CAM++ 声纹模型。
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=1.13.8
mkdir -p app/libs app/src/main/assets/speaker
[ -f app/libs/sherpa-onnx-static-link-onnxruntime-$VERSION.aar ] || \
  gh release download v$VERSION --repo k2-fsa/sherpa-onnx \
    -p "sherpa-onnx-static-link-onnxruntime-$VERSION.aar" -D app/libs
[ -f app/src/main/assets/speaker/campplus.onnx ] || {
  gh release download speaker-recongition-models --repo k2-fsa/sherpa-onnx \
    -p "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx" -O app/src/main/assets/speaker/campplus.onnx
}
echo "deps ready"
