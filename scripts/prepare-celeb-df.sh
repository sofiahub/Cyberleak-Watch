#!/usr/bin/env bash
# Reshapes an extracted Celeb-DF (v2) tree into the layout the match-rate harness expects:
#
#     <out>/<identity>/gallery/*.jpg   real photographs of that person
#     <out>/<identity>/fake/*.mp4      manipulated videos impersonating that person
#
# Usage:
#   scripts/prepare-celeb-df.sh <celeb-df-root> <output-root> [options]
#
#   --split test|dev   which synthesis videos to prepare (default: test)
#                        test = the 340 videos in List_of_testing_videos.txt
#                        dev  = synthesis videos NOT in that list
#   --sample N         cap the number of videos (dev only; default 500, 0 = no cap)
#   --seed S           seed for the dev sample (default 20260818)
#
# Two decisions are baked in, both load-bearing; see
# docs/evaluation/deepfake-datasets.md for the evidence behind each.
#
# 1. DIRECTION. Celeb-synthesis files are named id{A}_id{B}_{NNNN}.mp4 where A is the
#    TARGET whose footage was manipulated and B supplied the FACE that appears in the
#    output. A fake therefore belongs to B, the person being impersonated, and the one
#    a likeness matcher should flag. Established structurally over all 5639 files:
#    id{A}'s real video exists for 100% of them, and among the 149 discriminating cases
#    the asymmetry is 149 to 0.
#
# 2. SPLIT DISCIPLINE. The test split is the reported evaluation set and must be touched
#    only to produce a final number. All tuning happens on the dev split, the synthesis
#    videos outside List_of_testing_videos.txt, per
#    docs/evaluation/improvement-experiment.md. Both splits are prepared by this same code
#    path so that a dev-to-test comparison measures the intervention rather than a
#    difference in how the data was assembled.
#
# Gallery frames come exclusively from Celeb-real, never Celeb-synthesis: frames taken
# from a manipulated video would measure whether the system can match a video to itself.
# Real videos that appear in the TEST split are excluded from galleries for both splits,
# so enrolment material never overlaps the reported evaluation set.
set -euo pipefail

ROOT="${1:?usage: prepare-celeb-df.sh <celeb-df-root> <output-root> [--split test|dev]}"
OUT="${2:?usage: prepare-celeb-df.sh <celeb-df-root> <output-root> [--split test|dev]}"
shift 2

SPLIT_KIND=test
SAMPLE=500
SEED=20260818

while [ $# -gt 0 ]; do
  case "$1" in
    --split) SPLIT_KIND="${2:?--split needs a value}"; shift 2 ;;
    --sample) SAMPLE="${2:?--sample needs a value}"; shift 2 ;;
    --seed) SEED="${2:?--seed needs a value}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

case "$SPLIT_KIND" in test|dev) ;; *) echo "--split must be test or dev" >&2; exit 1 ;; esac

REAL="$ROOT/Celeb-real"
SYN="$ROOT/Celeb-synthesis"
SPLIT="$ROOT/List_of_testing_videos.txt"

for p in "$REAL" "$SYN" "$SPLIT"; do
  [ -e "$p" ] || { echo "missing: $p" >&2; exit 1; }
done
command -v ffmpeg >/dev/null || { echo "ffmpeg not found on PATH" >&2; exit 1; }

GALLERY_VIDEOS_PER_ID=4   # how many real videos to draw gallery frames from
FRAMES_PER_VIDEO=3        # frames taken from each, one every 3 seconds

mkdir -p "$OUT"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# --- Which synthesis videos are in the official test split ------------------------------
# NOTE the `|| [ -n "$path" ]`: List_of_testing_videos.txt has no trailing newline, so a
# plain `while read` silently discards its final entry and prepares 339 of 340.
: > "$work/test-syn.txt"
: > "$work/test-real.txt"
while read -r _label path || [ -n "$path" ]; do
  case "$path" in
    Celeb-synthesis/*) basename "$path" >> "$work/test-syn.txt" ;;
    Celeb-real/*)      basename "$path" >> "$work/test-real.txt" ;;
  esac
done < "$SPLIT"

# --- Build the candidate list for the requested split -----------------------------------
if [ "$SPLIT_KIND" = test ]; then
  cp "$work/test-syn.txt" "$work/candidates.txt"
else
  # Every synthesis video NOT in the test split.
  find "$SYN" -name '*.mp4' -exec basename {} \; | sort > "$work/all-syn.txt"
  sort "$work/test-syn.txt" > "$work/test-syn-sorted.txt"
  comm -23 "$work/all-syn.txt" "$work/test-syn-sorted.txt" > "$work/candidates.txt"

  if [ "$SAMPLE" -gt 0 ]; then
    # Deterministic sample: order by a checksum of "seed:name", take the first N. Uses
    # cksum rather than shuf/sort -R so the selection is reproducible across machines and
    # does not depend on GNU coreutils being present.
    while read -r name; do
      h=$(printf '%s' "$SEED:$name" | cksum | awk '{print $1}')
      printf '%s\t%s\n' "$h" "$name"
    done < "$work/candidates.txt" > "$work/hashed.txt"
    # `head` reads a FILE here rather than a live pipe on purpose: piping sort into head
    # makes head close the pipe once satisfied, sort takes SIGPIPE, pipefail turns that
    # into a failed pipeline and set -e exits, silently, with status 0, leaving an empty
    # dev set that looks like success.
    sort -n -k1,1 "$work/hashed.txt" > "$work/sorted.txt"
    head -n "$SAMPLE" "$work/sorted.txt" | cut -f2 > "$work/sampled.txt"
    mv "$work/sampled.txt" "$work/candidates.txt"
  fi
fi

total_candidates=$(wc -l < "$work/candidates.txt" | tr -d ' ')

echo "Preparing $SPLIT_KIND split: $total_candidates videos"
[ "$SPLIT_KIND" = dev ] && echo "  sample cap $SAMPLE, seed $SEED"

# --- Link fakes under the impersonated identity -----------------------------------------
fake_count=0
: > "$work/identities.txt"
while read -r name; do
  base=${name%.mp4}                      # id{A}_id{B}_{NNNN}
  rest=${base#*_}                        # id{B}_{NNNN}
  impersonated=${rest%%_*}               # id{B}  <- the face that appears

  mkdir -p "$OUT/$impersonated/fake"
  ln -sf "$SYN/$name" "$OUT/$impersonated/fake/$name"
  fake_count=$((fake_count + 1))
  echo "$impersonated" >> "$work/identities.txt"
done < "$work/candidates.txt"

sort -u "$work/identities.txt" > "$work/identities-uniq.txt"
id_count=$(wc -l < "$work/identities-uniq.txt" | tr -d ' ')
echo "  linked $fake_count fakes across $id_count identities"

# --- Gallery frames from Celeb-real, excluding test-split reals --------------------------
echo "Extracting gallery frames from Celeb-real (excluding test-split reals)..."
while read -r id; do
  gdir="$OUT/$id/gallery"
  mkdir -p "$gdir"

  used=0
  for v in "$REAL/${id}_"*.mp4; do
    [ -e "$v" ] || continue
    name=$(basename "$v")
    grep -qx "$name" "$work/test-real.txt" && continue
    [ "$used" -ge "$GALLERY_VIDEOS_PER_ID" ] && break

    ffmpeg -nostdin -loglevel error -i "$v" \
      -vf fps=1/3 -frames:v "$FRAMES_PER_VIDEO" -q:v 2 \
      "$gdir/${name%.mp4}_%02d.jpg" </dev/null
    used=$((used + 1))
  done

  n=$(find "$gdir" -name '*.jpg' | wc -l | tr -d ' ')
  if [ "$n" -eq 0 ]; then
    echo "  WARNING: $id has no gallery images, every real video is in the test split," \
         "or none exist. Its fakes cannot be scored and the harness will skip it." >&2
  fi
done < "$work/identities-uniq.txt"

echo
echo "Prepared $SPLIT_KIND split under $OUT"
echo "  identities : $id_count"
echo "  fakes      : $fake_count"
echo "  gallery    : $(find "$OUT" -name '*.jpg' | wc -l | tr -d ' ') frames"
[ "$SPLIT_KIND" = dev ] && echo "  selection  : seed $SEED, cap $SAMPLE (reproducible)"
exit 0
