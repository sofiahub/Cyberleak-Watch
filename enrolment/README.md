# Enrolment module

The enrolment module takes uploaded photographs, derives face embeddings, and stores them
encrypted. It implements section 5 of the design spec. The order of the steps is what guarantees
no plaintext media survives.

## The enrolment flow

`EnrolmentPipeline.run` executes these steps in order.

1. Open a vault, a temporary directory for encrypted uploads.
2. Encrypt every photograph into the vault before anything else reads it.
3. Read bytes back from the vault and decode them in memory. No image data reaches disk.
4. Detect faces with YuNet and derive embeddings with ArcFace.
5. Drop unusable faces at the quality gate.
6. Reject the set if it contains more than one person.
7. Select the most mutually distant embeddings for the gallery.
8. Encrypt the selection under the user's CKKS public key.
9. Shred the vault, in a `finally` block.

Step 9 is the safety mechanism. Because it sits in a `finally`, it runs on every path including
an exception, so a failure part way through cannot leave readable media behind. A `finally` only
ever exercised on the happy path is untested, so the test
`"the vault is shredded even when decoding fails part-way"` drives the failure path on purpose.

## The decision is pure and separate from I/O

`EnrolmentPipeline.decide` takes a sequence of embeddings and returns a decision. It performs no
I/O, holds no vault, touches no database, and needs neither a container nor a model.

That buys two things. The decision logic can be tested with synthetic embeddings in
milliseconds, and all the reasoning about mixed identity sits in one small function instead of
being tangled through the plumbing.

The I/O around it, meaning the vault, the vision pipeline, the CKKS context and the database
writes, is assembled in `enrol` and `enrolAndStore`, which call `decide` at the right point.

## Decoding happens in memory

Decrypted photographs must not be written to a temporary file. Doing so would put plaintext media
back on disk and make the privacy claim false.

`Images.decode`, in `vision/src/main/scala/ncii/vision/FaceDetector.scala`, exists for this
reason. It decodes encoded image bytes straight to an OpenCV matrix without touching the
filesystem.

This is worth documenting because the shortcut looks harmless. Writing the bytes to
`Files.createTempFile` and calling the path-based decoder is shorter and appears obviously
correct. It would also break the guarantee while leaving every test green, because the shredding
code still runs. It would just be shredding the vault while a decoded copy sat elsewhere on disk.

## Identity consistency

Every photograph in a set must show the same person, or the enrolment is rejected. Two tests run,
and failing either one rejects the set.

The centroid test is the primary check. It takes the mean embedding and measures each
photograph's similarity to it. A legitimate single-person set clusters tightly, with a minimum
similarity around 0.714. The threshold is 0.35, so there is about 0.36 of margin.

The pairwise test is the secondary check, and it exists because the centroid has a blind spot.
With two equally sized groups the centroid sits between them, so every member looks acceptable
against the mean. This test takes the worst pairwise similarity in the set instead. A legitimate
set reaches about 0.404, an even split falls to about 0.00, and a lone intruder sits near -0.05.
The threshold is 0.175, which leaves roughly 0.225 of margin below the legitimate floor while
staying well clear of the failure cases.

### Two caveats

The outlier count means little below about five photographs. That count is what appears in a
rejection reason, such as "2 photographs show a different person". At the specified minimum of
three photographs, one intruder barely shifts the centroid, and in measurement it was flagged in
only 2 of 12 random draws. The set is still rejected, but by the pairwise check, which reports
zero outliers. Since "0 photographs show a different person" would be a strange thing to tell
someone whose enrolment was refused, that case has its own wording: "the photographs do not all
show the same person". See `EnrolmentPipeline.decide`.

The thresholds came from synthetic data. Both 0.35 and 0.175 were measured on synthetic isotropic
gaussian vectors rather than real ArcFace embeddings, whose geometry differs. They should be
validated against the LFW data in this repository before any published result depends on them.
This is an open limitation, not a footnote. Any deployment should measure its own rejection and
false-acceptance rates against real enrolment photographs.

## The privacy claim

After enrolment, no plaintext media survives: not in the vault, not anywhere else on disk.

The test asserting this is `"a real enrolment leaves no plaintext media behind"` in
`src/test/scala/ncii/enrolment/EnrolmentEndToEndSuite.scala`. It:

1. runs a real enrolment over five LFW photographs
2. checks no files remain under the vault directory
3. reads whatever bytes do remain and looks for a JPEG header (`0xFF 0xD8`, which reads as "ÿØÿ"
   in Latin-1)
4. looks for the first photograph's own leading bytes
5. confirms the shredded vault refuses further reads

It runs the real pipeline, with the real models and a real CKKS context, and that matters. An
earlier version stored and shredded a vault by hand without ever calling `enrol`, which only
retested `ShreddingVault`. The temp-file decode described above would have passed it without
complaint. The test has to keep driving the actual pipeline to be worth anything.

## Testing

Tests bring up a `postgres:17-alpine` container through testcontainers, so Docker must be running.

Some tests need the LFW dataset and the ArcFace model, and are guarded by
`assume(Assets.available(...))`. A guarded test that cannot find its assets is reported as
skipped, and a skipped test is not a passing test. Run the suite with `NCII_DATA_DIR` pointing at
the data drive and check that the skip count is zero, otherwise those suites verified nothing.

This module forks its test JVM and sets `Test / envVars`, which stops the forked process
inheriting the ambient environment. `NCII_DATA_DIR`, `NCII_MODELS_DIR` and `NCII_CKKS_LIB` are
forwarded explicitly in `build.sbt` for that reason. Anyone adding another forked module needs to
do the same.

Run the tests with `sbt enrolment/test`, or `sbt test` for the whole build.
