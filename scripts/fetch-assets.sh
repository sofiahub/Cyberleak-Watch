#!/usr/bin/env bash
# Downloads models and evaluation datasets into gitignored directories.
# Safe to re-run: every step is skipped if its output already exists.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS="$ROOT/models"
DATA="$ROOT/data"
mkdir -p "$MODELS" "$DATA"

YUNET_URL="https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
BUFFALO_URL="https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"

# LFW's original home, vis-www.cs.umass.edu, no longer resolves (NXDOMAIN, not 404
#, the host itself is gone). These are the figshare mirrors scikit-learn's
# sklearn.datasets.fetch_lfw_pairs has used since the same outage: 5976018 is the
# unfunneled lfw.tgz, 5976006 is the 10-fold pairs.txt.
LFW_URL="https://ndownloader.figshare.com/files/5976018"
LFW_PAIRS_URL="https://ndownloader.figshare.com/files/5976006"

fetch() {
  local url="$1" dest="$2"
  if [ -f "$dest" ]; then
    echo "skip  $(basename "$dest") (already present)"
  else
    echo "fetch $(basename "$dest")"
    curl --fail --location --progress-bar --output "$dest.partial" "$url"
    mv "$dest.partial" "$dest"
  fi
}

fetch "$YUNET_URL" "$MODELS/face_detection_yunet_2023mar.onnx"

if [ ! -f "$MODELS/w600k_r50.onnx" ]; then
  fetch "$BUFFALO_URL" "$MODELS/buffalo_l.zip"
  unzip -o -j "$MODELS/buffalo_l.zip" '*w600k_r50.onnx' -d "$MODELS"
  rm -f "$MODELS/buffalo_l.zip"
fi

fetch "$LFW_PAIRS_URL" "$DATA/pairs.txt"

if [ ! -d "$DATA/lfw" ]; then
  fetch "$LFW_URL" "$DATA/lfw.tgz"
  tar -xzf "$DATA/lfw.tgz" -C "$DATA"
  rm -f "$DATA/lfw.tgz"
fi

echo
cd "$ROOT"
if [ -f models.sha256 ]; then
  echo "Verifying model weights against the committed manifest:"
  if shasum -a 256 -c models.sha256; then
    echo "Assets ready."
  else
    echo
    echo "CHECKSUM MISMATCH. The downloaded weights differ from the ones the reported"
    echo "evaluation results were produced with. Upstream has republished a file, or a"
    echo "download was truncated. Do not treat results from these weights as comparable"
    echo "to the figures in README.md until this is resolved."
    exit 1
  fi
else
  echo "No models.sha256 manifest found; recording current checksums:"
  shasum -a 256 models/*.onnx | tee models.sha256
fi
