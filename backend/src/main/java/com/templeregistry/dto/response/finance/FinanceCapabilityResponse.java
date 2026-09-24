package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.DataAvailability;
import com.templeregistry.entity.finance.enums.FinanceCapability;

import java.time.LocalDate;
import java.util.List;

/**
 * What one temple can answer for one financial question, and why (FIN-082, API_CONTRACT §4.1).
 *
 * <p>This is what the dashboard composes itself from rather than branching on temple identity —
 * a second temple is onboarded by declaring rows here, never by a frontend change.
 */
public record FinanceCapabilityResponse(
        FinanceCapability capability,
        DataAvailability availability,
        String reason,
        LocalDate coverageFrom,
        LocalDate coverageTo,
        List<String> knownGaps) {

    /** One temple's full capability list (FIN-082). */
    public record ForTemple(Long templeId, List<FinanceCapabilityResponse> capabilities) {
    }
}
