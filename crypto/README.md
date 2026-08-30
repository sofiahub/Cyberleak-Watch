# crypto: CKKS encrypted matching core

Server-side CKKS homomorphic encryption for privacy-preserving facial-likeness matching.
This module implements encrypted gallery storage and scoring, such that galleries are never
exposed to the server even during similarity computation.

**Build prerequisite.** The crypto module requires `./scripts/build-lattigo.sh` to have
built `native/build/libncii_ckks.dylib` first. Go 1.26.6 and JDK 25 are required. The
native binary is not committed and is rebuilt on every clean checkout.

## CKKS Parameter Set

These parameters are **fixed by the design** and are not tunables. The ciphertext layout,
rotation schedule, and noise budget depend on every value. A gallery encrypted under one
parameter set cannot be scored under another. The parameters establish a contract between
enrolment and matching time.

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Ring degree (logN) | 14 (16384) | Standard CKKS degree; 8192 slots sufficient for 16 × 512-d embeddings per ciphertext |
| Slots | 8192 (N/2 complex) | CKKS packs N/2 complex slots from degree N |
| Scale | 2^40 | Sufficient precision that decrypted scores differ ~1e-5 from plaintext cosine, orders of magnitude below any matching threshold |
| Block size | 512 dimensions | One 512-d embedding per block |
| Blocks per ciphertext | 16 | Packs 3 to 5 face galleries (16 × 512 = 8192 slots) into a single ciphertext with remainder zero-padded |
| Modulus chain | LogQ = [55, 45, 45, 45], LogP = [61] | Four 45-bit primes (plus one 55-bit level) give ample depth for one ciphertext × plaintext multiply plus nine rotate-and-sum operations without relinearisation |
| Rotations per block | 9 | log₂(512) = 9; rotate-and-sum halves the stride each round to collapse a block to its dot product |
| Lattigo version | v6.2.0 (pinned in `native/lattigo/go.mod`) | Ciphertext marshalling layout depends on the exact version; pinned to prevent silent divergence |

## Slot Layout and Worked Example

A CKKS ciphertext carries 8192 slots arranged as sixteen 512-slot blocks. Each block holds
one 512-dimensional embedding (or zero-padding for unused blocks). Scoring uses the
replication-multiply-reduce pattern:

**Gallery (ciphertext)**: Sixteen embedded vectors, one per block.
```
Ciphertext
├─ Block 0: embedding[0] at slots [0, 512)
├─ Block 1: embedding[1] at slots [512, 1024)
├─ ...
└─ Block 15: embedding[15] at slots [7680, 8192)
```

**Query (plaintext)**: One query replicated identically into all sixteen blocks.
```
Plaintext query
├─ Block 0: query at slots [0, 512)
├─ Block 1: query at slots [512, 1024)
├─ ...
└─ Block 15: query at slots [7680, 8192)
```

**Scoring**: One ciphertext × plaintext multiply produces sixteen element-wise products
simultaneously:
```
(ct × pt)[i] = gallery[block_i][j] · query[j]  for j in [0, 512)
```

**Reduction within each block** (rotate-and-sum): Halving the rotation stride each round
collapses every block to its dot product in the block's first slot:

```
Round 0: rotate by 256, add → [dot_0, dot_0, dot_0, dot_0, ...] (every slot in block 0)
Round 1: rotate by 128, add → dot_0 remains in slot 0; other slots converge
...
Round 8: rotate by 1, add → dot_0 isolated in block[i*512]
```

After all 9 rotations, each block's first slot (at positions 0, 512, 1024, ..., 7680)
holds the complete dot product of that block's embedding with the query.

**Example**: A 3-face gallery is packed into one ciphertext with 13 blocks zero-padded:
```
Ciphertext slots:
- [0, 512): face_0 embedding
- [512, 1024): face_1 embedding
- [1024, 1536): face_2 embedding
- [1536, 8192): zeros

Query (plaintext):
- [0, 512): query embedding (replicated 16 times)
- [512, 1024): query embedding (replicated 16 times)
- ...
- [7680, 8192): query embedding (replicated 16 times)

After multiply:
- [0, 512): face_0 · query (element-wise)
- [512, 1024): face_1 · query (element-wise)
- [1024, 1536): face_2 · query (element-wise)
- [1536, 8192): zeros · query (all zeros)

After rotate-and-sum:
- Slot 0: dot(face_0, query)
- Slot 512: dot(face_1, query)
- Slot 1024: dot(face_2, query)
- Slot 1536+: zeros
```

## Embeddings Are L2-Normalised

Both embeddings in the gallery and query embeddings are L2-normalised by construction
(enforced in `core/Embedding` with a private constructor and public factory methods). The
dot product of two L2-normalised vectors is their **cosine similarity**. No renormalisation
happens in this module; the invariant is preserved from enrolment through to scoring.

## Security Property

**The server can score but not decrypt.**

Server-side key sets are constructed with `sk: nil` (in the Go bridge code) and hold no
secret key. The decryption function (`ckks_decrypt`) explicitly checks for the presence of
the secret key before attempting decryption and returns `StatusBadArgument` if absent:

```go
// bridge.go, ckks_decrypt function
if ks.sk == nil {
    setError("decrypt: key set has no secret key (server-side key sets cannot decrypt)")
    return C.int(StatusBadArgument)
}
```

This check is enforced **natively**, not by a Scala flag or wrapper. Bypassing the Scala
layer does not bypass the guarantee; the Go side rejects the operation structurally.

**Test assertion**: `LifecycleSuite` confirms the server-side key set can score but cannot
decrypt:
- `shouldSucceed` for scoring operations
- `shouldThrow` with `NativeException` for any attempt to decrypt

## Byte-Transfer Convention

Any new bridge export returning byte data must follow the two-call pattern established by
the existing exports (`ckks_public_key_bytes`, `ckks_galois_key_bytes`, etc.). This is the
standard pattern for C FFI when the caller allocates the buffer:

**Signature (C):**
```c
int func(long handle, char* buf, int capacity);
```

**Calling convention (Scala, via `KeySet.readBytes`):**
1. **First call** (size query): Pass `buf = NULL`, `capacity = 0`.
   - Returns the required length (positive integer).
   - A negative return is an error; zero-length is also an error.

2. **Second call** (data fetch): Allocate a buffer of the returned size, pass it with the
   capacity.
   - Returns the number of bytes written (must match the size query).
   - A negative return is an error; a mismatch between size and written bytes is an error.

**Implementation pattern (Go):**
```go
func writeBytes(buf []byte, out *C.char, capacity C.int) int32 {
    n := len(buf)
    if out == nil || int(capacity) < n {
        return int32(n)  // Query phase: return required size
    }
    dst := unsafe.Slice((*byte)(unsafe.Pointer(out)), n)
    copy(dst, buf)
    return int32(n)  // Fetch phase: return bytes written
}
```

**Reference implementation in Scala:** `KeySet.readBytes` (lines 27 to 47). Any new bridge
export must follow this pattern exactly. The verification on line 44 (comparing requested
vs. actual size) is mandatory and catches silent truncation or other ABI mismatches.

## FFM Conventions

Two FFM conventions are non-obvious and both were discovered the hard way (Task 9):

### 1. Use `invoke`, not `invokeExact`

Call sites must use `MethodHandle.invoke`, not `invokeExact`. The latter demands an exact
static type signature match that Scala cannot express for a `MethodHandle` returned from a
generic method, and throws `WrongMethodTypeException` at runtime.

`invoke` performs type coercion instead of rejecting. This is safe here because:
- These are not hot-path operations (initialization, cleanup, error handling).
- Argument and return types at each call site are verified by inspection against the
  `FunctionDescriptor`, and the runtime will no longer catch a mismatch loudly.

**Example (correct):**
```scala
handle.invoke(keyHandle, buffer, sizeResult).asInstanceOf[Int]
```

**Example (incorrect, fails at runtime):**
```scala
handle.invokeExact(keyHandle, buffer, sizeResult)  // WrongMethodTypeException
```

**Reference:** `NativeLibrary.readString` (lines 78 to 87) and `KeySet.readBytes` (line 39).

### 2. Intermediate Ciphertexts Are Not Registered in the Handle Registry

Intermediate ciphertexts produced during scoring (rotated values, partial sums) are Go
heap values and are deliberately **not** registered in the handle registry. Only results
returned to Scala are stored. This is not a leak:

- Intermediate values are temporary and short-lived (seconds at most).
- The Go garbage collector reclaims them immediately after the operation completes.
- Task 9's hundred-cycle lifecycle test confirms the registry is conserved over time.

**Benefit:** Keeping the registry small reduces contention and simplifies cleanup. The
Scala layer only holds handles it explicitly needs.

**Reference:** `ckks_score` function (lines 561 to 562):
```go
// Note: rotated and intermediate AddNew results are Go heap values, collected by the
// garbage collector and deliberately not registered in the handle registry.
```

## Measured Costs

**See [`docs/evaluation/ckks-throughput.md`](../docs/evaluation/ckks-throughput.md)** (sections "Measurements" and "Key Observations") for:

- Throughput benchmarks: key generation, gallery encryption, single and batched scoring, decryption
- Exact byte counts with provenance: public key (1,310,840 bytes), Galois keys (47,190,894 bytes for nine rotations), gallery ciphertext (1,048,942 bytes)
- Accuracy figures: single-query and batched max error against plaintext cosine
- Deployment implications table across enrollment sizes

## Critical: Batching Does Not Amortise Across Users

**Batching packs sixteen queries into one multiply against one user's gallery.** It does
not amortise across users, because each user's gallery is encrypted under a distinct
public key. A gallery ciphertext cannot be moved between users or shared across different
public keys.

Scoring one face against N users costs **N multiplies**, regardless of whether queries are
batched. Batching improves the per-query cost *within* a single multiply (amortised across
sixteen queries for one user), but the user count dominates the wall-clock time.

**Example:** Scoring one face against 1,000 users requires 1,000 multiplies at 64.5 ms
each, totalling ~64 seconds, whether using single-query or batched mode. Batching would
reduce per-query latency if you had 16 queries per multiply, but you still need 1,000
multiplies for 1,000 users.

See [`docs/evaluation/ckks-throughput.md`](../docs/evaluation/ckks-throughput.md) (especially the table at lines 41 to 54)
for the full explanation and deployment implications.

## Design References

- **Privacy-preserving likeness matching specification:** 
  [`docs/superpowers/specs/2026-08-08-privacy-preserving-likeness-matching-design.md`](../docs/superpowers/specs/2026-08-08-privacy-preserving-likeness-matching-design.md)
  (Section 7 covers ciphertext layout, slot packing, and rotation schedule)

- **Throughput ceiling and deployment implications:** 
  [`docs/evaluation/ckks-throughput.md`](../docs/evaluation/ckks-throughput.md)

- **Implementation plan (plan 2):**
  [`docs/superpowers/plans/2026-08-10-vision-core-and-evaluation.md`](../docs/superpowers/plans/2026-08-10-vision-core-and-evaluation.md)
