package com.templeregistry.entity.finance.enums;

/**
 * How the platform obtains data from a source system.
 *
 * <p>Both pull and push are first-class. Across government temples, refusal to
 * permit an inbound database connection is usually a policy position rather
 * than a technical one, so {@link #PUSH_AGENT} is often the only deployable
 * option and must not be treated as a fallback.
 *
 * <h2>Two of these have no connector (V122)</h2>
 *
 * <p>{@link #MANUAL_ENTRY} and {@link #FILE_UPLOAD} exist because the Financial
 * Dashboard specification adds two ways for data to enter the platform that are
 * not extractions: a temple-staff form and a spreadsheet. Registering them here,
 * as source systems like any other, is what lets batching, provenance, per-row
 * error recording, capability declaration and the publication gate apply to them
 * without being written a second time.
 *
 * <p>They are also the only two values for which {@code connector_bean} is null,
 * and {@link #isAutomated()} is the test that distinguishes them. FR17's "any
 * temple without an automated connector" is exactly {@code !isAutomated()}, so
 * the alerting feature needs no separate flag and cannot drift out of step with
 * how a temple is actually configured.
 */
public enum ConnectorType {
    /** Sync worker opens an outbound JDBC connection to the source database. */
    PULL_JDBC,
    /** An agent inside the temple network pushes extracts to the platform. */
    PUSH_AGENT,
    /** The source system exposes an HTTP API the sync worker calls. */
    SOURCE_API,
    /** Periodic file exports landed in an agreed location. */
    FILE_DROP,
    /**
     * Temple staff enter figures through the Temple Management application.
     * No connector bean, no credential, no outbound connection: the data
     * arrives inbound over HTTP and the registry runtime reaches nothing.
     */
    MANUAL_ENTRY,
    /**
     * Temple staff upload a filled spreadsheet. Distinct from
     * {@link #FILE_DROP}, which is an automated export the worker collects
     * from an agreed location: this one is a person choosing a file, and it
     * carries an uploader, a filename and a content hash.
     */
    FILE_UPLOAD;

    /**
     * Whether this channel extracts data on a schedule without a person.
     *
     * <p>The single place the automated/manual distinction is defined. FR17
     * alerts temples that are not automated; the nightly scheduler runs only
     * those that are; and {@code connector_bean} is required for exactly the
     * values this returns {@code true} for.
     */
    public boolean isAutomated() {
        return this != MANUAL_ENTRY && this != FILE_UPLOAD;
    }
}
