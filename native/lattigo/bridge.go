// CKKS bridge for ncii. Built as a C-shared library and called from Scala via
// the Java FFM API.
//
// Lattigo version: v6.2.0 (pinned in go.mod; ciphertext layout depends on it)
//
// Conventions across this ABI:
//   - Objects live in a Go-side registry and are addressed by opaque int64 handles.
//     cgo forbids returning Go pointers to C, and handles keep ownership unambiguous.
//   - Every function returns an int32 status: 0 on success, negative on failure.
//   - On failure the caller reads ckks_last_error for a message.
//   - Byte outputs use the two-call pattern: pass a nil buffer to learn the required
//     size, then call again with a buffer of at least that size.
package main

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"

import (
	"fmt"
	"sync"
	"unsafe"

	"github.com/tuneinsight/lattigo/v6/core/rlwe"
	"github.com/tuneinsight/lattigo/v6/schemes/ckks"
)

const (
	StatusOK            int32 = 0
	StatusBadHandle     int32 = -1
	StatusBadArgument   int32 = -2
	StatusBufferTooSmall int32 = -3
	StatusInternal      int32 = -4
)

type context struct {
	params  ckks.Parameters
	encoder *ckks.Encoder
}

var (
	registryMu sync.Mutex
	registry   = map[int64]any{}
	nextHandle int64 = 1

	errMu   sync.Mutex
	lastErr string
)

func setError(format string, args ...any) {
	errMu.Lock()
	defer errMu.Unlock()
	lastErr = fmt.Sprintf(format, args...)
}

func store(v any) int64 {
	registryMu.Lock()
	defer registryMu.Unlock()
	h := nextHandle
	nextHandle++
	registry[h] = v
	return h
}

func load(h int64) (any, bool) {
	registryMu.Lock()
	defer registryMu.Unlock()
	v, ok := registry[h]
	return v, ok
}

func drop(h int64) {
	registryMu.Lock()
	defer registryMu.Unlock()
	delete(registry, h)
}

// writeString copies s into buf when it fits, and always reports the length needed.
func writeString(s string, buf *C.char, capacity C.int) int32 {
	n := len(s)
	if buf == nil || int(capacity) < n {
		return int32(n)
	}
	dst := unsafe.Slice((*byte)(unsafe.Pointer(buf)), n)
	copy(dst, s)
	return int32(n)
}

//export ckks_version
func ckks_version(buf *C.char, capacity C.int) C.int {
	return C.int(writeString("ncii-ckks/1 lattigo/v6", buf, capacity))
}

//export ckks_last_error
func ckks_last_error(buf *C.char, capacity C.int) C.int {
	errMu.Lock()
	s := lastErr
	errMu.Unlock()
	return C.int(writeString(s, buf, capacity))
}

//export ckks_free_handle
func ckks_free_handle(h C.longlong) C.int {
	if _, ok := load(int64(h)); !ok {
		setError("free: unknown handle %d", int64(h))
		return C.int(StatusBadHandle)
	}
	drop(int64(h))
	return C.int(StatusOK)
}

//export ckks_handle_count
func ckks_handle_count() C.int {
	registryMu.Lock()
	defer registryMu.Unlock()
	return C.int(len(registry))
}

//export ckks_new_context
func ckks_new_context(logN C.int, logScale C.int, out *C.longlong) C.int {
	if out == nil {
		setError("new_context: out pointer is nil")
		return C.int(StatusBadArgument)
	}

	// A 512-d packed dot product must stay inside the noise budget without
	// bootstrapping. Four 45-bit primes give ample depth for one ct x pt multiply
	// plus nine rotations.
	params, err := ckks.NewParametersFromLiteral(ckks.ParametersLiteral{
		LogN:            int(logN),
		LogQ:            []int{55, 45, 45, 45},
		LogP:            []int{61},
		LogDefaultScale: int(logScale),
	})
	if err != nil {
		setError("new_context: %v", err)
		return C.int(StatusInternal)
	}

	*out = C.longlong(store(&context{params: params, encoder: ckks.NewEncoder(params)}))
	return C.int(StatusOK)
}

//export ckks_slot_count
func ckks_slot_count(h C.longlong, out *C.int) C.int {
	v, ok := load(int64(h))
	if !ok {
		setError("slot_count: unknown handle %d", int64(h))
		return C.int(StatusBadHandle)
	}
	ctx, ok := v.(*context)
	if !ok {
		setError("slot_count: handle %d is not a context", int64(h))
		return C.int(StatusBadHandle)
	}
	*out = C.int(ctx.params.MaxSlots())
	return C.int(StatusOK)
}

type keySet struct {
	sk     *rlwe.SecretKey // nil on the server side
	pk     *rlwe.PublicKey
	galois *rlwe.MemEvaluationKeySet
}

// rotationSteps returns the strides a rotate-and-sum over a 512-slot block needs:
// 256, 128, ... 1. Galois keys are generated for exactly these and no others, so an
// unintended rotation fails rather than silently returning garbage.
func rotationSteps(blockSize int) []int {
	steps := []int{}
	for s := blockSize / 2; s >= 1; s /= 2 {
		steps = append(steps, s)
	}
	return steps
}

// writeBytes copies buf into out when it fits, and always reports the length needed.
func writeBytes(buf []byte, out *C.char, capacity C.int) int32 {
	n := len(buf)
	if out == nil || int(capacity) < n {
		return int32(n)
	}
	dst := unsafe.Slice((*byte)(unsafe.Pointer(out)), n)
	copy(dst, buf)
	return int32(n)
}

//export ckks_keygen
func ckks_keygen(ctxHandle C.longlong, blockSize C.int, out *C.longlong) C.int {
	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("keygen: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	ctx, ok := v.(*context)
	if !ok {
		setError("keygen: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	kgen := rlwe.NewKeyGenerator(ctx.params)
	sk := kgen.GenSecretKeyNew()
	pk := kgen.GenPublicKeyNew(sk)

	steps := rotationSteps(int(blockSize))
	galEls := make([]uint64, 0, len(steps))
	for _, s := range steps {
		galEls = append(galEls, ctx.params.GaloisElementForRotation(s))
	}
	gks := kgen.GenGaloisKeysNew(galEls, sk)

	*out = C.longlong(store(&keySet{
		sk:     sk,
		pk:     pk,
		galois: rlwe.NewMemEvaluationKeySet(nil, gks...),
	}))
	return C.int(StatusOK)
}

//export ckks_public_key_bytes
func ckks_public_key_bytes(keyHandle C.longlong, buf *C.char, capacity C.int) C.int {
	v, ok := load(int64(keyHandle))
	if !ok {
		setError("public_key_bytes: unknown key handle %d", int64(keyHandle))
		return C.int(StatusBadHandle)
	}
	ks, ok := v.(*keySet)
	if !ok {
		setError("public_key_bytes: handle %d is not a key set", int64(keyHandle))
		return C.int(StatusBadHandle)
	}

	b, err := ks.pk.MarshalBinary()
	if err != nil {
		setError("public_key_bytes: marshal failed: %v", err)
		return C.int(StatusInternal)
	}

	return C.int(writeBytes(b, buf, capacity))
}

//export ckks_galois_key_bytes
func ckks_galois_key_bytes(keyHandle C.longlong, buf *C.char, capacity C.int) C.int {
	v, ok := load(int64(keyHandle))
	if !ok {
		setError("galois_key_bytes: unknown key handle %d", int64(keyHandle))
		return C.int(StatusBadHandle)
	}
	ks, ok := v.(*keySet)
	if !ok {
		setError("galois_key_bytes: handle %d is not a key set", int64(keyHandle))
		return C.int(StatusBadHandle)
	}

	b, err := ks.galois.MarshalBinary()
	if err != nil {
		setError("galois_key_bytes: marshal failed: %v", err)
		return C.int(StatusInternal)
	}

	return C.int(writeBytes(b, buf, capacity))
}

//export ckks_keyset_from_public
func ckks_keyset_from_public(ctxHandle C.longlong, pkBuf *C.char, pkLen C.int, gkBuf *C.char, gkLen C.int, out *C.longlong) C.int {
	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("keyset_from_public: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	_, ok = v.(*context)
	if !ok {
		setError("keyset_from_public: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	if pkBuf == nil {
		setError("keyset_from_public: public key buffer is nil")
		return C.int(StatusBadArgument)
	}
	if gkBuf == nil {
		setError("keyset_from_public: galois key buffer is nil")
		return C.int(StatusBadArgument)
	}

	pkData := unsafe.Slice((*byte)(unsafe.Pointer(pkBuf)), pkLen)
	gkData := unsafe.Slice((*byte)(unsafe.Pointer(gkBuf)), gkLen)

	pk := new(rlwe.PublicKey)
	if err := pk.UnmarshalBinary(pkData); err != nil {
		setError("keyset_from_public: public key unmarshal failed: %v", err)
		return C.int(StatusInternal)
	}

	gks := new(rlwe.MemEvaluationKeySet)
	if err := gks.UnmarshalBinary(gkData); err != nil {
		setError("keyset_from_public: galois keys unmarshal failed: %v", err)
		return C.int(StatusInternal)
	}

	*out = C.longlong(store(&keySet{
		sk:     nil, // Server-side key sets have no secret key
		pk:     pk,
		galois: gks,
	}))
	return C.int(StatusOK)
}

//export ckks_encrypt
func ckks_encrypt(ctxHandle C.longlong, keyHandle C.longlong, values *C.double, n C.int, out *C.longlong) C.int {
	if out == nil {
		setError("encrypt: out pointer is nil")
		return C.int(StatusBadArgument)
	}

	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("encrypt: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	ctx, ok := v.(*context)
	if !ok {
		setError("encrypt: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	v, ok = load(int64(keyHandle))
	if !ok {
		setError("encrypt: unknown key handle %d", int64(keyHandle))
		return C.int(StatusBadHandle)
	}
	ks, ok := v.(*keySet)
	if !ok {
		setError("encrypt: handle %d is not a key set", int64(keyHandle))
		return C.int(StatusBadHandle)
	}

	if values == nil {
		setError("encrypt: values buffer is nil")
		return C.int(StatusBadArgument)
	}

	// Convert C array to Go slice
	slots := unsafe.Slice((*float64)(values), n)

	// Create a plaintext and encode the slot values
	pt := ckks.NewPlaintext(ctx.params, ctx.params.MaxLevel())
	if err := ctx.encoder.Encode(slots, pt); err != nil {
		setError("encrypt: encode failed: %v", err)
		return C.int(StatusInternal)
	}

	// Create an encryptor and encrypt under the public key
	encryptor := rlwe.NewEncryptor(ctx.params, ks.pk)
	ct, err := encryptor.EncryptNew(pt)
	if err != nil {
		setError("encrypt: encrypt failed: %v", err)
		return C.int(StatusInternal)
	}

	*out = C.longlong(store(ct))
	return C.int(StatusOK)
}

//export ckks_ciphertext_bytes
func ckks_ciphertext_bytes(ctHandle C.longlong, buf *C.char, capacity C.int) C.int {
	v, ok := load(int64(ctHandle))
	if !ok {
		setError("ciphertext_bytes: unknown ciphertext handle %d", int64(ctHandle))
		return C.int(StatusBadHandle)
	}
	ct, ok := v.(*rlwe.Ciphertext)
	if !ok {
		setError("ciphertext_bytes: handle %d is not a ciphertext", int64(ctHandle))
		return C.int(StatusBadHandle)
	}

	b, err := ct.MarshalBinary()
	if err != nil {
		setError("ciphertext_bytes: marshal failed: %v", err)
		return C.int(StatusInternal)
	}

	return C.int(writeBytes(b, buf, capacity))
}

//export ckks_ciphertext_from_bytes
func ckks_ciphertext_from_bytes(ctxHandle C.longlong, buf *C.char, bufLen C.int, out *C.longlong) C.int {
	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("ciphertext_from_bytes: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	_, ok = v.(*context)
	if !ok {
		setError("ciphertext_from_bytes: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	if buf == nil {
		setError("ciphertext_from_bytes: buffer is nil")
		return C.int(StatusBadArgument)
	}

	if out == nil {
		setError("ciphertext_from_bytes: out pointer is nil")
		return C.int(StatusBadArgument)
	}

	data := unsafe.Slice((*byte)(unsafe.Pointer(buf)), bufLen)
	ct := new(rlwe.Ciphertext)
	if err := ct.UnmarshalBinary(data); err != nil {
		setError("ciphertext_from_bytes: unmarshal failed: %v", err)
		return C.int(StatusInternal)
	}

	*out = C.longlong(store(ct))
	return C.int(StatusOK)
}

//export ckks_decrypt
func ckks_decrypt(ctxHandle C.longlong, keyHandle C.longlong, ctHandle C.longlong, out *C.double, n C.int) C.int {
	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("decrypt: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	ctx, ok := v.(*context)
	if !ok {
		setError("decrypt: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	v, ok = load(int64(keyHandle))
	if !ok {
		setError("decrypt: unknown key handle %d", int64(keyHandle))
		return C.int(StatusBadHandle)
	}
	ks, ok := v.(*keySet)
	if !ok {
		setError("decrypt: handle %d is not a key set", int64(keyHandle))
		return C.int(StatusBadHandle)
	}

	// Enforce the security property: a server-side key set (with no secret key) cannot decrypt
	if ks.sk == nil {
		setError("decrypt: key set has no secret key (server-side key sets cannot decrypt)")
		return C.int(StatusBadArgument)
	}

	v, ok = load(int64(ctHandle))
	if !ok {
		setError("decrypt: unknown ciphertext handle %d", int64(ctHandle))
		return C.int(StatusBadHandle)
	}
	ct, ok := v.(*rlwe.Ciphertext)
	if !ok {
		setError("decrypt: handle %d is not a ciphertext", int64(ctHandle))
		return C.int(StatusBadHandle)
	}

	if out == nil {
		setError("decrypt: out pointer is nil")
		return C.int(StatusBadArgument)
	}

	// Decrypt the ciphertext
	decryptor := rlwe.NewDecryptor(ctx.params, ks.sk)
	pt := decryptor.DecryptNew(ct)

	// Decode the plaintext to get slot values
	slots := make([]complex128, ctx.params.MaxSlots())
	if err := ctx.encoder.Decode(pt, slots); err != nil {
		setError("decrypt: decode failed: %v", err)
		return C.int(StatusInternal)
	}

	// Copy the real parts to the output array (CKKS encodes real values in the real part)
	outSlice := unsafe.Slice((*float64)(out), n)
	for i := 0; i < int(n) && i < len(slots); i++ {
		outSlice[i] = float64(real(slots[i]))
	}

	return C.int(StatusOK)
}

//export ckks_score
func ckks_score(ctxHandle C.longlong, keyHandle C.longlong, ctHandle C.longlong, query *C.double, n C.int, blockSize C.int, out *C.longlong) C.int {
	if out == nil {
		setError("score: out pointer is nil")
		return C.int(StatusBadArgument)
	}

	v, ok := load(int64(ctxHandle))
	if !ok {
		setError("score: unknown context handle %d", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}
	ctx, ok := v.(*context)
	if !ok {
		setError("score: handle %d is not a context", int64(ctxHandle))
		return C.int(StatusBadHandle)
	}

	v, ok = load(int64(keyHandle))
	if !ok {
		setError("score: unknown key handle %d", int64(keyHandle))
		return C.int(StatusBadHandle)
	}
	ks, ok := v.(*keySet)
	if !ok {
		setError("score: handle %d is not a key set", int64(keyHandle))
		return C.int(StatusBadHandle)
	}

	v, ok = load(int64(ctHandle))
	if !ok {
		setError("score: unknown ciphertext handle %d", int64(ctHandle))
		return C.int(StatusBadHandle)
	}
	ct, ok := v.(*rlwe.Ciphertext)
	if !ok {
		setError("score: handle %d is not a ciphertext", int64(ctHandle))
		return C.int(StatusBadHandle)
	}

	if query == nil {
		setError("score: query buffer is nil")
		return C.int(StatusBadArgument)
	}

	// Convert query C array to Go slice
	values := unsafe.Slice((*float64)(unsafe.Pointer(query)), int(n))

	// The query arrives already replicated across all blocks, so this is a single
	// ciphertext x plaintext multiply. No relinearisation is needed because one operand
	// is a plaintext, which is the reason this scheme fits in the noise budget.
	pt := ckks.NewPlaintext(ctx.params, ct.Level())
	if err := ctx.encoder.Encode(values, pt); err != nil {
		setError("score: encode: %v", err)
		return C.int(StatusInternal)
	}

	eval := ckks.NewEvaluator(ctx.params, ks.galois)

	// Attempt ct × pt multiply using MulNew first (no relinearization needed for ct × pt)
	prod, err := eval.MulNew(ct, pt)
	if err != nil {
		setError("score: multiply: %v", err)
		return C.int(StatusInternal)
	}

	// Rotate-and-sum within each 512-slot block. Halving the stride each round leaves
	// every slot of a block holding that block's total after log2(blockSize) rounds.
	// Rotations are cyclic over all 8192 slots, but because blockSize divides the slot
	// count and every block is summed in lockstep, no value crosses a block boundary.
	// Note: rotated and intermediate AddNew results are Go heap values, collected by the
	// garbage collector and deliberately not registered in the handle registry. Only the
	// final accumulator is stored as a native handle for return to Scala.
	acc := prod
	for step := int(blockSize) / 2; step >= 1; step /= 2 {
		rotated, err := eval.RotateNew(acc, step)
		if err != nil {
			setError("score: rotate by %d: %v", step, err)
			return C.int(StatusInternal)
		}
		acc, err = eval.AddNew(acc, rotated)
		if err != nil {
			setError("score: add after rotate %d: %v", step, err)
			return C.int(StatusInternal)
		}
	}

	*out = C.longlong(store(acc))
	return C.int(StatusOK)
}

func main() {}
