#!/usr/bin/env bash
# Builds the Lattigo CKKS bridge as a C-shared library.
#
# The output is a native binary and is NOT committed; every checkout builds its own.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/native/lattigo"
OUT="$ROOT/native/build"

os=$(uname -s)
arch=$(uname -m)
if [ "$os" != "Darwin" ] || [ "$arch" != "arm64" ]; then
  echo "This project pins native artifacts to macosx-arm64; found $os/$arch." >&2
  echo "Building anyway would produce a library the FFM binding cannot load." >&2
  exit 1
fi

command -v go >/dev/null || { echo "go not found on PATH" >&2; exit 1; }

mkdir -p "$OUT"
cd "$SRC"
go build -buildmode=c-shared -o "$OUT/libncii_ckks.dylib" .

echo "Built $OUT/libncii_ckks.dylib"
ls -lh "$OUT/libncii_ckks.dylib"
