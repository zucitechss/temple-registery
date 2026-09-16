package com.templeregistry.connector.finance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One record as the source produced it, before validation, mapping or normalization.
 *
 * <p>Values are strings on purpose. Staging exists so that a malformed source value lands
 * and is rejected <em>with a reason</em>, rather than throwing during extraction and
 * failing an entire batch. Real sources contain impossible dates, nulls where the schema
 * promises otherwise, and text in numeric columns; a typed extraction contract would turn
 * each of those into a crash at the worst moment. Money is unaffected -- a decimal
 * rendered as text and parsed back is exact.
 *
 * <p>Keys are the connector's own field names. They are meaningful only to that connector
 * and to the staging record it produces; nothing above the normalization stage may read
 * them, because that is where source vocabulary stops.
 *
 * <p>{@link #sourceRecordRef()} locates the record in the source for diagnosis and replay.
 * It is the value stored on a rejected row, so it must be specific enough for a human to
 * find the original.
 */
public record RawRow(String sourceRecordRef, Map<String, String> values) {

    public RawRow {
        if (sourceRecordRef == null || sourceRecordRef.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceRecordRef must not be blank: a rejected row that cannot be located is not diagnosable.");
        }
        if (values == null) {
            throw new IllegalArgumentException("values must not be null.");
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** The raw value for a field, or null when the source had none. */
    public String get(String field) {
        return values.get(field);
    }

    public boolean hasField(String field) {
        return values.containsKey(field);
    }
}
