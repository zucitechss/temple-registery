package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.MappingOutcome;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What one batch could not classify, worst first (FIN-054A).
 *
 * <p>Scoped to a single batch, and the batch is named. Counting the same source value across
 * batches would inflate it: re-extracting a period stages the same records again, so a value
 * present in three batches would be reported as costing three times the records it costs.
 *
 * @param syncBatchId the batch these counts are from, or null when nothing has been mapped yet
 * @param observedAt  when that batch's decisions were made
 * @param values      one entry per distinct unresolved value
 */
public record UnresolvedValueResponse(Long sourceSystemId,
                                      Long syncBatchId,
                                      MappingOutcome outcome,
                                      LocalDateTime observedAt,
                                      List<UnresolvedValue> values) {

    /**
     * @param namespace the staged field the value was read from — the namespace a new rule needs.
     *                  Null when the record carried no readable field at all
     * @param affected  how many staged records in this batch carried it
     */
    public record UnresolvedValue(String namespace,
                                  String sourceValue,
                                  long affected,
                                  LocalDateTime lastSeenAt) {
    }
}
