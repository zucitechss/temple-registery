package com.templeregistry.connector.finance;

/**
 * A stable hash of the parts of a source schema that extraction depends on.
 *
 * <p>Guards against silent source schema change (architecture risk R9). A temple may rename
 * a column, change a type, or add a table during a routine upgrade without telling anyone.
 * Comparing the fingerprint before a batch turns that into a failed batch with a clear
 * cause, instead of a load that succeeds and produces wrong figures.
 *
 * <p>The value is opaque to the framework: each connector decides what is worth hashing.
 * Length is capped to match the storage column so an over-long value fails here rather than
 * on insert.
 */
public record SchemaFingerprint(String value) {

    /** Matches the width of {@code fin_source_system.schema_fingerprint}. */
    public static final int MAX_LENGTH = 64;

    public SchemaFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SchemaFingerprint value must not be blank.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "SchemaFingerprint value exceeds " + MAX_LENGTH + " characters: " + value.length());
        }
    }

    public boolean matches(SchemaFingerprint other) {
        return other != null && value.equals(other.value());
    }
}
