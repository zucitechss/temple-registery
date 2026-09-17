package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a staged {@code raw_json} payload into flat named fields.
 *
 * <p>Staging holds what a connector delivered, with the source's own field names and values as
 * strings (V113). Every stage downstream needs the same flattening, and needs it to behave
 * identically: mapping matches a rule against a field, normalization reads a declared field,
 * and the two disagreeing about what a payload contains would be a defect nobody could see
 * from either side.
 *
 * <p>Two rules matter more than they look:
 *
 * <ul>
 *   <li>A JSON null stays null. The source had no value for that field, which is not the same
 *       as an empty string and must never quietly become one.
 *   <li>Nested objects and arrays are skipped rather than stringified. A field whose value is
 *       a structure is not a value any rule or declaration can be about, and turning it into
 *       {@code "[1,2]"} would invite a match on text nobody intended.
 * </ul>
 */
public final class StagedPayload {

    private StagedPayload() {
    }

    /**
     * Flattens a payload's scalar fields.
     *
     * @return the fields in payload order; empty if the payload is absent, unreadable or not a
     *         JSON object. An empty map is a legitimate answer — it means there is nothing to
     *         read, which each caller turns into its own explicit outcome rather than a guess.
     */
    public static Map<String, String> read(ObjectMapper objectMapper, String rawJson) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (rawJson == null || rawJson.isBlank()) {
            return fields;
        }
        try {
            JsonNode payload = objectMapper.readTree(rawJson);
            if (payload == null || !payload.isObject()) {
                return fields;
            }
            for (Map.Entry<String, JsonNode> field : payload.properties()) {
                JsonNode value = field.getValue();
                if (value.isContainerNode()) {
                    continue;
                }
                fields.put(field.getKey(), value.isNull() ? null : value.asText());
            }
        } catch (Exception unreadable) {
            return new LinkedHashMap<>();
        }
        return fields;
    }
}
