-- V109: board_members.aadhaar_last4 was created as CHAR(4) in V1, but BoardMember maps it as
-- a plain String (VARCHAR(4)). Hibernate schema-validation rejects the mismatch.
-- MODIFY is idempotent, so re-running is safe.

ALTER TABLE board_members
    MODIFY COLUMN aadhaar_last4 VARCHAR(4) NULL COMMENT 'Last 4 digits for masked display';
