# Getting started

This archive contains the source, the documentation and the measured results for a
privacy-preserving facial likeness matching system, built as the technical component of an MSc
dissertation on deepfake detection.

It does not contain the machine learning models, the face datasets, or any compiled binaries.
Those are fetched or built locally, for reasons given under "What is missing and why" below.

## What this system does

A person enrols by uploading several photographs of themselves. The system turns those into
face embeddings, checks they all show the same person, picks a small set that covers the most
variation, encrypts that set under the person's own public key, and stores only the ciphertext.

Later, a suspect image can be scored against the stored gallery without ever decrypting it. The
server never holds a readable face template, and the secret key never leaves the client.

## What you need

- JDK 25. Tested on Temurin 25.0.4 and GraalVM CE 21.0.2, though the Java FFM API used for the
  Lattigo binding was only finalised in JDK 22, so 21 is not enough for the whole project.
- sbt 1.12.14, pinned in `project/build.properties`.
- Go 1.26.6, to build the encryption core.
- Docker, running. The storage and enrolment tests start a `postgres:17-alpine` container.
  Postgres does not need to be installed natively.
- macOS on Apple silicon. OpenCV and FFmpeg are pulled with explicit `macosx-arm64`
  classifiers, so another platform means changing `nativePlatform` in `build.sbt`. The Lattigo
  bridge is built for macOS arm64 only and exits with an error elsewhere.
- `curl`, `unzip`, `tar` and `shasum` for fetching assets.

## Setting up

Fetch the models and the LFW dataset:

```bash
./scripts/fetch-assets.sh
```

The script is idempotent, so each step is skipped if its output already exists. It downloads the
YuNet face detector, the ArcFace `w600k_r50` recogniser, and the LFW dataset with its pairs
file, then verifies each against `models.sha256` and fails loudly on a mismatch.

Build the encryption core:

```bash
./scripts/build-lattigo.sh
```

This compiles the Go bridge into `native/build/libncii_ckks.dylib`, which the `crypto` module
loads through the Java FFM API.

If you keep the datasets somewhere other than `data/`, point the build at them:

```bash
export NCII_DATA_DIR=/Volumes/YourDrive/ncii-datasets
```

The datasets are large, so keeping them on an external drive is normal. `NCII_MODELS_DIR` and
`NCII_CKKS_LIB` work the same way if you move the models or the compiled library.

## Running the tests

```bash
sbt test
```

The full suite is 149 tests: 28 core, 24 vision, 38 crypto, 38 eval, 12 store, 9 enrolment.
Docker must be running, and `NCII_DATA_DIR` must point at the datasets.

One thing to watch. Tests that need a dataset or a model are guarded by
`assume(Assets.available(...))`, and a guarded test that cannot find its assets is reported as
skipped rather than failed. A run full of skips still exits zero and still looks green. Check
that the summary reports no `Skipped` count. If it does, the assets were not visible and those
suites verified nothing.

## Reproducing the results

Run the LFW verification protocol on its own, which takes about nine minutes:

```bash
sbt -batch lfwReport
```

Run LFW followed by the deepfake attribution stage:

```bash
sbt -batch evalReport
```

Benchmark the encrypted scoring:

```bash
sbt -batch ckksBench
```

The deepfake stage needs Celeb-DF v2, which has to be requested from its authors.
`docs/evaluation/deepfake-datasets.md` has the request instructions, the expected directory
layout, and an explanation of why the gallery images have to come from real footage rather than
manipulated footage. `scripts/prepare-celeb-df.sh` arranges a downloaded copy into the layout
the harness expects, using the official test split.

Note that `eval` is a built-in sbt command, so it shadows the project name. `eval/test` is
parsed as the eval command applied to `/test` and fails with `not found: value /`. The aliases
in `build.sbt` select the project explicitly to work around this. Plain `sbt test` is
unaffected.

## Where things live

```
core/        domain types with no dependencies: embeddings, geometry, the identity gate,
             gallery selection
vision/      face detection, alignment, embedding, quality gating
eval/        the LFW and deepfake evaluation protocols and their metrics
crypto/      CKKS encryption, the Lattigo binding, the shredding vault
store/       Postgres persistence for users, key material, galleries and audit records
enrolment/   the enrolment pipeline that ties the above together
native/      the Go source for the Lattigo bridge
scripts/     asset fetching, the Lattigo build, Celeb-DF preparation
docs/        the design spec, evaluation results and the project write-up
```

## Reading the documentation

Start with `docs/project-writeup.md`. It covers the design, how it was built, what was tested,
what the measurements showed, and where the limits are.

Then, depending on what you want:

- `docs/superpowers/specs/2026-08-08-privacy-preserving-likeness-matching-design.md` is the
  design spec the implementation argues from.
- `docs/evaluation/results-celeb-df.md` has the attribution results on Celeb-DF v2.
- `docs/evaluation/target-leakage.md` explains why attribution fails the way it does, which is
  the most interesting finding in the project.
- `docs/evaluation/ckks-throughput.md` measures what encrypted matching costs and why indexing
  cannot help.
- `docs/evaluation/improvement-experiment.md` is the pre-registered ablation, including the
  normalisation experiment that did not work.
- `store/README.md` and `enrolment/README.md` document the schema and the enrolment flow.

## What is missing and why

The models and datasets are not included. The ArcFace recogniser is 166 MB and comes with its
own licence, LFW and Celeb-DF v2 both have their own access terms, and Celeb-DF v2 in particular
must be requested from its authors rather than redistributed. `scripts/fetch-assets.sh` obtains
what can be obtained automatically, and `docs/evaluation/deepfake-datasets.md` covers the rest.

No face images, embeddings, or key material appear anywhere in this archive. That is deliberate,
and it matches the constraint the system itself is built around.

The compiled Lattigo library is not included either, since it is platform specific and rebuilt
from the Go source in `native/lattigo`.

## Current state

Three of the planned stages are built: the vision core with its evaluation harness, the CKKS
matching core, and storage with enrolment. The crawl and ingest stage was cut from the project.
The matcher and client, which is where encrypted scoring would meet the enrolled galleries, is
specified but not yet built.
