package com.templeregistry.entity.trust;

import com.templeregistry.entity.base.BaseEntity;
import com.templeregistry.util.AesEncryptionConverter;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

@Entity
@Table(name = "board_members", indexes = {
        @Index(name = "idx_board_members_trust_id", columnList = "trust_id")
})
@SQLRestriction("is_deleted = false")
@SQLDelete(sql = "UPDATE board_members SET is_deleted = true, updated_at = NOW(6) WHERE id = ? AND lock_version = ?")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class BoardMember extends BaseEntity {

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "trust_id", nullable = false) private Long trustId;

    @Column(name = "full_name", nullable = false, length = 200) private String fullName;

    @Convert(converter = AesEncryptionConverter.class)
    @Column(name = "aadhaar_encrypted", columnDefinition = "TEXT") private String aadhaarEncrypted;

    /**
     * HMAC-SHA256 of the plaintext Aadhaar number.
     * Used for deterministic duplicate detection — AES-GCM with random IV cannot be used for lookups.
     * Never exposed in any API response.
     */
    @Column(name = "aadhaar_hash", length = 64) private String aadhaarHash;

    /**
     * Last 4 digits of the plaintext Aadhaar number, stored for masked display.
     * Avoids decrypting the ciphertext just to produce a mask.
     */
    @Column(name = "aadhaar_last4", length = 4) private String aadhaarLast4;

    @Column(name = "designation", length = 150) private String designation;

    @Column(name = "appointment_date") private LocalDate appointmentDate;

    @Column(name = "tenure_end_date") private LocalDate tenureEndDate;

    @Column(name = "contact_number", length = 15) private String contactNumber;

    @Column(name = "address", columnDefinition = "TEXT") private String address;

    @Builder.Default
    @Column(name = "is_current", nullable = false) private boolean isCurrent = true;

    // DC Governance Field — dcFlagReason removed (V80 migration)
    @Builder.Default
    @Column(name = "is_verified_by_dc", nullable = false) private boolean isVerifiedByDc = false;

    public String getMaskedAadhaar() {
        if (aadhaarLast4 != null && aadhaarLast4.length() == 4) {
            return "XXXX-XXXX-" + aadhaarLast4;
        }
        // Legacy fallback: attempt to derive from decrypted value if last4 not yet populated
        return null;
    }
}
