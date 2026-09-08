-- Preserve the legacy column until estimate persistence is implemented.
ALTER TABLE pricing.estimate
  ADD COLUMN security_state text NOT NULL DEFAULT 'UNKNOWN'
    CHECK (security_state IN ('NONE','FIXED','RANGE','UNKNOWN')),
  ADD COLUMN security_min numeric(18,0),
  ADD COLUMN security_max numeric(18,0),
  ADD CONSTRAINT ck_security_amount_state CHECK (
    (security_state='UNKNOWN' AND security_min IS NULL AND security_max IS NULL)
    OR (security_state='NONE' AND security_min IS NOT NULL AND security_max IS NOT NULL AND security_min=0 AND security_max=0)
    OR (security_state='FIXED' AND security_min IS NOT NULL AND security_max IS NOT NULL AND security_min>=0 AND security_max=security_min)
    OR (security_state='RANGE' AND security_min IS NOT NULL AND security_max IS NOT NULL AND security_min>=0 AND security_max>security_min)
  );
