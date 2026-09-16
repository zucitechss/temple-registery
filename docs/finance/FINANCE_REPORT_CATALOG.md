# Finance Report Catalog

**Status:** DRAFT — specification only. No report implemented.
**Date:** 2026-09-16
**Parent:** [MULTI_TEMPLE_FINANCE_ARCHITECTURE.md](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md)

This catalog is **generic**. Every report is defined against the canonical model, never against a source schema. "Kollur" appears only in the support column, as evidence that the generic definition is satisfiable by a real source.

**Support legend:** ✅ full · 🟡 partial · ❌ not supported (renders `NOT_AVAILABLE` with reason) · ➖ not applicable

---

## Catalog Summary

| # | Report | Kollur | Future temples | Canonical source |
|---:|---|:--:|:--:|---|
| R1 | Total Money Collected | ✅ | ✅ | `fin_agg_revenue_period` |
| R2 | Revenue Trend | ✅ | ✅ | `fin_agg_revenue_period` |
| R3 | Monthly Revenue | ✅ | ✅ | `fin_agg_revenue_period` |
| R4 | Revenue by Category | ✅ | ✅ | `fin_agg_revenue_period` |
| R5 | Revenue by Seva (Split) | ✅ | ✅ | `fin_agg_revenue_service` |
| R6 | Receipt Count | ✅ | ✅ | `fin_agg_revenue_period` |
| R7 | Cancellations | ✅ | ✅ | `fin_cancellation` |
| R8 | Payment Mode | 🟡 inferred | ✅ where recorded | `fin_revenue_fact` |
| R9 | High-value Sevas | ✅ | ✅ | `fin_agg_revenue_service` |
| R10 | Gold Count | ✅ | 🟡 | `fin_precious_metal_fact` |
| R11 | Silver Count | ✅ | 🟡 | `fin_precious_metal_fact` |
| R12 | Gold Weight | ✅ | 🟡 | `fin_precious_metal_fact` |
| R13 | Silver Weight | ✅ | 🟡 | `fin_precious_metal_fact` |
| R14 | Gold Value | ❌ | 🟡 | `fin_precious_metal_fact` |
| R15 | Silver Value | ❌ | 🟡 | `fin_precious_metal_fact` |
| R16 | Nirantara Subscriptions | ✅ | 🟡 | `fin_nirantara_subscription` |
| R17 | Nirantara Payments | 🟡 to FY2023-24 | 🟡 | `fin_nirantara_payment` |
| R18 | Nirantara Execution | ❌ | 🟡 | `fin_nirantara_execution` |
| R19 | Expenses | ❌ | 🟡 | `fin_expense_fact` *(deferred)* |
| R20 | Expense Categories | ❌ | 🟡 | `fin_expense_fact` *(deferred)* |
| R21 | Net Income | ❌ | 🟡 | derived |
| R22 | Government Grants | ❌ | 🟡 | `fin_grant` *(deferred)* |
| R23 | Approved Funds vs Spent | ❌ | 🟡 | `fin_grant_utilisation` *(deferred)* |
| R24 | Fund Utilization | ❌ | 🟡 | `fin_grant_utilisation` *(deferred)* |
| R25 | Ongoing Works | ❌ | 🟡 | `fin_work_project` *(deferred)* |
| R26 | In-kind Donations | ✅ | 🟡 | `fin_revenue_fact` |
| R27 | Revenue by Counter/Operator | ✅ | 🟡 | `fin_revenue_fact` |
| R28 | Data Availability Matrix | ✅ | ✅ | `fin_temple_capability` |
| R29 | Sync & Reconciliation Status | ✅ | ✅ | `fin_sync_batch` |

**Kollur: 14 fully supported, 3 partial, 11 not supported, 1 n/a.** Every "not supported" renders an explicit reason, never a zero.

---

## R1 — Total Money Collected

| | |
|---|---|
| **Purpose** | Headline revenue KPI for a temple and period |
| **Business definition** | Net cash-equivalent revenue recognised from receipts issued in the period, excluding cancelled receipts |
| **Canonical data** | `fin_agg_revenue_period` (`period_type=FY`) |
| **Source (Kollur)** | `DailySevaNew.Amount` + 6 FY archives, excluding `DailySevaNewOld` |
| **Calculation** | `SUM(net_amount)` where `net_amount = gross_amount − cancelled_amount` |
| **Filters** | temple, FY, date range, category |
| **Kollur** | ✅ — FY2025-26 = ₹90.62 Cr |
| **Future** | ✅ — every temple with `REVENUE` |
| **Availability** | `AVAILABLE` |
| **Refresh** | Nightly |
| **API** | `GET …/finance/summary` |
| **Component** | `<KpiCard>` |
| **Visualization** | KPI + sparkline |
| **Drill-down** | → R2 → R4 → R5 |
| **Export** | CSV |

**Note.** Must state which streams are included. Kollur's receipt revenue (₹90.62 Cr), saree auction proceeds (₹2.30 Cr) and donor-declared saree value (₹3.64 Cr) are three different bases. Auction proceeds are the realised value of the same sarees, so summing all three double-counts. The headline is receipt revenue; the others appear in R26.

---

## R2 — Revenue Trend

| | |
|---|---|
| **Purpose** | Multi-year trajectory; year-on-year change |
| **Canonical data** | `fin_agg_revenue_period` (`FY`) |
| **Calculation** | `SUM(net_amount) GROUP BY financial_year`; YoY % derived |
| **Filters** | temple, FY range, category |
| **Kollur** | ✅ — FY2019-20 → FY2026-27 |
| **Availability** | `AVAILABLE`, coverage from FY2019-20 |
| **Visualization** | Line/bar; **table** = FY · Receipts · Gross · Cancelled · Net · YoY % |
| **Drill-down** | Year → R3 |

**Coverage rule.** A filter selecting FY2017-18 returns `NOT_AVAILABLE` for that year, not an empty bar. Kollur seva revenue simply does not exist before FY2019-20.

---

## R3 — Monthly Revenue

| | |
|---|---|
| **Purpose** | Within-year seasonality; festival peaks |
| **Canonical data** | `fin_agg_revenue_period` (`MONTH`) |
| **Calculation** | `SUM(net_amount) GROUP BY period_key` |
| **Kollur** | ✅ — `transaction_date` is a real date |
| **Visualization** | Bar; table = Month · Receipts · Net · Avg/day · % of FY |
| **Drill-down** | Month → day |

**Correction to current dashboard.** The static file asserts *"the temple's system reports revenue by financial year only, never by month."* This is false — `ReceiptDate` is a real `smalldatetime` and agrees exactly with `Finyear`. Daily grain is available.

---

## R4 — Revenue by Category

| | |
|---|---|
| **Purpose** | Where money comes from, by canonical income type |
| **Canonical data** | `fin_agg_revenue_period` grouped by `category_id` |
| **Categories** | `SEVA`, `SPECIAL_SEVA`, `DONATION`, `HUNDI_DONATION`, `PRASADAM_SALE`, `ENTRY_FEE`, `IN_KIND_DONATION`, `ASSET_REALISATION`, … |
| **Kollur** | ✅ — FY2025-16: SEVA ₹46.20 Cr · DONATION ₹20.55 Cr · PRASADAM ₹17.83 Cr · SPECIAL ₹6.04 Cr |
| **Visualization** | Donut; table = Category · Receipts · Net · % |

**Why this replaces a "top sevas" donut.** Categorising honestly prevents three distortions the current dashboard contains: ₹27.7 Cr of prasadam retail counted as "seva", ₹13.40 Cr of hundi collection counted as a purchased service, and entry fees counted as rituals.

---

## R5 — Revenue by Seva (Seva Revenue Split)

| | |
|---|---|
| **Purpose** | Revenue per individual service — an explicit requirement |
| **Canonical data** | `fin_agg_revenue_service` ⋈ `fin_service_dim` |
| **Calculation** | `SUM(net_amount)`, `COUNT` per `service_id` per FY |
| **Filters** | temple, FY, date range, category, min amount |
| **Kollur** | ✅ — all 164 services |
| **Visualization** | Horizontal bar (top 15 + Other); **table** = Service · Local name · Category · Bookings · Gross · Cancelled · Net · Avg · Rate card · % |
| **Pagination** | **Required** — 164 rows for Kollur |
| **Export** | CSV |

Verified Kollur FY2025-26 top five: Hundi ₹13.40 Cr (13) · Laddu ₹7.37 Cr (690,189) · Cloth Bag ₹6.65 Cr (458,991) · Anna Santarpana ₹5.81 Cr (18,979) · Panchakajjaya ₹5.15 Cr (377,022).

**Rules.** `rate_card_amount` is displayed but never used as revenue — it is a list price that legitimately diverges from what was charged. The measure is "bookings/receipts", never "devotees": one devotee buying 20 laddus is one receipt.

---

## R6 — Receipt Count · R7 — Cancellations

**R6** — `SUM(transaction_count)` from `fin_agg_revenue_period`. Kollur ✅ (3,950,072 in FY2025-26). Volume indicator independent of amount.

**R7** — from `fin_cancellation`, full detail. Kollur ✅ — 22 receipts / ₹1.14 L in FY2025-26; 465 rows across all history. Table = Date · Original date · Service · Amount · Cancelled by · Reason. Low volume but high audit value: cancellation rate is an integrity signal a DC officer should see, so cancellations are reported separately rather than silently netted away.

---

## R8 — Payment Mode

| | |
|---|---|
| **Canonical data** | `fin_revenue_fact.payment_mode` + `payment_mode_confidence` |
| **Kollur** | 🟡 `PARTIALLY_AVAILABLE` — 3,950,071 of 3,950,072 receipts have no card/bank data (99.99997 %) |
| **Availability reason** | "No payment-mode field exists in the source; cash is inferred from the absence of card details." |
| **Label** | **"No digital payment recorded"**, not "Paid in cash" |

A blank card field means "no card number was typed", which is not the same as "the devotee paid cash". `payment_mode_confidence` carries `INFERRED` for Kollur and `RECORDED` for temples with a genuine mode column, so the same widget can be honest about both.

---

## R9 — High-value Sevas

From `fin_agg_revenue_service` where `rate_card_amount >= threshold`. Kollur ✅. Table = Service · Rate card · Bookings · Net revenue.

**Must include zero-booking services and show the zero.** The current dashboard lists Ashtabandaha Gold Kalasha (₹5,00,000) and Sahasra Chandi Havana (₹50,000) as headline "special sevas"; both had **0 bookings** in FY2025-26. Here a zero is a genuine measurement — the service exists and was not booked — which is categorically different from `NOT_AVAILABLE`.

---

## R10–R13 — Precious Metals: Counts and Weights

| | |
|---|---|
| **Canonical data** | `fin_precious_metal_fact` |
| **Calculation** | `SUM(item_count)`, `SUM(total_weight_grams)/1000` per `metal_type` per FY |
| **Kollur** | ✅ — gold 1,895 items / 325.516 kg; silver 316 items / 539.250 kg |
| **Visualization** | Grouped bar, two series; table = FY · Gold items · Gold kg · Silver items · Silver kg |

**Correction to current dashboard.** It states *"the temple's system only logs item counts — value and weight use flat assumptions of Rs 12,000 and 15g per item."* The weight half is wrong: `Qty` holds real per-item grams (`InKgs = Qty/1000` confirms the unit) and `ssg_Itemslno` separates gold from silver. Real weight, split by metal, per year, is available. `ASSUMED_WEIGHT_PER_ITEM_GRAMS` is deleted.

**Gaps.** FY2021-22 and FY2022-23 have no rows — rendered as **gaps**, never as zero bars. FY2015-16 holds 1,666 items that are an opening-stock migration and are flagged as such.

---

## R14 / R15 — Gold and Silver Value

| | |
|---|---|
| **Kollur** | ❌ `NOT_AVAILABLE` |
| **Reason** | "The source records no valuation or purity for donated gold and silver. `Rate` and `Amount` are zero on every row after FY2015-16, and `Purity` is empty on all 2,225 rows." |
| **Rendering** | `<AvailabilityNotice>` — **no chart, no estimate, no assumed constant** |

`ASSUMED_VALUE_PER_ITEM_RS = 12000` is removed outright. Presenting an invented constant as a rupee chart on a government oversight dashboard is the single most misleading element of the current implementation. A value report becomes possible only if an approved valuation methodology and an appraisal process exist — at which point `value_basis` moves from `NOT_RECORDED` to `APPRAISED` and the report lights up with no schema change.

---

## R16 — Nirantara Subscriptions

| | |
|---|---|
| **Purpose** | What perpetual sevas are booked, by whom, when |
| **Canonical data** | `fin_nirantara_subscription` ⋈ `fin_service_dim` |
| **Kollur** | ✅ — 6,659 subscriptions, 5,177 subscribers |
| **Visualization** | Bar by service; table = Service · Subscriptions · Subscribers · Earliest · Latest · Last payment FY |

**Must not display** fulfilment rate, missed count, completion %, or active-vs-cancelled split — see R18. **Must display** the execution caveat alongside, sourced from `fin_temple_capability.availability_reason`.

`subscription_status` is loaded as `UNKNOWN` / `ASSUMED` for Kollur: `ACTIVE=1` and `SEVACLOSED=0` on all 6,659 rows means the column carries no information, and rendering it as "6,659 active" would assert something the source does not know.

---

## R17 — Nirantara Payments

| | |
|---|---|
| **Kollur** | 🟡 `PARTIALLY_AVAILABLE` |
| **Reason** | "Payments are recorded only up to FY2023-24. Later years are not recorded, not zero." |
| **Coverage** | FY2017-18 → FY2023-24 |

**Critical rendering rule.** FY2024-25 onward must render as **"not recorded"**, never ₹0 — particularly because the schedule table continues to generate ₹10.88 Cr (FY2025-26) and ₹3.55 Cr (FY2026-27) of expected sevas over the same period. A zero here would read as "collection collapsed", which is a materially different and unsupported conclusion.

---

## R18 — Nirantara Execution

| | |
|---|---|
| **Purpose** | Whether booked perpetual sevas are actually being performed |
| **Canonical data** | `fin_nirantara_execution` — **zero rows for Kollur, by design** |
| **Kollur** | ❌ `NOT_AVAILABLE` |
| **Reason** | "The source system records scheduled sevas but never records performance. All execution flags are unset, the attendance table is empty, and the issued-count is zero on every subscription. **Booking data alone cannot prove execution.**" |

Evidence behind that reason: `seva_List.seva_Prepared`, `Seve_closed` and `LetterPrinted` are `0` on all 15,091 rows; `Sevaattendedlist` has 0 rows; `NoOfSevaIssue` is 0 on all 6,659 subscriptions; and in `seva_List_Fridaypooja`, `seva_Prepared = 1` on **17,808 future-dated rows** — a flag already set for rituals scheduled into 2027 cannot mean "performed".

**Architectural guarantee.** `schedule_generated` lives on `fin_nirantara_schedule`; execution lives on a different table. There is no query that accidentally turns the first into the second. The day the temple begins recording fulfilment, rows appear, capability flips to `AVAILABLE`, and this report activates — with no schema or code change.

---

## R19–R21 — Expenses, Expense Categories, Net Income

| | |
|---|---|
| **Kollur** | ❌ `NOT_AVAILABLE` |
| **Reason** | "The source system does not record expenditure transactions." |
| **Canonical** | `fin_expense_fact` — **deferred, not built** (Q1) |

Evidence: `FN_TRANSACTION` appears to be a general ledger — 524,296 rows, balanced double entry — but in FY2025-26 contains exactly two accounts, both sides of saree-auction sales, ₹2.32 Cr against ₹90.62 Cr of income. `FN_VOUCHERPAYEMENT` is empty. `BudgetDetails` holds 12 FY2017-18 allocation rows with no actuals.

**Interim option.** `trust_financials.annual_expenditure` — a self-declared annual scalar — can populate a clearly-labelled "declared expenditure" KPI. It cannot support the category chart or line items. **R21 Net Income is `NOT_DERIVABLE`** while there is no expenditure side; the only possible net figure mixes declared and computed bases and is not comparable.

**If Q1 selects structured capture**, `BudgetMainGroupMaster`'s 16 Kannada heads (ಸಿಬ್ಬಂದಿ ವರ್ಗ, ಸಾದಿಲ್ವಾರು, ನಿತ್ಯ ಕಟ್ಲೆ, ರಥೋತ್ಸವ, ಕಟ್ಟಡಗಳು, ತೆರಿಗೆಗಳು, ಶಿಕ್ಷಣ…) are the temple's own taxonomy and should seed the canonical expense categories rather than inventing new ones.

---

## R22–R25 — Grants, Approved Funds, Utilization, Ongoing Works

| | |
|---|---|
| **Kollur** | ❌ `NOT_AVAILABLE` |
| **Reason** | "No government grant, sanction order, utilisation or works data exists in the source system or the registry." |
| **Canonical** | `fin_grant`, `fin_grant_utilisation`, `fin_work_project` — **deferred** (Q3) |

The current dashboard's four DC-approved funds — with sanction order numbers, approving authorities, per-year approved/spent figures and a sample utilisation receipt — are entirely illustrative. The file says so.

**Architectural observation.** These are **DC-office facts, not temple-POS facts**. Sanction orders are issued by the DC; utilisation is certified by the DC. The natural home is a registry capture module, not a temple integration — which makes R22–R25 the most tractable of the currently-unsupported reports, since the data owner is the same office that runs the portal.

---

## R26 — In-kind Donations

From `fin_revenue_fact` categories `IN_KIND_DONATION` and `ASSET_REALISATION`. Kollur ✅ — saree donations FY2025-26 ₹3.64 Cr over 30,320 items (donor-declared); saree auction ₹2.30 Cr over 27,767 lots (realised).

**Must not sum the two.** Auction proceeds are the realised value of the same physical sarees. `value_basis` (`DONOR_DECLARED` vs `AUCTION_REALISED`) carries the distinction so the UI can present both without implying a total. GST (`CGSTAmt`/`SGSTAmt`) exists from FY2023-24 only; earlier zeros are absence of recording, not exemption.

---

## R27 — Revenue by Counter / Operator

From `fin_revenue_fact.counter_ref` / `operator_ref`. Kollur ✅ — 3 counters, 19 operators. **New capability not present on the current dashboard**, and directly useful for DC oversight: per-counter and per-operator collection patterns, plus cancellation concentration, are standard financial-control indicators. `operator_ref` is pseudonymous.

---

## R28 — Data Availability Matrix

From `fin_temple_capability`. ✅ for every temple. Table = Capability · Availability · Reason · Coverage from/to · Known gaps.

This is a **report in its own right**, not merely metadata. A DC officer comparing temples needs to see that Temple A reports expenditure and Temple B does not — otherwise a district total silently understates. It is also the honest answer to "why is this panel empty?"

---

## R29 — Sync & Reconciliation Status

From `fin_sync_batch` + `fin_reconciliation_result`. ✅ for every temple. Shows last successful sync, last attempt, **source data through**, freshness, reconciliation status and variance.

`lastSyncedAt` and `sourceDataThrough` are both shown because they differ materially: Kollur's data ends **2026-07-26** no matter how recently we synced. "Synced 2 hours ago" beside figures that stop seven weeks earlier would be misleading on its own.

---

## Cross-cutting Rules

1. **Every report returns the standard envelope** — data plus `availability`, `sync`, `reconciliation`, `coverage`, `warnings`.
2. **Every amount is nullable.** `NOT_AVAILABLE` ⇒ `data: null`. Never `0`.
3. **Every report is chart *and* table.** Charts convey shape; tables are what get exported, audited and cited.
4. **Pagination** on anything unbounded (R5 at 164 rows; all drill-downs).
5. **Filters** — temple, FY, date range, category, scope — are uniform across reports.
6. **Export** reuses `export_job_records` + `exportExecutor`; exports carry the same availability annotations as the screen.
7. **Multi-temple rollups carry coverage metadata** — a district total covering 4 of 11 temples must say so.
8. **No report knows a source table name.**
