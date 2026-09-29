package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.MetalType;
import com.templeregistry.entity.finance.enums.ValuationSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A precious item received by the temple (V134, FR7).
 *
 * <p>The one subject whose fields come from two different channels for the same
 * temple on the same day. The specification states it directly: counts and
 * weight are available in the temple software, purity and value are not and
 * must be captured through temple management input.
 *
 * <p><b>Two channels do not share a row.</b> Each writes its own, keyed by its
 * own {@code (source_system_id, source_record_ref)}, and the report joins them
 * on {@code (temple, received_date, metal_type)}. Letting the manual channel
 * update the connector row would make a fact mutable by a party that did not
 * produce it and leave {@code source_system_id} meaning whoever wrote last.
 *
 * <p><b>Not {@link FinRevenueFact}.</b> The value of an in-kind donation does
 * belong in revenue under the seeded IN_KIND_DONATION category. A count of items
 * and a weight in grams do not: they are not money, must never be summed with
 * money, and the daily revenue grain would destroy the per-item detail the FR7
 * grid needs.
 *
 * <p>{@link #estimatedValue} is never inferred from {@link #grossWeightG}. A
 * gram of unknown purity has no derivable worth, and a plausible wrong figure on
 * a DC dashboard is worse than an honest gap (ADR-007).
 */
@Entity
@Table(
    name = "fin_precious_item_fact",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fpif_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fpif_temple_fy",   columnList = "temple_id, financial_year, metal_type"),
        @Index(name = "idx_fpif_temple_date", columnList = "temple_id, received_date"),
        @Index(name = "idx_fpif_overlay",     columnList = "temple_id, received_date, metal_type"),
        @Index(name = "idx_fpif_batch",       columnList = "sync_batch_id"),
        @Index(name = "idx_fpif_supersede",   columnList = "temple_id, source_system_id, received_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinPreciousItemFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** A connector for counts and weight, a manual channel for purity and value. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    @Column(name = "source_of_truth_version")
    private Integer sourceOfTruthVersion;

    /** Row-exact provenance handle. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** Business date the item was received. Never the valuation date. */
    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    /** Canonical, resolved through {@code mapping_type = METAL_TYPE}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "metal_type", nullable = false, length = 30)
    private MetalType metalType;

    /** What was received, as the source or the donor described it. */
    @Column(name = "item_description", length = 500)
    private String itemDescription;

    /** FR7 counts. Null means this channel does not record them. */
    @Column(name = "item_count")
    private Integer itemCount;

    /** FR7 weight in grams. Null means not recorded by this channel. */
    @Column(name = "gross_weight_g", precision = 18, scale = 3)
    private BigDecimal grossWeightG;

    /** FR7 purity. Not available from the known source: manual input only. */
    @Column(name = "purity_karat", precision = 6, scale = 3)
    private BigDecimal purityKarat;

    /** Fine metal content where it has been assayed. Never derived from gross weight and purity. */
    @Column(name = "net_weight_g", precision = 18, scale = 3)
    private BigDecimal netWeightG;

    /** FR7 value. Null until somebody values it, and never inferred from weight (ADR-007). */
    @Column(name = "estimated_value", precision = 18, scale = 2)
    private BigDecimal estimatedValue;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    /** Who says so, recorded beside the figure so a declared value is never read as an assessed one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_source", length = 50)
    private ValuationSource valuationSource;

    /** When the valuation was made. A gold price is only true on a date. */
    @Column(name = "valued_on")
    private LocalDate valuedOn;

    /**
     * The consignment row this valuation refers to, where the valuer could
     * identify it. Null where the overlay matches only on date and metal type.
     */
    @Column(name = "valuation_of_item_id")
    private Long valuationOfItemId;

    /** Pseudonymous handle where the source has one. Never a donor name, address or contact. */
    @Column(name = "donor_ref", length = 100)
    private String donorRef;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
