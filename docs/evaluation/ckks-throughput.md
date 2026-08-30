# CKKS Encrypted Matching: Throughput Benchmark and Findings

## Summary

The CKKS homomorphic encryption scheme used for privacy-preserving face matching has a throughput ceiling that is linear in the number of enrolled users. Standard approximate nearest-neighbor indexing (HNSW, IVF) cannot reduce this because those methods require access to plaintext geometry for partitioning. This document measures the concrete cost and explores its implications for deployment.

## Hardware and environment

- **Machine**: MacBook Pro, Apple M4 Max CPU (12 cores)
- **OS**: macOS 14.7.1
- **JDK**: Eclipse Adoptium Java 25.0.4
- **Scala**: 3.8.4
- **sbt**: 1.12.14

## CKKS parameter set

Per the design specification:
- Ring degree: 2^14 = 16,384
- Slots: 8,192 (N/2 complex slots)
- Scale: 2^40
- Block size: 512 dimensions per embedding
- Blocks per ciphertext: 16
- Rotations per block: 9 (log₂ 512)

## Measurements

All measurements are averages over multiple iterations with warmup discarded. Times are measured on a single core; throughput scales linearly with the number of cores available.

### Single operations

| Operation | Count | Avg Time | Throughput |
|-----------|-------|----------|------------|
| Key generation | 10 | 123.09 ms | 8.1 keys/s |
| Gallery encryption (1 vector) | 10 | 7.72 ms | 129.6 vectors/s |
| Single score (1 gallery × 1 query) | 100 | 64.48 ms | 15.5 scores/s |
| Batched score (1 gallery × 16 queries) | 100 | 64.11 ms | 15.6 sets/s |
| Decryption (one score ciphertext) | 100 | 11.45 ms | 87.3 scores/s |

### Derived metrics

The cost of scoring a crawled face against enrolled users is linear in the number of users, not queries. Each user's gallery is encrypted under a different public key, so batching operates within a single user's key, not across users.

Scoring one face against N users requires N multiplies at 64.11 ms each, regardless of whether queries are batched.

| Scenario | Users | Multiplies | Wall-Clock (1 core) |
|----------|-------|-----------|------------------|
| One face, single-query mode | 1,000 | 1,000 | 64.1 seconds |
| One face, single-query mode | 10,000 | 10,000 | 641 seconds (~10.7 min) |
| One face, batched mode | 1,000 | 1,000 | 64.1 seconds |
| One face, batched mode | 10,000 | 10,000 | 641 seconds (~10.7 min) |
| Sixteen faces, batched mode | 1,000 | 1,000 | 64.1 seconds total (4.0 s per face) |
| Sixteen faces, batched mode | 10,000 | 10,000 | 641 seconds total (40 s per face) |

Batching raises aggregate throughput by packing sixteen queries into one multiply. When processing many queries against multiple users, batching improves the throughput of the query queue, but does not reduce the latency for a single face against a large enrollment.

## Accuracy

Previously measured (cited to avoid re-derivation):
- Single-query max error against plaintext cosine: **4.18 × 10⁻⁸**
- Batched-query max error: **2.44 × 10⁻⁷** (mean **2.86 × 10⁻⁸**)

Both are well below any reasonable matching threshold.

## Why indexing cannot help

The throughput ceiling is not a tuning problem; it is a structural constraint of the privacy model:

1. **HNSW and IVF require geometry access**: Both methods partition the search space using plaintext distances or angular partitions to prune candidates. This is the core mechanism that reduces search from O(n) to O(log n) or O(k) with small k.

2. **Encrypted templates do not expose geometry**: A CKKS-encrypted embedding is an opaque ciphertext. No party without the secret key can compute distances, angles, or any relationship between encrypted vectors. This is the privacy guarantee.

3. **Consequence**: Server-side indexing is impossible without decryption (which would expose templates). Client-side indexing requires the server to send all n encrypted vectors to the client before scoring, negating any bandwidth savings.

The linearity is the **cost of the privacy guarantee**. It is not a defect to be engineered away, but a price to be documented and accepted.

## Deployment implications

### Single face lookup

Scoring one crawled face against an enrollment database:
- **1,000 users**: **64.1 seconds** on a single core, or ~5.3 seconds on a 12-core machine (assuming perfect parallelism).
- **10,000 users**: **641 seconds** (~10.7 minutes) on a single core, or ~53 seconds on a 12-core machine.
- **100,000 users**: **6,410 seconds** (~1.8 hours) on a single core, or ~534 seconds (~8.9 minutes) on a 12-core machine.

This cost is **independent of batching mode**. Single-query and batched modes have identical latency for a single-face lookup because the bottleneck is the number of users (multiplies required), not the packing of queries.

This is acceptable for batch processing and overnight runs. It is not practical for real-time interactive lookups at scale.

### Bulk query processing

When processing many faces (e.g., a batch of 16 from video frames or a detection run) against the same enrollment database, batching improves amortized cost:

- **16 faces against 1,000 users**: 1,000 multiplies total = 64.1 seconds, or **4.0 seconds per face**.
- **16 faces against 10,000 users**: 10,000 multiplies total = 641 seconds, or **40 seconds per face**.

Batching reduces per-face cost by distributing the multiply cost across multiple queries. However, the wall-clock time to process all 16 faces against all users remains linear in users.

### Scaling to larger enrollments

Because cost is linear in users, larger enrollments scale predictably:

| Enrollment | One Face (1 core) | 16 Faces Batched (per face, 1 core) | 16 Faces on 12 Cores (per face) |
|-----------|-----------------|-------------------------------------|------------------------------|
| 1,000 | 64 s | 4.0 s | 0.33 s |
| 10,000 | 641 s | 40 s | 3.3 s |
| 100,000 | 6,410 s (1.8 h) | 401 s | 33 s |
| 1,000,000 | 64,100 s (17.8 h) | 4,010 s (67 min) | 334 s (5.6 min) |

For enrollments in the tens of thousands, single-face lookup becomes impractical without parallelism. Bulk processing (batching 16 queries per multiply) reduces per-face cost and is the practical mode for large enrollments.

## Comparison with plaintext matching

A plaintext vector database with standard ANN indexing (HNSW or IVF) achieves sub-millisecond queries even on millions of users (typically 0.1 to 1.0 ms). CKKS encrypted matching at 64 ms per user is roughly **100 to 1000 times slower**.

This slowdown is not an implementation artifact; it is the consequence of the privacy model. Plaintext indexing is possible because the server can read the full geometry to partition and prune. Encrypted templates expose no geometry to any party without the secret key. The linearity and its cost are structural.

Whether this trade-off is acceptable depends on the deployment:
- **Acceptable**: Batch processing, overnight runs, periodic audits, investigative workflows.
- **Not acceptable**: Real-time identity verification against large enrollments (>1000 users).

## Key observations

1. Batching reduces per-face cost within a multiply, not the multiply count. Batching packs sixteen queries into one multiply (one per block), but one multiply per enrolled user is still required. The per-face amortized cost improves 16×, but the latency for a single face against all users remains unchanged.

2. Cost is per (face, user) pair. Matching one face against N users costs N multiplies at 64.1 ms each, totalling 64.1 N milliseconds. This is the base unit of work and is independent of query batching.

3. Key material cost per user is substantial.

   - Public key: 1.3 MB (exact: 1,310,840 bytes, marshalled length from Task 5)
   - Galois keys: 47.2 MB (exact: 47,190,894 bytes for nine rotation steps, marshalled length from Task 5)
   - Total per user: ~48.5 MB
   - At 10,000 users: ~485 GB
   - At 100,000 users: ~4.85 TB
   - At 1,000,000 users: ~48.5 TB

   At million-user scale, storing keys alone requires approximately 48.5 TB, and that counts
   keys only, not the encrypted galleries beside them.

   Gallery ciphertext (one 512-d embedding): 1,048,942 bytes (marshalled length from Task 6).

4. Key generation is a per-user setup cost. At 123 ms per key, enrolling 1,000 users takes approximately 2 minutes. This is amortized over the lifetime of the key and is not a matching-time cost.

5. Decryption is much faster than scoring. At 11.45 ms per decryption, decryption is 5.6× faster than a multiply. Server-side homomorphic evaluation is the throughput bottleneck.

6. No unbounded memory growth was observed. Over 200+ scoring operations, the process remained stable, confirming that intermediate ciphertexts are properly garbage-collected on the Go side.

## Conclusion

The CKKS encrypted matching scheme delivers the intended privacy property: encrypted templates are never exposed to the server, even during scoring. The measured cost is **64.1 seconds per face per thousand enrolled users on a single core**, regardless of query batching mode. This is the concrete price of the privacy guarantee.

**Practical deployments:**

- **Batch processing and overnight runs**: Viable. Processing 1,000 faces against 100,000 users would take ~640,000 core-seconds, feasible as an overnight job on a moderate cluster.
- **Bulk query processing with batching**: Viable. Processing video frames in batches of 16 against 10,000 users takes ~40 seconds per face; parallelizing across 12 cores gives ~3.3 seconds per face.
- **Real-time single-face lookup at large scale**: Not practical. A single query against 10,000 users takes ~11 minutes on one core.

**Secondary cost: Key material.** Storing encryption keys for 100,000 users requires ~4.85 TB. At million-user scale, ~48.5 TB. This storage cost, not yet analyzed in the design spec, should inform enrollment and retention strategies.

Batching is essential for bulk processing and improves throughput, but does not alter the fundamental linearity: work scales with users, not queries.

The numbers reported here should guide implementation decisions. They reflect neither an optimization opportunity nor a defect, but the structural cost of the privacy model.
