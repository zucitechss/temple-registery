package com.templeregistry.dto.response.finance;

/** One value a rule is allowed to name (FIN-054A). The platform-wide revenue taxonomy. */
public record CanonicalValueResponse(String categoryCode,
                                     String categoryName,
                                     String description,
                                     int displayOrder) {
}
