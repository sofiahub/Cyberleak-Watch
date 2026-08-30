-- Enrolled users. Holds no biometric data: the display name is what the user typed,
-- and the id is what every other table keys on.
CREATE TABLE IF NOT EXISTS enrolled_user (
    id           UUID PRIMARY KEY,
    display_name TEXT        NOT NULL,
    enrolled_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CKKS key material uploaded by the client. The secret key never appears here;
-- storing one would break the design's central guarantee.
--
-- Sizes measured in plan 2: public key ~1.3 MB, Galois keys ~47.2 MB. bytea holds up
-- to 1 GB per value, so this fits, but the Galois column dominates the database.
CREATE TABLE IF NOT EXISTS key_material (
    user_id      UUID PRIMARY KEY REFERENCES enrolled_user(id) ON DELETE CASCADE,
    public_key   BYTEA       NOT NULL,
    galois_keys  BYTEA       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The encrypted gallery. One ciphertext per user, ~1 MB, holding up to sixteen
-- 512-slot blocks of which 3-5 are used.
CREATE TABLE IF NOT EXISTS encrypted_gallery (
    user_id      UUID PRIMARY KEY REFERENCES enrolled_user(id) ON DELETE CASCADE,
    ciphertext   BYTEA       NOT NULL,
    vector_count INT         NOT NULL CHECK (vector_count BETWEEN 1 AND 16),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What happened during an enrolment, for reporting and debugging.
--
-- This table must never carry biometric data: no embeddings, no image bytes, no
-- derived vectors. Counts and outcomes only. A CHECK constraint cannot enforce that,
-- so it is enforced by the column list and asserted by a test.
CREATE TABLE IF NOT EXISTS enrolment_audit (
    id             BIGSERIAL PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES enrolled_user(id) ON DELETE CASCADE,
    photos_offered INT         NOT NULL,
    photos_usable  INT         NOT NULL,
    outcome        TEXT        NOT NULL,
    reason         TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
