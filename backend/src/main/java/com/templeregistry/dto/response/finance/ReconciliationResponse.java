package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What was checked for one temple's financial year, and what each check found (FIN-083,
 * API_CONTRACT §4 `/finance/reconciliation`).
 *
 * <p>Every recorded check is returned, not only the ones that failed — {@code checkType} tells a
 * reader which checks are authoritative today and which are advisory until a connector implements
 * {@code sourceTotals()} ({@link ReconciliationCheckType}'s own javadoc). Hiding the advisory ones
 * would let a run of green authoritative checks read as "the figures agree with the source",
 * which nobody has verified.
 */
public record ReconciliationResponse(
        Long templeId,
        String financialYear,
        List<CheckResult> checks) {

    public record CheckResult(
            Long sourceSystemId,
            ReconciliationCheckType checkType,
            /** What was compared, e.g. {@code GROSS_AMOUNT}, {@code TRANSACTION_COUNT}. */
            String metric,
            BigDecimal sourceTotal,
            BigDecimal centralTotal,
            BigDecimal difference,
            BigDecimal differencePct,
            ReconciliationStatus status,
            String statusReason,
            LocalDateTime checkedAt) {
    }
}
