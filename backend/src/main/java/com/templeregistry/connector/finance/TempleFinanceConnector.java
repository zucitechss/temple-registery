package com.templeregistry.connector.finance;

import com.templeregistry.entity.finance.enums.FinanceCapability;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What every temple finance source system must be able to do, expressed without reference
 * to how it is reached.
 *
 * <p>This contract describes a <b>synchronization capability</b>, not a transport. Nothing
 * here mentions a connection, a client, a query, a file or a protocol, so the same
 * interface serves all four integration mechanisms:
 *
 * <ul>
 *   <li>{@code PULL_JDBC} -- the worker reads the temple database directly</li>
 *   <li>{@code PUSH_AGENT} -- an agent inside the temple network delivers extracts, and the
 *       connector reads what was delivered</li>
 *   <li>{@code SOURCE_API} -- the temple exposes an interface the worker calls</li>
 *   <li>{@code FILE_DROP} -- the temple exports files on a schedule</li>
 * </ul>
 *
 * <p>The push and file mechanisms are not fallbacks. Across a hundred government temples,
 * permission for an inbound connection to a temple database is frequently refused as a
 * matter of policy rather than capability, so a contract that assumed the platform could
 * reach every temple would be wrong for a large share of them. That is why there is no
 * {@code getConnection()} and no {@code getClient()} here: either would decide the
 * deployment model for every future temple on behalf of the first one.
 *
 * <h2>Credentials</h2>
 *
 * <p>No method takes or returns a credential. A connector receives only
 * {@link SourceSystemDescriptor#credentialRef()}, an alias, and exchanges it for real
 * credentials through the provider that exists solely in the sync-worker runtime
 * (FIN-D-002, FIN-D-009). Implementations live in the worker; this contract is visible to
 * both runtimes, which is safe precisely because it can carry no secret.
 *
 * <h2>Capabilities are declared, not assumed</h2>
 *
 * <p>{@link #describeCapabilities()} is a statement of what the source genuinely records.
 * A connector must omit anything its source does not hold rather than returning empty
 * results for it, because an empty result is indistinguishable from a real zero and would
 * let the platform report that a temple spent nothing when the truth is that nobody knows.
 *
 * <h2>Extraction and reconciliation must stay independent</h2>
 *
 * <p>{@link #extract} and {@link #sourceTotals} must not share their aggregation. If
 * {@code sourceTotals} were computed from the same query as {@code extract}, reconciliation
 * would compare a number with itself and pass while the extraction query was wrong -- which
 * is the exact class of error it exists to catch. The two methods also work along different
 * axes, which makes the separation natural: extraction follows the change axis
 * ({@link SyncContext#changedSince()}), reconciliation follows business dates
 * ({@link DateRange}).
 *
 * <p>Implementations are stateless and must be safe for concurrent use across temples.
 */
public interface TempleFinanceConnector {

    /**
     * Identity of this connector, including which integration mechanism it implements.
     *
     * @return metadata whose {@code connectorId} matches the value configuration stores in
     *         {@code fin_source_system.connector_bean}
     */
    ConnectorMetadata metadata();

    /**
     * The capabilities this connector can genuinely supply for the given source.
     *
     * <p>Takes the descriptor because two deployments of the same software can differ: one
     * temple may record payment mode where another does not. A connector that cannot vary
     * may ignore the argument.
     *
     * <p>Returning a capability is a commitment that {@link #extract} will produce
     * meaningful records for it. Omitting one is how the platform learns to report
     * NOT_AVAILABLE with a reason instead of zero.
     */
    Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source);

    /**
     * Whether the source is currently usable, without extracting anything.
     *
     * <p>Deliberately not called {@code testConnection}: for a push or file-drop source
     * there is no connection to test, and the meaningful question is whether recent data
     * has arrived.
     *
     * <p>Must not throw for an unreachable source -- an unusable source is an expected
     * operational state and is reported, not raised.
     */
    SourceProbeResult probe(SourceSystemDescriptor source);

    /**
     * A fingerprint of the source structures this connector depends on, used to detect
     * schema drift between runs (risk R9).
     *
     * <p>Empty when the source exposes nothing stable to fingerprint, which is legitimate
     * for a file drop or an opaque API. Empty means "cannot be checked", and the framework
     * treats that differently from a mismatch.
     */
    Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source);

    /**
     * Read source records for one capability within one synchronization context.
     *
     * <p>Returns a {@link Stream} rather than a collection because a first historical load
     * can run to tens of millions of source records, and nothing in the pipeline may
     * require the whole result in memory. The stream may hold a resource, so callers must
     * close it -- use try-with-resources.
     *
     * <p>Rows are returned as the source produced them. Interpretation, validation and
     * mapping happen later; a connector that cleaned values here would hide the defects
     * that staging exists to record.
     *
     * <p>A connector must never advance or report a watermark. The framework decided
     * {@link SyncContext#changedUpTo()} before calling, and stores it only if the batch
     * succeeds (FIN-D-005).
     *
     * @throws UnsupportedCapabilityException if the capability was not declared for this source
     */
    Stream<RawRow> extract(FinanceCapability capability, SyncContext context);

    /**
     * Totals computed by the source system itself, over a range of business dates.
     *
     * <p>The independence of this computation is what makes reconciliation meaningful --
     * see the class comment. It must be derived from the source engine, not from records
     * this platform has already copied.
     *
     * <p>Return {@link SourceTotals#notAvailable()} when the source cannot produce a
     * figure. Never substitute zero: "we could not ask" and "the answer is nothing" lead to
     * opposite conclusions on a finance dashboard.
     *
     * <p>Reconciliation itself -- comparison, tolerance, and the decision to publish -- is
     * not performed here. A connector that judged its own output would be marking its own
     * homework.
     *
     * @param period inclusive business-date range; unbounded for a full-history check
     * @throws UnsupportedCapabilityException if the capability was not declared for this source
     */
    SourceTotals sourceTotals(FinanceCapability capability,
                              SourceSystemDescriptor source,
                              DateRange period);

    /**
     * Guard for implementations: rejects a capability this connector does not declare.
     *
     * <p>Provided so every connector fails the same way rather than each inventing its own
     * behaviour for an unsupported request -- and so that none of them quietly returns an
     * empty result.
     */
    default void requireCapability(FinanceCapability capability, SourceSystemDescriptor source) {
        if (capability == null || !describeCapabilities(source).contains(capability)) {
            throw new UnsupportedCapabilityException(metadata().connectorId(), capability);
        }
    }
}
