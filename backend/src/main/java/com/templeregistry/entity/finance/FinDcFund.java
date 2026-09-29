package com.templeregistry.entity.finance;

import com.templeregistry.entity.finance.enums.WorkStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A fund approved by the DC office (V133, FR1.4 and FR5).
 *
 * <p>An approval, not a transaction: it exists from the moment the sanction
 * letter is signed and before a rupee is spent. The spend side is
 * {@link FinFundUtilisation}, one to many.
 *
 * <p><b>FR6 is this entity filtered</b> to {@link WorkStatus#IN_PROGRESS}. The
 * specification calls ongoing works a subset of the fund report, so there is no
 * separate work-project table: it would carry a copy of the fund name, amount
 * and approver, and the copies would disagree within a month.
 *
 * <p>{@link #approvedAmount} is nullable for the same reason every measure is
 * (ADR-007). A fund recorded from a sanction letter whose amount is illegible is
 * not a fund of zero rupees, and the FR5 chart renders it as unavailable rather
 * than as a bar of height nought.
 *
 * <p>The balance is deliberately not stored. It is approved minus the sum of
 * utilisations, it changes on every payment, and a stored copy is a second
 * number that can disagree with the rows it summarises.
 */
@Entity
@Table(
    name = "fin_dc_fund",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_fdf_record", columnNames = {"source_system_id", "source_record_ref"}),
    indexes = {
        @Index(name = "idx_fdf_temple_fy",     columnList = "temple_id, financial_year"),
        @Index(name = "idx_fdf_temple_status", columnList = "temple_id, work_status"),
        @Index(name = "idx_fdf_sanction",      columnList = "sanction_letter_ref"),
        @Index(name = "idx_fdf_batch",         columnList = "sync_batch_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinDcFund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Registry temple id. The isolation key; never a hardcoded value. */
    @Column(name = "temple_id", nullable = false)
    private Long templeId;

    /** {@code fin_source_system.id}, in practice a MANUAL_ENTRY or FILE_UPLOAD channel. */
    @Column(name = "source_system_id", nullable = false)
    private Long sourceSystemId;

    @Column(name = "sync_batch_id", nullable = false)
    private Long syncBatchId;

    /** Row-exact provenance handle. In the grain. */
    @Column(name = "source_record_ref", nullable = false, length = 200)
    private String sourceRecordRef;

    /** FR5.1 */
    @Column(name = "fund_name", nullable = false, length = 300)
    private String fundName;

    /** FR5.2. Null only where the sanction letter states none. */
    @Column(name = "approval_date")
    private LocalDate approvalDate;

    /** FR5.3, funds to be used by. Drives the FR6 deadline view. */
    @Column(name = "use_by_date")
    private LocalDate useByDate;

    /** FR5.4, person. A name as written on the sanction letter, not a user account. */
    @Column(name = "approved_by_name", length = 200)
    private String approvedByName;

    /** FR5.4, department. */
    @Column(name = "approved_by_department", length = 200)
    private String approvedByDepartment;

    /** FR5.5. The requirement says this is recorded only if available, so nullable by specification. */
    @Column(name = "fund_category", length = 100)
    private String fundCategory;

    /** FR5.6. The reference quoted in correspondence with the DC office. */
    @Column(name = "sanction_letter_ref", length = 150)
    private String sanctionLetterRef;

    /** Null means not recorded (ADR-007). Never defaulted to zero. */
    @Column(name = "approved_amount", precision = 18, scale = 2)
    private BigDecimal approvedAmount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "work_status", nullable = false, length = 30)
    private WorkStatus workStatus = WorkStatus.NOT_STARTED;

    @Column(name = "work_description", length = 1000)
    private String workDescription;

    /** FY of the approval. FR1 box 4 sums utilisation within the current one. */
    @Column(name = "financial_year", nullable = false, length = 10)
    private String financialYear;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
