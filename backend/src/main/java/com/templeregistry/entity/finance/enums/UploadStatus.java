package com.templeregistry.entity.finance.enums;

/**
 * What the uploader of a workbook is being asked to do next (FR19).
 *
 * <p>Deliberately not the same as the batch status, which says where the
 * pipeline got to. An upload sitting at {@link #AWAITING_COMMIT} with a
 * perfectly healthy batch is the normal state between the two calls FR19 needs,
 * and no single column expresses both.
 */
public enum UploadStatus {
    /** Stored and hashed; not yet examined. */
    RECEIVED,
    /** Sheets, headers or template version wrong. Nothing was staged. */
    REJECTED_STRUCTURE,
    /** Byte-identical to a workbook already accepted for this temple and capability. */
    REJECTED_DUPLICATE,
    /** Validated and previewed. Waiting for the uploader to confirm the import. */
    AWAITING_COMMIT,
    /** Confirmed and loaded. */
    COMMITTED,
    /** Validated but never confirmed, and superseded or expired. */
    ABANDONED
}
