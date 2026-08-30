# Store module

The store module keeps enrolment data in Postgres: enrolled users, their CKKS key material, and
encrypted facial galleries. Plaintext photographs are never stored.

## Schema

Four tables, all created by `src/main/resources/db/migration/V1__initial.sql` on startup.

`enrolled_user` identifies an enrolled person.

- `id` (UUID, primary key): unique enrolment identifier.
- `display_name` (text): the name given during enrolment. Not validated or normalised.
- `enrolled_at` (timestamp): when enrolment happened. Defaults to `now()`.

This table holds no biometric data, only a display name and a timestamp.

`key_material` holds the CKKS public and Galois keys uploaded by the client.

- `user_id` (UUID, primary key, foreign key to `enrolled_user`): who the keys belong to.
- `public_key` (bytea): CKKS public key, about 1.3 MB.
- `galois_keys` (bytea): precomputed Galois keys for nine rotation steps, about 47.2 MB.
- `created_at` (timestamp): when the keys arrived.

`encrypted_gallery` holds one user's encrypted embedding set.

- `user_id` (UUID, primary key, foreign key to `enrolled_user`): who the gallery belongs to.
- `ciphertext` (bytea): the encrypted embeddings, about 1 MB for a gallery of 3 to 5 vectors.
- `vector_count` (int, CHECK between 1 and 16): how many embeddings the ciphertext contains.
  Sixteen 512-slot blocks fit in one CKKS ciphertext. The spec asks for 3 to 5, so the gallery
  spans variation without wasting slots.
- `created_at` (timestamp): when enrolment completed.

The ciphertext is opaque to the server. Only a client holding the secret key can decrypt it,
which is the property the whole design rests on.

`enrolment_audit` records what happened during an enrolment.

- `id` (BIGSERIAL, primary key): sequence number.
- `user_id` (UUID, foreign key to `enrolled_user`): who was being enrolled.
- `photos_offered` (int): how many photographs the client uploaded.
- `photos_usable` (int): how many produced a face that passed the quality gate.
- `outcome` (text): `Accepted`, or one of the `Rejected` values.
- `reason` (text): why, if the enrolment was rejected.
- `occurred_at` (timestamp): when it happened.

## There is no secret-key column

There is deliberately no column and no method for storing a secret key. The client holds the
secret key and never uploads it. A server-side secret key would defeat the central guarantee,
which is that the server cannot decrypt a gallery or match on plaintext. `KeyMaterialRepo` has
no method to store one, and none should be added.

## Audit records hold no biometric data

The audit table must never carry embeddings, image bytes, face coordinates, or anything else a
face could be reconstructed from. The guarantee is structural rather than tested: the table has
no `bytea` column, and `AuditRecord` has no field that could carry one. A SQL CHECK constraint
cannot enforce the absence of data, so the column list is the enforcement.

A rejected enrolment leaves an audit record and nothing else. No key material, no gallery.

## Key material at scale

Keys dominate storage. One user's key material comes to roughly 48.5 MB: about 1.3 MB of public
key and 47.2 MB of Galois keys.

| enrolled users | key material |
|---|---|
| 1,000 | about 48.5 GB |
| 10,000 | about 485 GB |
| 100,000 | about 4.85 TB |
| 1,000,000 | about 48.5 TB |

At larger enrolments this becomes a real operational cost, and that is before storing any
galleries. See [`docs/evaluation/ckks-throughput.md`](../docs/evaluation/ckks-throughput.md) for
the measurements and what they mean for deployment.

## Testing

Tests bring up a `postgres:17-alpine` container through testcontainers, so Docker must be
running. Postgres is not needed natively. Every test in this module touches the database, and
they run one at a time to avoid contention (`Global / concurrentRestrictions` in `build.sbt`).

Run them with `sbt store/test`, or `sbt test` for the whole build.
