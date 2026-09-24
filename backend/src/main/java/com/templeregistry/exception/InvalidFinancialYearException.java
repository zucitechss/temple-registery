package com.templeregistry.exception;

/**
 * A {@code financialYear} request parameter is not canonical {@code yyyy-yy} form (FIN-081).
 *
 * <p>Thrown rather than left to fall through to {@code FinancialYear.startOf}'s
 * {@code IllegalArgumentException}, which {@link GlobalExceptionHandler} has no handler for and
 * would otherwise answer with a 500 — the same class of contract defect recorded as HANDOFF
 * limitation 62 for a missing required parameter.
 */
public class InvalidFinancialYearException extends RuntimeException {

    public InvalidFinancialYearException(String financialYear) {
        super("'" + financialYear + "' is not a financial year in the form yyyy-yy, e.g. 2025-26.");
    }
}
