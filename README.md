# ncii-likeness-matching: vision core and evaluation

Facial likeness matching for detecting non-consensual intimate imagery, including
synthetic imagery. A person enrols ordinary photographs, the system derives an identity
embedding, and material found later can be matched against it without retaining the
original uploads.

This repository is **plan 1 of 5**: the detection and embedding pipeline, plus the
evaluation that demonstrates it works. Encrypted matching (CKKS), storage and enrolment,
the crawler, and the matcher/client are separate later plans and are deliberately absent.

Design rationale: [`docs/superpowers/specs/2026-08-08-privacy-preserving-likeness-matching-design.md`](docs/superpowers/specs/2026-08-08-privacy-preserving-likeness-matching-design.md)
Implementation plan: [`docs/superpowers/plans/2026-08-10-vision-core-and-evaluation.md`](docs/superpowers/plans/2026-08-10-vision-core-and-evaluation.md)

## No data is committed to this repository

Model weights, datasets and any face image are gitignored and must be fetched locally.
This is deliberate: the project handles biometric data, which is special-category personal
data under UK GDPR Article 9, and a repository is the wrong place for it. Everything
excluded can be re-obtained with the steps below.

## Prerequisites

- **JDK 25 (LTS).** Verified on Temurin 25.0.4 and previously on GraalVM CE 21.0.2. The
  full suite passes on both. The Java FFM API, needed from plan 2 onward for the Lattigo
  binding, was finalised in JDK 22, so 21 is no longer sufficient for the whole project.
- **Go 1.26.6.** Required to build the CKKS encrypted matching core (plan 2, `crypto/`
  module). The native binary `native/build/libncii_ckks.dylib` is not committed and must
  be built locally using `./scripts/build-lattigo.sh`.
- **Docker.** The `store` and `enrolment` modules' tests start a `postgres:17-alpine`
  container. Docker must be running. Postgres is not required to be installed natively.
- **sbt 1.12.14** (pinned in `project/build.properties`).
- **macOS arm64.** Native OpenCV/FFmpeg artifacts are pulled with explicit `macosx-arm64`
  classifiers rather than the platform bundle, so building on another platform means
  changing `nativePlatform` in `build.sbt`. The Lattigo bridge is built only for macOS
  arm64 and will exit with an error on other platforms.
- `curl`, `unzip`, `tar`, `shasum` for asset fetching.

## Fetching the models and datasets

```bash
./scripts/fetch-assets.sh
```

Idempotent, so each step is skipped if its output already exists. It retrieves:

| Asset | Into | Source |
|---|---|---|
| YuNet face detector | `models/face_detection_yunet_2023mar.onnx` | OpenCV Zoo |
| ArcFace `w600k_r50` | `models/w600k_r50.onnx` | InsightFace `buffalo_l` release |
| LFW images | `data/lfw/` (5749 identities, 13233 images) | figshare mirror |
| LFW pair protocol | `data/pairs.txt` (6001 lines) | figshare mirror |

The script then verifies both models against `models.sha256` and **fails loudly** on a
mismatch. Those checksums pin the exact weights that produced the results below; if
upstream republishes a file, the reported figures no longer describe the model you have.

**Note on the LFW source.** The dataset's original host, `vis-www.cs.umass.edu`, no longer
resolves: NXDOMAIN on both public and ISP resolvers, while the parent `cs.umass.edu`
still resolves. The host is gone, not merely returning 404. The script uses the figshare
mirrors that `sklearn.datasets.fetch_lfw_pairs` switched to, verified to contain the
canonical 5749/13233 counts and the standard 10×600 pair file.

## Keeping datasets off the internal drive

`ncii.vision.Assets` resolves every dataset path from a single root, overridable with
`NCII_DATA_DIR` (and models likewise with `NCII_MODELS_DIR`). To keep the corpora on an
external drive:

```bash
export NCII_DATA_DIR=/Volumes/YourDrive/ncii-datasets
sbt test
sbt -batch evalReport
```

The root must then contain `lfw/`, `pairs.txt` and, once obtained, `deepfake/`, i.e. the
same layout `fetch-assets.sh` would have produced under `data/`.

Note this is all-or-nothing: the variable moves the LFW paths as well as the deepfake root.
If it points somewhere without LFW, the LFW-dependent tests **skip rather than fail**, and
`sbt test` will still report all green with those tests silently absent. Check for
`0 ignored` in the output to confirm they actually ran.

## Deepfake corpus (not yet obtained)

The match-rate experiment needs Celeb-DF (v2) and FaceForensics++, both of which require
access requests with turnaround measured in weeks. See
[`docs/evaluation/deepfake-datasets.md`](docs/evaluation/deepfake-datasets.md) for the
request links, the expected directory layout, and, importantly, why gallery images must
be real photographs rather than frames lifted from the manipulated videos.

Until that corpus is present, the deepfake stage skips with an explanatory message. The
harness itself is complete and tested.

## Running

To include the crypto module (plan 2), build the Lattigo bridge first:

```bash
./scripts/build-lattigo.sh   # Builds native/build/libncii_ckks.dylib
```

This script requires Go 1.26.6 on macOS arm64. It exits with a clear error message on
other platforms or when Go is not found. The native binary is not committed and must be
rebuilt after every clean checkout.

Then run the full test suite:

```bash
sbt test            # full suite: 28 core + 24 vision + 38 crypto + 38 eval + 12 store + 9 enrolment = 149 tests
sbt -batch lfwReport   # LFW verification protocol only (~9 min)
sbt -batch evalReport  # LFW, then the deepfake stage
```

Tests that need models, LFW data, or the Lattigo bridge skip cleanly when those are
absent, rather than failing. If you do not need the crypto module, omit the build step
and the suite will skip its 30 tests with a message naming the build script.

**Why the aliases exist.** `eval` is a built-in sbt command (`eval <scala expr>`), so it
shadows the project selector: `eval/run` is parsed as the eval command applied to `/run`
and dies with `not found: value /`. The aliases in `build.sbt` select the project
explicitly to sidestep this. Plain `sbt test` is unaffected.

**A developer trap with forked tests.** Setting `Test / envVars` in `build.sbt` stops a
forked test JVM from inheriting the ambient environment. `enrolment` sets it for
testcontainers, so `NCII_DATA_DIR`, `NCII_MODELS_DIR` and `NCII_CKKS_LIB` must be
forwarded explicitly as absolute paths (see the enrolment module in `build.sbt`), or
tests that depend on them will skip silently and report passing. When adding a forked
module, forward the variables explicitly. To check that nothing skipped, run the suite
with `NCII_DATA_DIR` set and confirm the summary line reports no `Skipped` count. A
guarded test whose assets are missing is reported as skipped, not failed, so a run full
of skips still looks green.

## Measured results

LFW verification, full 6000-pair protocol, 0 pairs skipped:

```
Accuracy (10-fold CV) : 0.9985 ± 0.0007
AUC                   : 0.9993
EER                   : 0.0023 at threshold 0.1911
TAR @ FAR 1e-3        : 0.9973 at threshold 0.2524
TAR @ FAR 1e-4        : N/A (need 10000 impostor pairs; have 3000)
```

Published figures for ArcFace `w600k_r50` are approximately 99.8% accuracy and 0.2% EER,
so the pipeline reproduces the model's known behaviour rather than merely running.

Accuracy uses the standard protocol: ten folds of 600 pairs, each fold's threshold chosen
on the other nine and applied to the held-out fold, reported as mean ± standard error.
AUC, EER and TAR are pooled statistics over all 6000 pairs.

**TAR @ FAR = 1e-4 is not reported because it cannot be measured on LFW.** A false-accept
rate resolves no more finely than one impostor pair out of the total, and the standard
protocol supplies 3000 impostor pairs, a floor of 1/3000 or about 3.33e-4. Reaching 1e-4 needs at
least 10,000 impostor pairs, i.e. an all-pairs (BLUFR-style) impostor set over the 13,233
images. `Metrics.tarAtFar` rejects unresolvable requests rather than returning a
plausible-looking number.

## Deepfake match-rate result

Against the Celeb-DF (v2) official test split, 340 manipulated videos over 52 identities:

```
own-identity match rate  : 0.9882   (score >= 0.2524, the LFW FAR 1e-3 threshold)
rank-1 identification    : 0.5824   (own identity ranks highest of 52)
top-2 identification     : 0.9412
top-10 identification    : 1.0000
```

Detection works. Rank-1 attribution does not, but the true identity is never lost, only
displaced, and by one specific competitor. In 92.3% of misrankings the winner is the
**target**: the person whose footage was manipulated rather than the person whose face was
swapped in. A face swap superimposes one identity on another instead of replacing it.

Mapped onto the threat model, the victim is detected while the performer whose footage was
used outranks them, so a two-candidate shortlist recovers 94.1%, and the correct identity
never falls outside the top ten of fifty-two.

Full analysis in [`docs/evaluation/results-celeb-df.md`](docs/evaluation/results-celeb-df.md)
and [`docs/evaluation/target-leakage.md`](docs/evaluation/target-leakage.md).

## Known limitations

- `QualityGate` sharpness is variance-of-Laplacian on the original-resolution crop, so the
  threshold is scale-dependent: a large blurry face and a small sharp one are not judged on
  equal terms. `MinSharpness = 40` is calibrated against LFW and will need recalibrating
  against rougher crawl imagery.
- The ten-fold protocol requires exactly 6000 scored pairs; if any pair is skipped it falls
  back to a single global threshold, which is labelled as such in the output but is
  optimistic and not comparable to published figures.
- `LfwProtocol` decodes each image twice per pair: once for embedding, once to read
  dimensions for centre-face selection.
- Video frame accumulation is unbounded, so very long clips grow memory linearly.
- FaceForensics++ per-generator breakdown is not yet exposed.

## Module layout

```
core/    dependency-free domain types: Embedding (L2-normalised by construction), geometry
vision/  YuNet detection, Umeyama alignment, ArcFace embedding, quality gate, video sampling and tracking
eval/    ROC/AUC/TAR/EER metrics, the LFW protocol, the deepfake match-rate protocol
```

`Embedding`'s constructor is private and every factory normalises, so an unnormalised
embedding cannot be constructed anywhere in the codebase.
