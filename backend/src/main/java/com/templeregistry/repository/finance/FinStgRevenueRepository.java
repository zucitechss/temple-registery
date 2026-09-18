package com.templeregistry.repository.finance;

import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.enums.StagingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Staged revenue records awaiting, or having completed, pipeline processing. */
@Repository
public interface FinStgRevenueRepository extends JpaRepository<FinStgRevenue, Long> {

    /**
     * One chunk of a batch's rows in a given state, oldest first, starting after {@code afterId}.
     *
     * <p>Keyed on the last id seen rather than an offset, and that is a correctness property
     * rather than a tuning choice. A processing loop that always asks for the first page of
     * {@code RECEIVED} rows depends for its termination on every row it read leaving that
     * state; a row that does not — because a concurrent validator claimed it first — is
     * returned again on the next pass, and the loop never ends. Advancing the key makes
     * termination structural: ids strictly increase, so each row is offered exactly once and
     * the query runs out whatever the caller manages to claim.
     *
     * <p>Safe because the status axis is monotonic: {@code RECEIVED} is the state rows are
     * created in and nothing returns them to it, so a row skipped past cannot reappear behind
     * the cursor. It is also the difference between one scan of the batch and a fresh scan per
     * chunk, which matters on a first historical load.
     *
     * @param afterId exclusive lower bound; {@code 0} to start at the beginning
     */
    List<FinStgRevenue> findBySyncBatchIdAndValidationStatusAndIdGreaterThanOrderByIdAsc(
            Long syncBatchId, StagingStatus validationStatus, Long afterId, Pageable pageable);

    long countBySyncBatchIdAndValidationStatus(Long syncBatchId, StagingStatus validationStatus);

    /** Everything a batch staged, whatever state it has reached. Source of {@code rows_extracted}. */
    long countBySyncBatchId(Long syncBatchId);

    /**
     * Marks the rows that contributed to a written fact as {@code LOADED} (FIN-056).
     *
     * <p>Conditional on {@code expected} for the same reason {@link #transition} is: it makes
     * this a claim rather than an overwrite, so a re-run counts only the rows it actually moved
     * and {@code rows_loaded} cannot drift above the rows that exist. A row already {@code
     * LOADED} matches nothing and is not counted twice.
     *
     * <p>In bulk because a load writes one fact from many rows: one statement per contributing
     * record would put a round trip on the hot path of exactly the operation that handles the
     * most rows.
     *
     * @return how many rows this call moved, which is the number that had not been loaded before
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE FinStgRevenue s
           SET s.validationStatus = :next,
               s.updatedAt        = :now
         WHERE s.id IN :ids
           AND s.validationStatus = :expected
        """)
    int markLoaded(@Param("ids") List<Long> ids,
                   @Param("expected") StagingStatus expected,
                   @Param("next") StagingStatus next,
                   @Param("now") LocalDateTime now);

    /**
     * Moves one row between states, but only from the state the caller expected.
     *
     * <p>The {@code expected} predicate is what makes the transition a claim rather than an
     * overwrite: two validators running against the same batch cannot both act on one row,
     * because the second update matches nothing and returns 0. Without it, both would record
     * a rejection and the batch's error count would exceed the rows that were actually
     * rejected.
     *
     * <p>Also what prevents a terminal row being revived: a {@code REJECTED} or {@code LOADED}
     * row never matches {@code expected = RECEIVED}.
     *
     * <p>{@code updatedAt} is set explicitly because a bulk JPQL update bypasses Hibernate's
     * {@code @UpdateTimestamp}.
     *
     * @return 1 if this caller claimed the row, 0 if somebody else already did
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE FinStgRevenue s
           SET s.validationStatus = :next,
               s.rejectionReason  = :reason,
               s.updatedAt        = :now
         WHERE s.id = :id
           AND s.validationStatus = :expected
        """)
    int transition(@Param("id") Long id,
                   @Param("expected") StagingStatus expected,
                   @Param("next") StagingStatus next,
                   @Param("reason") String reason,
                   @Param("now") LocalDateTime now);

    /**
     * A bounded sample of a source system's most recent staged payloads (FIN-054A).
     *
     * <p>Used to answer the one question a mapping rule's namespace depends on: which fields does
     * this source actually emit. There is no registry of those field names anywhere -- the
     * connector chooses them and the resolver derives what it reads from the rules -- so the only
     * evidence is the payloads themselves.
     *
     * <p>Newest first and capped by the caller, because the answer is a vocabulary rather than a
     * census: the distinct keys stabilise after a handful of rows, and a source system with a
     * first historical load has millions of them.
     */
    @Query("""
        SELECT s.rawJson
          FROM FinStgRevenue s
         WHERE s.sourceSystemId = :sourceSystemId
         ORDER BY s.id DESC
        """)
    List<String> samplePayloads(@Param("sourceSystemId") Long sourceSystemId, Pageable pageable);
}
