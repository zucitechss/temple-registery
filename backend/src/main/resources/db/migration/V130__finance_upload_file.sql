-- ============================================================================
-- V130: The uploaded workbook (FR19)
--
-- ONE TABLE, BECAUSE THE BATCH ALREADY DOES THE REST
--
-- An Excel import is a batch. fin_sync_batch already counts rows extracted,
-- rejected and loaded, holds a status, tracks retries, names its trigger and
-- now names its actor (V122). fin_sync_error already records a per-row failure
-- with a stage, a code, a message and the offending payload, which is exactly
-- the error report FR19 needs. None of that is rebuilt.
--
-- What a batch cannot hold is the file: the name a person will refer to, the
-- content hash that detects a re-upload, and the reference to the stored bytes.
-- That is this table, and it is one-to-one with the batch it opened.
--
-- DUPLICATE DETECTION
--
-- uk_fuf_temple_capability_hash is the first of three guards, and the only one
-- that produces a message a person can act on -- "you already uploaded this file
-- on the 3rd". The other two are structural: the staging unique key catches a
-- repeated row inside one file, and the fact grain turns a genuinely new file
-- covering an already-loaded day into a restatement.
--
-- The hash is over the uploaded bytes. Two files that differ only in a
-- spreadsheet recalculation are different files by this test, which is the safe
-- direction to be wrong in: it admits a file it might have rejected, and the
-- date-supersede protocol (V124) then prevents the double count.
--
-- Scoped to (temple, capability) rather than globally, because two temples
-- filling the same template with the same figures is a coincidence, not a
-- duplicate.
--
-- WHY status LIVES HERE AND ON THE BATCH
--
-- They are different questions. The batch status is where the pipeline got to.
-- The upload status is what the uploader is being asked to do next: it was
-- validated and is waiting for them to confirm, or they confirmed and it
-- imported, or they abandoned it. An upload sitting at AWAITING_COMMIT with a
-- perfectly healthy batch is the normal state between the two calls FR19's flow
-- requires, and no single column expresses both.
--
-- THE BYTES ARE KEPT
--
-- storage_ref points at the existing file store. The file is retained because it
-- is the end of the provenance chain: "Expenses!R42" only means something while
-- the workbook it names still exists. Retention is the same open question as
-- staging retention (Q7) and is deliberately not answered here.
-- ============================================================================

CREATE TABLE fin_upload_file (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id           BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id    BIGINT        NOT NULL  COMMENT 'fin_source_system.id of the FILE_UPLOAD channel this arrived through',
    capability          VARCHAR(50)   NOT NULL  COMMENT 'What the workbook contains, e.g. EXPENSE. One capability per file: a template is per capability',
    sync_batch_id       BIGINT        NULL      COMMENT 'fin_sync_batch.id opened for this file. NULL only between the row being written and the batch being opened',

    original_filename   VARCHAR(255)  NOT NULL  COMMENT 'As uploaded. What the person will call it when they ask about it',
    content_sha256      CHAR(64)      NOT NULL  COMMENT 'Over the uploaded bytes. The duplicate guard',
    size_bytes          BIGINT        NOT NULL,
    template_version    VARCHAR(20)   NULL      COMMENT 'Read from the workbook version marker. NULL means it could not be read, which is a structural rejection, not an unknown version',
    storage_ref         VARCHAR(500)  NOT NULL  COMMENT 'Handle in the existing file store. Never a filesystem path this application constructs',

    status              VARCHAR(30)   NOT NULL DEFAULT 'RECEIVED'
                                                COMMENT 'RECEIVED | REJECTED_STRUCTURE | REJECTED_DUPLICATE | AWAITING_COMMIT | COMMITTED | ABANDONED. What the uploader is being asked to do next -- not where the pipeline got to, which is the batch status',
    rejection_reason    TEXT          NULL      COMMENT 'Why the whole file was refused. Per-row reasons are fin_sync_error',

    row_count           INT           NULL      COMMENT 'Data rows found. NULL until the structural check has read it',
    rows_valid          INT           NULL,
    rows_invalid        INT           NULL,
    dates_covered_from  DATE          NULL      COMMENT 'Earliest business date in the file. Drives the FR19 freshness update and the supersede preview',
    dates_covered_to    DATE          NULL,

    uploaded_by         BIGINT        NOT NULL  COMMENT 'The person. This is where "who uploaded it" lives, and it is NOT NULL because a file always has one',
    uploaded_at         DATETIME(6)   NOT NULL,
    committed_by        BIGINT        NULL      COMMENT 'Who confirmed the import. Usually the uploader, deliberately not assumed to be',
    committed_at        DATETIME(6)   NULL,

    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fuf_temple_capability_hash UNIQUE (temple_id, capability, content_sha256)
);

CREATE INDEX idx_fuf_temple_status ON fin_upload_file (temple_id, status, uploaded_at);
CREATE INDEX idx_fuf_batch         ON fin_upload_file (sync_batch_id);
CREATE INDEX idx_fuf_uploader      ON fin_upload_file (uploaded_by, uploaded_at);
