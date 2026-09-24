package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One capability declaration, as an administrator configures it (FIN-140-B).
 *
 * <p>Distinct from {@link FinanceCapabilityResponse}, which serves the dashboard, and deliberately
 * not merged with it. That one answers "what can this temple tell me"; this one answers "what has
 * somebody declared, when, and against which source system" — so it carries the id, the owning
 * source system, the lock version and the review timestamp, none of which a reporting consumer has
 * any business seeing. Reusing one record for both would have meant either leaking administrative
 * fields into a four-role reporting response or hiding them from the screen that edits them.
 *
 * <p>Carries no credential reference, connector bean or source database name: a capability
 * declaration is a statement about what a temple records, not about how the platform reaches it.
 */
public record CapabilityDeclarationResponse(Long id,
                                            Long sourceSystemId,
                                            Long templeId,
                                            FinanceCapability capability,
                                            DataAvailability availability,
                                            String availabilityReason,
                                            LocalDate coverageFrom,
                                            LocalDate coverageTo,
                                            List<String> knownGaps,
                                            LocalDateTime lastReviewedAt,
                                            Integer version,
                                            LocalDateTime createdAt,
                                            LocalDateTime updatedAt) {
}
