package com.templeregistry.service.finance.onboarding;

/**
 * One thing wrong, or possibly wrong, with a source system's configuration (FIN-140).
 *
 * <p>Carried straight through to the API response rather than mirrored into a separate DTO. It
 * holds no entity, no temple figure and no source connection detail — only a code, a severity, a
 * subject and a sentence — so a mirror class would have had identical fields and one more place
 * for the two to drift apart.
 *
 * @param code     stable, machine-readable, safe to branch on in a client or assert in a test
 * @param severity {@link ReadinessStatus#WARNING} or {@link ReadinessStatus#BLOCKED}; never
 *                 {@code READY}, which is the absence of findings rather than one of them
 * @param subject  what the finding is about — a capability name, a rule id, a metric. Null when
 *                 the finding concerns the source system as a whole
 * @param message  a complete sentence for a human, stating what is wrong and what it would cause.
 *                 Displayed verbatim, in the same spirit as {@code availability_reason}
 */
public record ReadinessFinding(String code,
                               ReadinessStatus severity,
                               String subject,
                               String message) {

    public static ReadinessFinding blocking(String code, String subject, String message) {
        return new ReadinessFinding(code, ReadinessStatus.BLOCKED, subject, message);
    }

    public static ReadinessFinding warning(String code, String subject, String message) {
        return new ReadinessFinding(code, ReadinessStatus.WARNING, subject, message);
    }
}
