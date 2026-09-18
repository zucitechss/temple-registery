package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.MappingType;

import java.time.LocalDateTime;

/**
 * One mapping rule as the administrative screen sees it (FIN-054A).
 *
 * @param namespace     the staged field this rule reads, split out of the stored value
 * @param sourceValue   the raw value this rule matches, split out of the stored value
 * @param storedValue   the single column as it actually is, so what is shown can be reconciled
 *                      with what is in the database
 * @param wellFormed    false when {@code storedValue} names no field. Such a rule is saved, shows
 *                      as active, and can never match anything — which is exactly the defect an
 *                      administrator opens this screen to find, so it is reported rather than
 *                      hidden or silently corrected
 * @param canonicalValueKnown whether {@code canonicalValue} is a live category. A rule naming one
 *                      that has since been retired resolves to INVALID_CONFIGURATION at run time
 * @param version       pass back on update; a stale one is refused
 */
public record MappingRuleResponse(Long id,
                                  Long sourceSystemId,
                                  Long templeId,
                                  MappingType mappingType,
                                  String namespace,
                                  String sourceValue,
                                  String storedValue,
                                  boolean wellFormed,
                                  String sourceLabel,
                                  String canonicalValue,
                                  boolean canonicalValueKnown,
                                  int priority,
                                  boolean active,
                                  String notes,
                                  Integer version,
                                  Long createdBy,
                                  LocalDateTime createdAt,
                                  Long updatedBy,
                                  LocalDateTime updatedAt) {
}
