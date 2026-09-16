package com.templeregistry.service.finance.sync;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

/**
 * Fails worker startup if the sync-worker runtime has come up as a web application.
 *
 * <p>The sync worker is the only process holding temple source credentials and the only
 * one able to reach a temple network. If it also served HTTP, every controller in the
 * artifact would be reachable from a process that can open a connection to a temple
 * database -- which reassembles exactly the coupling ADR-001 forbids, without anyone
 * having written a line of code that connects the two.
 *
 * <p>{@code application-sync-worker.yml} sets {@code spring.main.web-application-type=none}
 * to prevent this. That property is easy to lose in a deployment override, so the
 * invariant is also asserted here: a misconfigured worker refuses to start rather than
 * starting in a state nobody intended.
 *
 * <p>Failing closed is the deliberate choice. A worker that will not start is an obvious,
 * loud problem; a worker quietly serving HTTP while holding temple credentials is an
 * invisible one.
 */
@RequiredArgsConstructor
@Slf4j
public class SyncWorkerBoundaryGuard {

    private final ApplicationContext applicationContext;
    private final SyncWorkerProperties properties;

    @PostConstruct
    void assertNonWebRuntime() {
        if (applicationContext instanceof WebApplicationContext) {
            throw new IllegalStateException(
                    "Finance sync worker started as a WEB application. The sync worker holds temple "
                            + "source credentials and must not serve HTTP. Set "
                            + "spring.main.web-application-type=none for the sync-worker profile.");
        }

        log.info("[FinanceSync] Sync worker runtime active (instance: {}). Web layer disabled. "
                        + "Extraction enabled: {}",
                properties.getInstanceId(), properties.isEnabled());

        if (!properties.isEnabled()) {
            log.info("[FinanceSync] trm.finance.sync.enabled=false - no temple source will be contacted.");
        }
    }
}
