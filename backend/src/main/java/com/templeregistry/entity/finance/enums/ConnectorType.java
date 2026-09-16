package com.templeregistry.entity.finance.enums;

/**
 * How the platform obtains data from a source system.
 *
 * <p>Both pull and push are first-class. Across government temples, refusal to
 * permit an inbound database connection is usually a policy position rather
 * than a technical one, so {@link #PUSH_AGENT} is often the only deployable
 * option and must not be treated as a fallback.
 */
public enum ConnectorType {
    /** Sync worker opens an outbound JDBC connection to the source database. */
    PULL_JDBC,
    /** An agent inside the temple network pushes extracts to the platform. */
    PUSH_AGENT,
    /** The source system exposes an HTTP API the sync worker calls. */
    SOURCE_API,
    /** Periodic file exports landed in an agreed location. */
    FILE_DROP
}
