package com.templeregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for both runtimes built from this artifact.
 *
 * <pre>
 *   --spring.profiles.active=prod           registry / web / API
 *   --spring.profiles.active=sync-worker    finance ingestion (non-web)
 * </pre>
 *
 * <p>{@code @EnableScheduling} deliberately does <b>not</b> live here. It is declared by
 * {@link com.templeregistry.config.SchedulingConfig}, which is active only outside the
 * sync-worker profile, so that the worker does not start a second copy of every existing
 * background job. See that class for why.
 */
@SpringBootApplication
@EnableAsync
public class TempleRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(TempleRegistryApplication.class, args);
    }
}
