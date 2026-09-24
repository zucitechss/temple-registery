package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PeriodType;

/**
 * The columns {@code uk_farp_grain} is unique over (FIN-070).
 *
 * <p>A record, so equality is by value and grouping facts by it is free — the same device
 * {@code RevenueNormalizer.GrainKey} uses against {@code uk_frf_grain}, and carrying the same
 * obligation: <b>this and the database constraint must agree</b>. Anything added to one belongs in
 * the other, or one period lands as two rows and a total is counted twice.
 *
 * <p>Unlike that key, every component here is non-null, which is why {@code fin_agg_revenue_period}
 * needs no generated stand-in columns. That is not an accident of the data: {@code category_id} is
 * {@code NOT NULL} on the fact, and the deliberate absence of a nullable "all categories" total row
 * is what keeps it so (FIN-070B D5).
 *
 * <p>{@code sourceSystemId} is in the key and is never aggregated away. A temple's figure is the sum
 * of its sources, computed at read time; an aggregate that had already summed across them could not
 * be gated, because {@code ReconciliationGate} decides per (temple, source, financial year).
 */
public record RevenueAggregateKey(long templeId,
                                  long sourceSystemId,
                                  PeriodType periodType,
                                  String periodKey,
                                  long categoryId,
                                  PaymentMode paymentMode) {
}
