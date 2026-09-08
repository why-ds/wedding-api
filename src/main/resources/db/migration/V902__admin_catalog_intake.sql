CREATE TABLE iam.platform_role (code text PRIMARY KEY, name text NOT NULL);
INSERT INTO iam.platform_role VALUES ('ADMIN','운영 관리자');
CREATE TABLE iam.user_platform_role (
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 role_code text NOT NULL REFERENCES iam.platform_role(code),
 granted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,role_code)
);
CREATE TABLE ops.catalog_draft (
 id uuid PRIMARY KEY, external_key varchar(80) NOT NULL UNIQUE, identity_key char(64) NOT NULL UNIQUE,
 data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
 status text NOT NULL CHECK(status IN ('DRAFT','ARCHIVED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES iam.user_account(id),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ops.catalog_import (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES iam.user_account(id), file_hash char(64) NOT NULL,
 rows_json jsonb NOT NULL CHECK(jsonb_typeof(rows_json)='array'),
 expires_at timestamptz NOT NULL, committed_at timestamptz, result_ids jsonb
);
CREATE INDEX ix_catalog_draft_status ON ops.catalog_draft(status,updated_at DESC,id);
CREATE INDEX ix_catalog_import_expiry ON ops.catalog_import(expires_at) WHERE committed_at IS NULL;
