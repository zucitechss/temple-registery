package com.templeregistry.dto.response.finance;

/**
 * A source system the caller may administer mappings for (FIN-054A).
 *
 * <p>Deliberately thin. A source system row also holds a credential reference, a database name and
 * a connector bean — operational details that say how to reach a temple's live system and have no
 * business travelling to a browser to populate a dropdown.
 */
public record SourceSystemSummaryResponse(Long id,
                                          Long templeId,
                                          String templeName,
                                          String systemCode,
                                          String systemName,
                                          boolean active,
                                          long activeRuleCount) {
}
