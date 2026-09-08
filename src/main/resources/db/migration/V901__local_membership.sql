-- Above V900 so previously initialized development DBs apply this migration normally.
CREATE TABLE iam.local_credential (
 user_id uuid PRIMARY KEY REFERENCES iam.user_account(id),
 email varchar(254) NOT NULL UNIQUE CHECK(email=lower(btrim(email))),
 password_hash varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 password_changed_at timestamptz NOT NULL DEFAULT now()
);
-- Personal favorites exist before a shared wedding project is created.
CREATE TABLE planning.user_favorite (
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 listing_id uuid NOT NULL REFERENCES catalog.listing(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,listing_id)
);
CREATE INDEX ix_user_favorite_listing ON planning.user_favorite(listing_id);
