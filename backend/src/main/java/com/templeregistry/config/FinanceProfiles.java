package com.templeregistry.config;

/**
 * Spring profile names that separate the two runtimes built from this one artifact.
 *
 * <p>The Temple Registry deploys as a single jar started in one of two ways:
 *
 * <pre>
 *   --spring.profiles.active=prod           registry / web / API runtime
 *   --spring.profiles.active=sync-worker    finance ingestion runtime
 * </pre>
 *
 * <p>Only the sync worker holds temple source credentials and only the sync worker can
 * reach a temple network. The registry runtime has no code path to a temple database
 * because the beans that could open one are never created in it (ADR-001).
 *
 * <p>Constants rather than string literals so that a typo in a {@code @Profile} annotation
 * is a compile error. A mistyped profile string fails open -- the bean would load in the
 * registry runtime -- which is precisely the failure this boundary exists to prevent.
 */
public final class FinanceProfiles {

    private FinanceProfiles() {
    }

    /** The finance ingestion runtime. Holds connectors, credentials and sync scheduling. */
    public static final String SYNC_WORKER = "sync-worker";

    /**
     * Everything that is not the sync worker: the registry/web/API runtime, and all
     * existing test profiles.
     */
    public static final String REGISTRY = "!" + SYNC_WORKER;
}
