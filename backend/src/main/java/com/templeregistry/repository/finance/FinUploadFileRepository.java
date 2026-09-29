package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinUploadFile;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Uploaded workbooks (V130, FR19).
 *
 * <p>{@link #findByTempleIdAndCapabilityAndContentSha256} is the duplicate
 * guard, and the only one of the three that can produce a message a person can
 * act on: it names the earlier upload and when it was accepted.
 */
@Repository
public interface FinUploadFileRepository extends JpaRepository<FinUploadFile, Long> {

    Optional<FinUploadFile> findByTempleIdAndCapabilityAndContentSha256(
            Long templeId, FinanceCapability capability, String contentSha256);

    Optional<FinUploadFile> findBySyncBatchId(Long syncBatchId);

    List<FinUploadFile> findByTempleIdOrderByUploadedAtDesc(Long templeId);
}
