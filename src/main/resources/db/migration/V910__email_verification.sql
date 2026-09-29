-- Email ownership: accounts are created only after the mailbox owner opens a registration link.
ALTER TABLE iam.local_credential ADD COLUMN email_verified_at timestamptz;
-- Accounts that existed before verification (including provisioned administrators) keep working.
UPDATE iam.local_credential SET email_verified_at=created_at WHERE email_verified_at IS NULL;

-- One-time mail links. Only the SHA-256 of the random token is stored, never the token itself.
-- REGISTER rows hold the pending registration (no account exists yet);
-- RESET_PASSWORD rows point at an existing account.
CREATE TABLE iam.email_token (
 token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 purpose text NOT NULL CHECK(purpose IN ('REGISTER','RESET_PASSWORD')),
 email varchar(254) NOT NULL CHECK(email=lower(btrim(email))),
 user_id uuid REFERENCES iam.user_account(id),
 display_name text,
 password_hash varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 used_at timestamptz,
 CHECK(expires_at>created_at),
 CHECK(CASE purpose
   WHEN 'REGISTER' THEN user_id IS NULL AND display_name IS NOT NULL AND password_hash IS NOT NULL
   ELSE user_id IS NOT NULL AND display_name IS NULL AND password_hash IS NULL END)
);
CREATE INDEX ix_email_token_email ON iam.email_token(email,purpose) WHERE used_at IS NULL;
CREATE INDEX ix_email_token_expiry ON iam.email_token(expires_at);
