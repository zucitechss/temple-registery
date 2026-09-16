package com.templeregistry.entity.finance;

import com.templeregistry.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Canonical identity of one service a temple offers.
 *
 * <p>Scoped per temple, unlike {@link FinRevenueCategory}: service catalogues are
 * genuinely temple-specific, and pretending otherwise would force every temple's
 * sevas into one shared list. Each service still resolves to a platform-wide
 * category, which is what makes temples comparable without making them identical.
 *
 * <p>{@link #rateCardAmount} is the published list price. It is stored as context
 * and is <b>never</b> revenue: what was charged and what the rate card says
 * legitimately differ, and summing this column would report a price list as
 * income.
 */
@Entity
@Table(
    name = "fin_service_dim",
    uniqueConstraints = @UniqueConstraint(name = "uk_fsd_temple_service",
                                          columnNames = {"temple_id", "service_code"}),
    indexes = {
        @Index(name = "idx_fsd_category", columnList = "category_id, is_deleted"),
        @Index(name = "idx_fsd_temple",   columnList = "temple_id, is_active, is_deleted")
    }
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FinServiceDim extends BaseEntity {

    /** Registry temple id. The isolation key; never a hardcoded value. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** Canonical, stable code within this temple. */
    @Column(name = "service_code", nullable = false, length = 100)
    private String serviceCode;

    @Column(name = "service_name_en", nullable = false, length = 200)
    private String serviceNameEn;

    /** Local script (e.g. Kannada). Stored directly -- the schema is utf8mb4. */
    @Column(name = "service_name_local", length = 200)
    private String serviceNameLocal;

    /** {@code fin_revenue_category.id}. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** List price. Never revenue, and never summed as such. */
    @Column(name = "rate_card_amount", precision = 18, scale = 2)
    private BigDecimal rateCardAmount;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Opaque provenance handle for tracing back to staging. Not a query. */
    @Column(name = "source_record_ref", length = 200)
    private String sourceRecordRef;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
