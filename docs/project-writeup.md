# Privacy-preserving likeness matching: project write-up

Every figure below was measured. Sources are given per section.

## Design

- The problem: detect deepfake NCII by matching media against a victim's enrolled likeness,
  without the server ever holding a readable face template.
- The server stores only ciphertext. Scoring runs on encrypted embeddings, and the secret key
  stays on the client. There is no secret-key column in the schema and no method to store one.
- Faces are represented as ArcFace `w600k_r50` embeddings: 512 dimensions, L2-normalised.
  Cosine similarity is the score.
- Encryption uses CKKS via Lattigo v6.2.0. CKKS was chosen because it computes on approximate
  reals and supports the rotate-and-sum operation an inner product needs.
- Uploaded photographs are encrypted into a vault under an AES-256 key that exists only in
  memory, then crypto-shredded once enrolment finishes. Shredding the key rather than
  overwriting the files matters because on SSDs and copy-on-write filesystems, overwriting in
  place does not reliably destroy data. Wear levelling can preserve the original blocks.
- UK possession of illegal material is largely strict liability, so any crawl stage was scoped
  to run only against a self-hosted mock corpus. That stage was later cut from the project on
  22 August 2026. The design spec still describes it.
- One structural limit was accepted rather than solved. Approximate nearest-neighbour indexing
  such as HNSW or IVF cannot work here, because those methods need plaintext geometry to
  partition the space and every user's gallery sits under a different public key. Cost is
  therefore linear in the number of enrolled users.

## Development

- The work was planned in five stages. Three were built: the vision core and evaluation
  harness, the CKKS matching core, and the store with enrolment. The crawler was cut. The
  matcher and client are neither planned nor built.
- Scala 3.8.4, JDK 25, sbt 1.12.14. Modules are `core`, `vision`, `eval`, `crypto`, `store` and
  `enrolment`, plus a Go and Lattigo bridge reached through a C shared library and Java FFM.
- Each task went to a fresh implementer, then through a review covering both spec compliance
  and code quality, with test counts checked independently before the task was closed. The
  branch holds 85 commits.
- One process observation is worth recording. Almost every defect found in stage three came
  from the plan document rather than the implementation. The implementers transcribed what they
  were given. The recurring problems were assertions that could not fail, unused type
  parameters, and guards written with `require`, which compiles away under
  `-Xdisable-assertions`.

## Implementation

- YuNet (`FaceDetectorYN`) handles detection. Alignment uses the Umeyama closed-form similarity
  transform with a determinant check to prevent reflection. The first version called
  `estimateAffinePartial2D`, which silently defaults to RANSAC. That version was
  non-deterministic and drifted 8.389px against an 8px expectation, which would have made the
  evaluation impossible to reproduce.
- `FaceSelection.centreMost` picks the face nearest the centre of the image. Using the
  detector's first face instead gave an LFW equal error rate of 4.93% against a published 0.2%,
  because 19.3% of LFW images contain more than one face. Both `eval` and `enrolment` call the
  same implementation. If they disagreed, the measured thresholds would not describe what
  enrolment actually stores.
- CKKS parameters: ring degree 16384, 8192 slots, scale 2^40, 512-slot blocks, 16 blocks per
  ciphertext, 9 rotations for the halving sum, LogQ [55,45,45,45] and LogP [61].
- Postgres 17 through Doobie, with four tables: enrolled user, key material, encrypted gallery
  and enrolment audit. The audit table has no `bytea` column, so it cannot hold biometric data
  even by accident. A rejected enrolment leaves that record and nothing else.
- Enrolment runs nine steps and shreds the vault in a `finally`, so shredding happens on every
  path including failure. Images are decoded in memory through `Images.decode` and `imdecode`.
  Writing decrypted bytes to a temporary file would put plaintext media back on disk and make
  the privacy claim false.
- The identity gate uses two thresholds and both do work. A centroid test at 0.35 catches a
  single intruder. A pairwise test at 0.175 catches an even split between two people, which the
  centroid cannot see because it sits between the two clusters.

## Testing

- The suite is 149 tests with zero skips: 38 crypto, 38 eval, 28 core, 24 vision, 12 store and
  9 enrolment.
- Store tests run against a real `postgres:17-alpine` container through testcontainers. Vision
  and eval tests run against real LFW and Celeb-DF v2 data.
- Assertions were checked by breaking the code and confirming the right test failed:
  - renaming an enum case throughout failed only the wire-format test
  - giving every vault the same key failed only the key-separation test
  - disabling the pairwise threshold failed only the even-split tests
  - reverting the shred sweep failed the subdirectory test with `DirectoryNotEmptyException`
- A skipped test reports no failure, so it reads as a pass. Several runs during development
  looked green while whole suites had not executed. In one case the end-to-end enrolment suite
  skipped because setting `Test / envVars` stops a forked JVM from inheriting the environment,
  and once those tests ran they failed. Counts are now verified with `NCII_DATA_DIR` set and a
  zero skip count confirmed.
- One environment defect was fixed rather than worked around. Testcontainers reports a container
  as started once its port is mapped, but the postmaster is not yet answering, so the first
  unpooled connection raced it. The symptom moved between suites from run to run, which made it
  look like machine noise.

## Results

### Verification on LFW

Source: `docs/evaluation/results-celeb-df.md`.

| metric | value |
|---|---|
| Accuracy (10-fold CV) | 0.9985 ± 0.0007 |
| AUC | 0.9993 |
| EER | 0.0023 at threshold 0.1911 |
| TAR at FAR 1e-3 | 0.9973 at threshold 0.2524 |

### Attribution on Celeb-DF v2

Official test split: 340 videos across 52 identities, at an operating threshold of 0.2524, the
cosine at which LFW gives a false-accept rate of 1e-3.

- Own-identity match rate is 98.8%. Manipulated videos do score above threshold for the person
  depicted.
- Rank-1 identification is 58.2%. In 42% of cases another enrolled identity scores higher.
- Top-2 is 94.1%, top-3 is 96.8%, top-5 is 98.2% and top-10 reaches 100%.
- 85.9% of rank-1 misses sit at exactly rank 2. That is one systematic competitor displacing the
  right answer, not scattered noise.
- Mean own-identity score is 0.4542 and mean best-other score is 0.4145, so the mean margin is
  0.0397. That is roughly 22 times narrower than at LFW scale. Deepfake embeddings sit in a
  compressed band above the threshold that barely separates one person from another.
- Another identity outscores the true one for 19 of 52 identities (36.5%), covering 131 of 340
  videos (38.5%).

### Target leakage

Source: `docs/evaluation/target-leakage.md`.

- When attribution fails, the winner is almost always the swap target: 92.25% of errors, against
  1.96% expected by chance.
- The target outranks the face donor in 216 of 500 videos (43.2%).
- The cause is mechanical. A face swap superimposes one identity on another rather than
  replacing it, and the output reads as a blend weighted toward the target.

### Cost of encrypted matching

Source: `docs/evaluation/ckks-throughput.md`.

| operation | time |
|---|---|
| Key generation | 123.09 ms |
| Gallery encryption, one vector | 7.72 ms |
| Single score | 64.48 ms |
| Batched score, 16 queries | 64.11 ms |
| Decryption | 11.45 ms |

- Scoring one face against 1,000 users takes 64.1 seconds. Against 10,000 users it takes 641
  seconds, about 10.7 minutes.
- Batching packs 16 queries into one multiply, but only within a single user's key. It raises
  throughput for a queue of queries without reducing the latency of one face against a large
  enrolment.
- Key material runs to about 48.5 MB per user, with Galois keys alone at 47,190,894 bytes. At one
  million users that is roughly 48.5 TB before storing any galleries.

### Pre-registered improvement experiment

Score normalisation (E1) was not adopted. GalleryZ fell short of the pre-registered two-point bar
and is recorded as having no effect. E2 to E4 were dropped. The negative result is kept in the
record rather than removed.

## Evaluation

- The headline claim needs restating. Calling attribution unreliable at 58% is the wrong summary.
  Detection works, with 98.8% of manipulated videos scoring above threshold. What degrades is
  ranking, and it degrades in a structured, explainable way: 94.1% at top-2, with the competitor
  identifiable as the swap target. A system that surfaces two candidates is defensible. One that
  names a single person is not.
- Deployed risk is worse than the LFW false-accept rate suggests. At a threshold calibrated for
  FAR 1e-3 on LFW, a margin of 0.0397 on deepfake footage means misattribution is common.
  Reporting the 98.8% match rate on its own would overstate what the system can do.
- The privacy guarantee holds within a stated bound. No plaintext media or embedding is written
  to disk, and the vault key never reaches disk. However, `SecretKeySpec` clones the key array
  and `Cipher` copies key material internally, and neither copy is zeroed. The guarantee is that
  the key is never written to disk, not that it is erased from memory against an attacker who can
  read the running process.
- The throughput ceiling is structural rather than a tuning problem. Per-user keys rule out both
  cross-user batching and server-side indexing. A deployment has to either shard by candidate set
  or accept linear cost.

### Known limitations

- The identity-gate thresholds of 0.35 and 0.175 were derived from synthetic isotropic gaussian
  vectors, not from real ArcFace embeddings, whose geometry differs. They should be validated
  against the LFW data in this repository before any published result depends on them.
- The outlier count in a rejection reason only means something once there are about five
  legitimate photographs. At the specified minimum of three, the centroid barely moves away from
  an intruder and flagged it in only 2 of 12 random draws. The set is still rejected, but by the
  pairwise check.
- Deepfake evaluation rests on Celeb-DF v2 alone. FaceForensics++ access was requested but not
  granted, so generalisation across manipulation methods is untested.
- The matcher and client do not exist, so there is no end-to-end accuracy or latency figure for
  the deployed path, only component measurements.
- The crawl and ingest stage was cut, so there is no ingest or alerting evaluation.
