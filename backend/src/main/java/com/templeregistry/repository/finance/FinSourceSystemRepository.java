package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinSourceSystem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Registry of external systems that supply financial data. */
@Repository
public interface FinSourceSystemRepository extends JpaRepository<FinSourceSystem, Long> {

    List<FinSourceSystem> findByTempleIdAndDeletedFalse(Long templeId);

    /**
     * Every registered source system (FIN-054A).
     *
     * <p>For the administrative selector, which is filtered down to the caller's jurisdiction in
     * the service. Unfiltered here because there is no join from a source system to a district:
     * the path runs through the temple, and the traversal that scopes it already lives in
     * {@code JurisdictionGuard}.
     */
    List<FinSourceSystem> findByDeletedFalse();

    Optional<FinSourceSystem> findByTempleIdAndSystemCodeAndDeletedFalse(Long templeId, String systemCode);

    /**
     * Any source system with this code for this temple, <b>including a retired one</b> (FIN-140).
     *
     * <p>{@code uk_fss_temple_system} is {@code (temple_id, system_code)} and does not include
     * {@code is_deleted}, so a soft-deleted row still holds its code. A registration check that
     * filtered deleted rows out would pass and then hit the constraint, turning a refusal a caller
     * could act on into an opaque server error.
     */
    Optional<FinSourceSystem> findByTempleIdAndSystemCode(Long templeId, String systemCode);

    /** Sources the scheduler is permitted to contact. Used only by the sync worker. */
    List<FinSourceSystem> findBySyncEnabledTrueAndDeletedFalse();

    /**
     * The source system row, taken with a database write lock (FIN-058).
     *
     * <p>Used by {@code ManualSyncTrigger} so that deciding whether a run may start, and inserting
     * the batch that starts it, happen while nobody else can decide the same thing. The check and
     * the insert are two statements; without the lock, two callers both read "no batch is active",
     * both insert, and the same window is extracted from a temple's database twice.
     *
     * <p>The lock is on this row rather than on {@code fin_sync_batch} because the resource being
     * made exclusive is the source system, and the batch row that would otherwise be locked does
     * not exist yet. It is a database lock, so it holds across worker processes -- a JVM mutex
     * would protect one instance and silently stop protecting anything the day a second is deployed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM FinSourceSystem s WHERE s.id = :id")
    Optional<FinSourceSystem> findByIdForUpdate(@Param("id") Long id);
}
