package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * An external operational system that supplies financial data for one temple.
 *
 * <p>This is the table that resolves the identity gap between the registry and a
 * temple's own system. For Kollur it holds the mapping from registry temple
 * {@code 300001} to source {@code TempleCode 43} -- a link that exists nowhere
 * else in either system today.
 *
 * <p><b>No credential is ever stored here.</b> {@link #credentialRef} is an alias
 * resolved by the sync worker from environment/secret configuration at extraction
 * time. There is deliberately no host, port, connection-string or password column:
 * the registry runtime must not be capable of reaching a temple database, and
 * omitting the fields makes that a property of the schema rather than a rule
 * someone has to remember (ADR-001).
 *
 * <p>A temple may have several source systems -- a POS system and a separate
 * accounting package, say -- which is why this is a table rather than columns on
 * {@code temples}.
 */
@Entity
@Table(
    name = "fin_source_system",
    uniqueConstraints = @UniqueConstraint(name = "uk_fss_temple_system", columnNames = {"temple_id", "system_code"}),
    indexes = {
        @Index(name = "idx_fss_temple",  columnList = "temple_id, is_deleted"),
        @Index(name = "idx_fss_enabled", columnList = "sync_enabled, is_deleted")
    }
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinSourceSystem extends BaseEntity {

    /** Registry temple id, e.g. 300001. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** Stable short code for the source system, e.g. {@code KOLSOHAM}. */
    @Column(name = "system_code", nullable = false, length = 50)
    private String systemCode;

    @Column(name = "system_name", nullable = false, length = 200)
    private String systemName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_technology", nullable = false, length = 30)
    private SourceTechnology sourceTechnology;

    @Enumerated(EnumType.STRING)
    @Column(name = "connector_type", nullable = false, length = 30)
    private ConnectorType connectorType;

    /**
     * Spring bean name of the {@code TempleFinanceConnector} implementation,
     * e.g. {@code kollurFinanceConnector}. Resolved only inside the sync worker.
     */
    @Column(name = "connector_bean", nullable = false, length = 150)
    private String connectorBean;

    /** Temple identifier as used <em>inside the source system</em>, e.g. {@code 43}. */
    @Column(name = "source_temple_code", length = 50)
    private String sourceTempleCode;

    /** Documentation only, e.g. {@code KOLSOHAM_LOCAL}. Never used to build a connection. */
    @Column(name = "source_database_name", length = 100)
    private String sourceDatabaseName;

    /** Secret alias/key -- never a credential value. */
    @Column(name = "credential_ref", length = 200)
    private String credentialRef;

    @Column(name = "sync_schedule_cron", length = 50)
    private String syncScheduleCron;

    /**
     * Kill switch. Defaults to {@code false} so that registering a source system
     * never, by itself, starts network traffic to a temple.
     */
    @Builder.Default
    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled = false;

    /** Hours after which reported data is flagged STALE for this temple. */
    @Builder.Default
    @Column(name = "staleness_threshold_hours", nullable = false)
    private Integer stalenessThresholdHours = 48;

    /** Hash of the source schema, compared between syncs to detect drift. */
    @Column(name = "schema_fingerprint", length = 64)
    private String schemaFingerprint;

    @Builder.Default
    @Column(name = "source_timezone", nullable = false, length = 50)
    private String sourceTimezone = "Asia/Kolkata";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
