package com.templeregistry.service.finance.sync;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Process-level settings for the finance sync worker.
 *
 * <p>Deliberately contains no host, URL, username, password or connection string. Those
 * belong to neither this class nor {@code fin_source_system} (FIN-D-002); connection
 * targets are a property of the connector and credentials are resolved through
 * {@link SourceCredentialProvider}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trm.finance.sync")
public class SyncWorkerProperties {

    /**
     * Master switch for extraction, defaulting to {@code false}.
     *
     * <p>Starting the worker and actually contacting temple systems are separate decisions.
     * This mirrors {@code fin_source_system.sync_enabled}, which defaults off for the same
     * reason (FIN-D-004): the worker can be deployed, observed and health-checked before
     * it is permitted to generate any traffic to a government temple.
     */
    private boolean enabled = false;

    /** Human-readable name for this worker instance, used in logs and batch records. */
    private String instanceId = "finance-sync-worker";
}
