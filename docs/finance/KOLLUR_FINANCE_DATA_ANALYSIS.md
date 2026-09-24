# Kollur Finance Dashboard — Data Analysis

**Temple:** Kollur Sri Mookambika Devi Temple
**Temple ID:** 300001 · **Temple Code:** KA-TMP-29D0887C
**Location:** Kollur, Kundapura Taluk, Udupi District, Karnataka
**Source database:** `KOLSOHAM_LOCAL` (SQL Server 2025, restored from the temple's `.bak`) — `TempleCode = 43`
**Analysis date:** 2026-09-16
**Status:** Analysis only. No code changed, no migration written, no row in either database modified.

> Companion document: [KOLSOHAM_DATABASE_ANALYSIS.md](../database/KOLSOHAM_DATABASE_ANALYSIS.md) — full structural analysis of the Kollur database (167 tables, 170 views, 129 procedures). This document is the *financial* read of that same database and does not repeat its structural findings.

---

## 1. Executive Summary

The Kollur database holds **genuinely excellent income data** and **almost no expenditure data**. That single asymmetry decides the outcome of nearly every question below.

**What the database is strong at.** `DailySevaNew` contains 5.37 M live receipt rows and 17.0 M archived rows, each with a real `ReceiptDate`, a real `Amount`, a `SevaCode` resolvable to a named seva, and a `BillCancled` flag. Revenue by year, by month, by day, by seva, by counter and by cashier is all directly computable. Eight financial years are covered (FY2019-20 to FY2026-27), and the totals land within 2–4% of the figures currently hard-coded in the dashboard:

| FY | Receipts | Real revenue (Cr) | Dashboard mock (Cr) |
|---|---:|---:|---:|
| 2019-20 | 2,530,931 | 51.94 | 53.0 |
| 2020-21 | 1,450,433 | 28.15 | 28.6 |
| 2021-22 | 1,712,111 | 36.59 | 37.3 |
| 2022-23 | 3,602,455 | 76.32 | 77.8 |
| 2023-24 | 3,877,205 | 82.59 | 84.2 |
| 2024-25 | 3,809,011 | 83.42 | 85.3 |
| 2025-26 | 3,950,072 | 90.62 | 92.9 |
| 2026-27 (to 26 Jul) | 1,415,755 | 38.61 | 84.01 † |

† The dashboard's final value was deliberately overwritten with the ₹84.01 Cr "income register" figure from a scanned PDF so the two panels would agree. It is not a mock of FY2026-27 revenue.

**What the database cannot do.** There is **no expenditure data of any kind**. `FN_TRANSACTION` looks like a general ledger — 524,296 rows, perfectly balanced double entry — but in FY2025-26 it contains exactly two account postings, both sides of saree-auction sales, totalling ₹2.32 Cr against ₹90.62 Cr of actual seva income. No payroll, no vendor, no purchase, no voucher table holds a single row. The entire "Where the money goes" panel (₹51.25 Cr of expenditure across 7 categories and 10 line items) has **no source in this database** and was transcribed from a Kannada PDF by hand.

**The Nirantara Seva verdict.** Nirantara Seva corresponds to this system's **Sashwatha Seva**. Bookings are well recorded — 6,659 subscriptions, 5,177 subscribers, resolvable to named sevas and to devotee name and address. Execution is not. The schedule table `seva_List` carries `seva_Prepared`, `Seve_closed` and `LetterPrinted` columns and **all three are `0` on all 15,091 rows**. `Sevaattendedlist`, the attendance table, is empty. `seva_sevakartaDetails.NoOfSevaIssue` — the count of sevas actually issued — is `0` on all 6,659 rows. In the larger `seva_List_Fridaypooja` table `seva_Prepared = 1` on ~100% of rows, but it is also `1` on **17,808 future-dated rows**, which proves it means "schedule row generated", not "ritual performed".

> **Booking information exists, but actual seva execution cannot be independently verified from the available data.**

**One correction to the current dashboard.** Its gold/silver panel states that "the temple's system only logs item counts — value and weight use flat assumptions of Rs 12,000 and 15g per item." The weight half of that is **wrong**. `HKanikeItems.Qty` holds real per-item weight in grams (confirmed: `InKgs = Qty/1000`), and `ssg_Itemslno` separates gold (ಬಂಗಾರ, 1,895 items, 325.5 kg) from silver (ಬೆಳ್ಳಿ, 316 items, 539.3 kg). Real weight, split by metal, per year, is available today. Only **value** is genuinely missing — `Rate` and `Amount` are zero on every row after FY2015-16.

**Bottom line.** Of the 12 widgets on the dashboard, **6 can be built from real data now** (2 of them better than the mock), **1 partially**, and **5 cannot be built at all** because they depend on expenditure and government-grant data that this database has never held.

---

## 2. Current Finance Dashboard Inventory

### 2.1 How it is wired

| Item | Value |
|---|---|
| Rendering | Static HTML in an `<iframe>` |
| File | [`frontend/public/dc/temple-300001-dashboard.html`](../../frontend/public/dc/temple-300001-dashboard.html) (597 lines) |
| Embedded by | [`DcTempleProfilePage.tsx:588`](../../frontend/src/features/dc/pages/DcTempleProfilePage/DcTempleProfilePage.tsx#L588), tab `finance` |
| Source constant | `FINANCE_DASHBOARD_SRC = '/dc/temple-300001-dashboard.html'` ([line 115](../../frontend/src/features/dc/pages/DcTempleProfilePage/DcTempleProfilePage.tsx#L115)) |
| Gating | `.filter((tab) => tab.v !== 'finance' \|\| id === FINANCE_DASHBOARD_TEMPLE_ID)` — visible only for temple 300001 |
| Access control | `TARGET_KEYS.TAB_DC_TEMPLE_FINANCE` |
| Charting | Chart.js 4.4.1 via CDN |
| Design copy | [`docs/TempleDashboardForDCOffice_v2.html`](../TempleDashboardForDCOffice_v2.html) (771 lines) |
| **Backend API** | **None. No service, no endpoint, no fetch call.** |
| **Data source** | **One hard-coded `const TEMPLE = {…}` object, [line 194](../../frontend/public/dc/temple-300001-dashboard.html#L194) onward.** |

The file states this itself at line 186:

> `// Static DC Office dashboard for the existing Temple ID 300001. All values below are hard-coded - nothing here reads from the application's backend or database.`

A banner is rendered at the top of the page: *"This temple has some digital data being tracked. The figures below are a sample of realistic data, not confirmed actuals."*

There is **no date-range selector, no filter, no pagination, no sort, and no export** anywhere in the dashboard. Every figure is a fixed literal. This matters for scoping: filters are not being *migrated*, they are being *built for the first time*.

### 2.2 Widget-by-widget inventory

All 12 widgets are static. "Mock var" is the key in the hard-coded `TEMPLE` object.

| # | Widget | Type | Metric shown | Dimension | Period | Mock var |
|---:|---|---|---|---|---|---|
| 1 | Total money collected | KPI + sparkline | Total revenue ₹Cr | Financial year | 8 FYs, 2019-20→2026-27 | `totalRevCr` |
| 2 | Money collected, month on month | Sparkline | Revenue ₹Cr | Calendar month | Apr–Aug, current FY | `monthlyCr` |
| 3 | Paid in cash | Gauge | % of receipts in cash | — | All time | `cashSharePct` = 99.999 |
| 4 | DC approved funds used overall | Gauge | Spent ÷ approved % | — | Last 5 FYs | derived from `govFunds` |
| 5 | Where the money comes from | Donut + legend | Share of income ₹Cr | Income source (top 5) | All years combined | `topSevas` |
| 6 | Where the money goes: expenditures | 3 KPIs + bar chart + ranked list | Income / expenditure / net surplus; spend by category; top 10 line items | Expense category; line item | FY2025-26 | `expenditure` |
| 7 | DC Approved Funds: approved vs spent | Drillable list + receipt mock | Approved vs spent ₹L, sanction order, authority, category, utilisation proof | Fund × FY | 5 FYs, 2021-22→2025-26 | `govFunds` (4 funds) |
| 8 | Ongoing Works | Card list | Approved ₹L, spent ₹L, start, expected completion, status, invoice | Work item | Current | `ongoingWorks` (3) |
| 9 | Gold & silver donated — items | Line chart | Count of items | Financial year | 9 FYs, 2015-16→2024-25 | `donationCounts` |
| 10 | Gold & silver — estimated value | Bar chart | Count × ₹12,000 **assumed** | Financial year | Same 9 FYs | derived |
| 11 | Gold & silver — estimated weight | Bar chart | Count × 15 g **assumed** | Financial year | Same 9 FYs | derived |
| 12 | Special / high-value sevas | List | Seva name + rupee amount | Seva | — | `specialSevas` (5) |

Two further reports are required by this task but are **not yet present** on the dashboard:

| # | Widget | Status |
|---:|---|---|
| 13 | **Seva Revenue Split** (revenue by individual seva type) | New requirement — no current widget |
| 14 | **Nirantara Seva Tracking** (booked vs actually performed) | New requirement — no current widget |

### 2.3 Hard-coded values that must be replaced

| Constant | Value | Replaced by |
|---|---|---|
| `ASSUMED_VALUE_PER_ITEM_RS` | 12,000 | Nothing — no valuation exists (§11) |
| `ASSUMED_WEIGHT_PER_ITEM_GRAMS` | 15 | **Real `HKanikeItems.Qty` in grams** (§8.2) |
| `cashSharePct` | 99.999 | Derivable, 99.99997% actual (§12.3) |
| `totalRevCr` (8 values) | 53.0 … 84.01 | Real per-FY aggregates (§12.1) |
| `monthlyCr` (5 values) | 16.2 … 16.41 | Real `MONTH(ReceiptDate)` aggregates (§12.2) |
| `topSevas` (5 entries) | Hundi 73.9 Cr … | Real seva aggregates (§9) |
| `expenditure` (3 KPIs, 7 categories, 10 items) | ₹51.25 Cr total | **Nothing — no source exists** (§11) |
| `govFunds` (4 funds × 5 years) | ₹180 L … ₹300 L | **Nothing — no source exists** (§11.2) |
| `ongoingWorks` (3 works) | — | **Nothing — no source exists** (§11.2) |
| `specialSevas` (5 entries) | ₹5,00,000 … ₹20,000 | Real `seva_seva.ssv_amount` (§9.4) |

---

## 3. Existing Temple Registry Data

The Temple Registry database (MySQL, Flyway-managed, 22 migrations, 65 tables) was examined for anything that could serve financial reporting.

### 3.1 The finding

**The Temple Registry contains no transactional financial data whatsoever.** There is no donation table, no seva table, no booking table, no payment table, no transaction table, no receipt table, no revenue table, no expense table, no vendor table and no purchase table. Searching the schema for those concepts returns nothing.

This is not an oversight in the registry's design — it is a *regulatory registry*, not an accounting system. It records what a temple has declared to the Deputy Commissioner, not what the temple collected yesterday.

### 3.2 The one financial table

`trust_financials` (from `V1__initial_schema.sql`) is the sole financial structure:

| Column | Type | Meaning |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | — |
| `trust_id` | BIGINT **FK → `trusts.id`** | Owning trust |
| `financial_year` | VARCHAR(10) | e.g. `2025-26` |
| `annual_income` | DECIMAL(18,2) | **One number per year** |
| `annual_expenditure` | DECIMAL(18,2) | **One number per year** |
| `submitted_at` | DATETIME | Declaration timestamp |
| `document_id` | BIGINT | Supporting scan |
| `is_deleted`, `created_at`, `updated_at`, `created_by`, `updated_by` | — | Standard audit columns |

Constraint: `fk_tf_trust FOREIGN KEY (trust_id) REFERENCES trusts (id)`. Uniqueness per trust per year was added in `V9__trust_financial_year_uniqueness.sql`.

This is **declared annual totals only** — two scalars per year, self-reported, with no breakdown by source, no transaction detail, no dates, no categories. It already surfaces in the DC overview KPI card ("Show trust annual income in DC overview KPI card", commit `a5e7d46`). It cannot support any chart on the finance dashboard, but it is valuable as a **reconciliation baseline**: the declared `annual_income` can be compared against computed Kollur revenue to flag divergence.

### 3.3 Other tables examined and ruled out

| Table | Why it looked relevant | Actual content | Verdict |
|---|---|---|---|
| `decl_mov_financial` | "financial" in the name | Declared **movable financial assets** (deposits, securities) — a balance-sheet item in an asset declaration | Not transactional |
| `decl_mov_precious_metal` | Gold/silver declarations | Declared precious-metal **holdings**, not receipts | Could cross-check §8, not a revenue source |
| `asset_declarations` + 9 `decl_*` tables | Asset value | Declaration workflow and asset inventory | No income/expense |
| `contractors` | Vendor payments? | Contractor **registry** — master data, no amounts | Not financial |
| `employees` | Payroll? | Employee **registry** — master data, no salary | Not financial |
| `board_meetings`, `board_members` | Governance | Meeting records, member details | Not financial |
| `temples`, `temple_profile_current` | Temple master | Identity, grade, status, location | Join key only |
| `documents`, `temple_photos` | Attachments | Binary/document storage | Supporting only |
| `audit_*`, `workflow_*`, `notification_*` | Audit trail | Application audit, not financial audit | Not financial |

**Join key to Kollur:** `temples.id = 300001` ↔ Kollur `TempleCode = 43`. This mapping is not recorded anywhere in either database and must be established explicitly (§20).

---

## 4. Kollur Temple Database Inventory — Financial Tables

Only tables that carry money, quantity or financial-supporting information are listed. Row counts from `sys.partitions`.

### 4.1 Tables that ARE financial sources

| Table | Rows | Money column | Date column | Status columns | Verdict |
|---|---:|---|---|---|---|
| **`DailySevaNew`** | 5,365,855 | `Amount` (money) | `ReceiptDate` (smalldatetime) | `BillCancled`, `Deleteflag` | **Primary revenue source** |
| `DailySevaNew20192020` … `20242025` (6 tables) | 16,982,270 | `Amount` | `ReceiptDate` | same | **Historical revenue** |
| `DailySevaNewOld` | 16,982,270 | `Amount` | `ReceiptDate` | same | **Exact duplicate of the 6 above — do not use** |
| **`SareeDonation`** | 182,675 | `SareeValueDonor` (money) | `ReceiptDate` | `billcancled`, `DeleteFlag` | **Saree donation value** |
| **`SareeAuction`** | 147,747 | `AuctionAmt`, `TaxableAmount`, `CGSTAmt`, `SGSTAmt` | `AuctionDate` | `billcancled`, `DeleteFlag` | **Auction revenue + GST** |
| **`HKanikeItems`** | 2,225 | `Qty` (grams), `Rate`/`Amount` (both 0 post-2015-16) | `ssg_receiptdate` | `BillCancel`, `Deleteflag` | **Gold/silver weight; no value** |
| `seva_sevakartaPayment` | 5,219 | `ssk_TotalAmount`, `ssk_TotalAmtpaid` | `ReceiptDate` | — | Nirantara payments — **stops 2023-24** |
| `seva_sevakartaPaymentdet` | 5,247 | — | `ssk_RefDate` | `ssk_PaymentType` | Payment mode detail |
| `seva_List_Fridaypooja` | 317,892 | `Amount` (money) | `seva_Date` | `seva_Prepared`, `Printed`, `Returned`, `Deleteflag` | **Schedule amount, not a payment** |
| `FN_TRANSACTION` | 524,296 | `TrAmount` | `Trdate` | `TrCancled`, `DeleteFlag` | **Ledger — saree auction only** |
| `SareeAuctionDetails` | 147,689 | — | — | — | Auction lot ↔ donated saree link |

### 4.2 Master / reference data

| Table | Rows | Role |
|---|---:|---|
| **`seva_seva`** | 164 | Seva master — `ssv_Name`, `ssv_Name_English`, `ssv_amount` (rate card), `ssv_sannidhi`, `Accountcode` |
| `seva_sannidhi` | 4 | Income buckets: `SEVAS (DS)`, `SPL SEVAS (SS)`, `KANIKE/DONATION (KN)`, `PRASADA (PS)` |
| `HItemMaster` | 45 | Item master — **`ItemSlNo` 2 = ಬಂಗಾರ (gold), 1 = ಬೆಳ್ಳಿ (silver)** |
| `FN_ACCOUNTMASTER` | 22 | Chart of accounts |
| `FN_MASTERMAIN` | 136 | Account group hierarchy |
| `BudgetMainGroupMaster` | 16 | **Expense category names** (Kannada) — see §11.3 |
| `BudgetDetails` | 12 | Budget allocations — **FY2017-18 only** |
| `CounterMaster` | 3 | Counters 1–3 |
| `UserTable` | 19 | Cashiers/operators |
| `sareetypemaster`, `ColorTypeMaster`, `ClothTypeMaster` | 4 / 18 / 2 | Saree attributes |
| `PurityMaster` | 9 | Gold/silver purity grades — **never used, §14.4** |
| `seva_period` | 8 | Recurrence archetypes incl. `SEVA_SASHWATHAPUJA` — **never used, §10.2** |

### 4.3 Nirantara (Sashwatha) Seva tables

| Table | Rows | Role |
|---|---:|---|
| `seva_sevakarta` | 5,177 | Subscriber; links to `NameMaster` / `AddressMaster` |
| **`seva_sevakartaDetails`** | 6,659 | **Subscription** — seva, dates, recurrence, `ACTIVE`, `SEVACLOSED`, `Renewal`, `NoOfSeva`, `NoOfSevaIssue` |
| `seva_RWsevakartaDetails` | 6,659 | Renewal mirror (86 cols vs 94) |
| `seva_List` | 15,091 | **Schedule** — `seva_Date`, `seva_Prepared`, `Seve_closed`, `LetterPrinted` |
| `seva_List_Fridaypooja` | 317,892 | Friday-pooja schedule, with `Amount` |
| `ssk_seva_Familydetails` | 5,275 | Family members in the sankalpa |
| **`Sevaattendedlist`** | **0** | **Attendance table — empty** |

### 4.4 Financial tables that are EMPTY

Every one of these exists with full schema, views and stored procedures, and holds **zero rows**:

`FN_VOUCHERPAYEMENT` (payment vouchers) · `FN_BANKMASTER` · `FN_TrialBalance` · `FN_CashFlowTemp` · `Donation` (generic donations) · `AnnadhanaDetails` (food-offering receipts) · `HundiCollection_Cash` / `_Coins` / `_GoldSilver` / `_Others` (donation-box counting) · `HTulabhara` / `HTulabharaDetails` · `TenantMaster` / `TenantReceipt` / `TenantReceiptDetails` / `ShopRentMaster` / `LandRentMaster` / `GenerateTenatRent` / `PropertyStatusMaster` (all rent) · `HallBookingDetails` / `HallBooking_Payment` · `MOSender` / `MOSenderDetails` · `ElectricalReading` · `CommissionDetails` · `SMS_TemplateMaster` · `TotalSMSCount`

Notably: **hundi (donation box) collection is empty** even though "Hundi" is the single largest income line on the dashboard. The hundi money is booked through `DailySevaNew` under `SevaCode 430 (HUNDIALS)`, not through the dedicated hundi tables (§9.3).

---

## 5. Financial Data Sources

| Business concept | Kollur table | Column | Available | Notes |
|---|---|---|:--:|---|
| Seva revenue (charged) | `DailySevaNew` | `Amount` | **YES** | Authoritative; see §12.1 |
| Seva transaction date | `DailySevaNew` | `ReceiptDate` | **YES** | Real date, aligns with `Finyear` exactly |
| Financial year | `DailySevaNew` | `Finyear` | **YES** | `int` `YYYYYYYY`; verified consistent with `ReceiptDate` |
| Seva type / name | `seva_seva` | `ssv_code`, `ssv_Name_English`, `ssv_Name` | **YES** | 0 orphan `SevaCode`s |
| Seva rate card | `seva_seva` | `ssv_amount` | **YES** | List price, not charged price |
| Seva quantity | `DailySevaNewDetails` | `Qty` | PARTIAL | Table unreliable — §14.1 |
| Receipt number | `DailySevaNew` | `ReceiptNo` | **YES** | `varchar(12)`, **nullable, unindexed** |
| Cancellation | `DailySevaNew` | `BillCancled`, `BillCancledBy`, `BillCancledReason`, `Billcanceldate` | **YES** | 22 receipts FY2025-26 |
| Cancellation log | `ReceiptCanceldetails` | 7 cols | **YES** | 465 rows, 2018→2026 |
| **Refund** | — | — | **NO** | No refund table, no negative amounts |
| Counter | `DailySevaNew` | `COUNTERNO` → `CounterMaster` | **YES** | 3 counters |
| Cashier / operator | `DailySevaNew` | `ModifiedBy` → `UserTable.UserId` | **YES** | |
| **Payment mode** | `DailySevaNew` | `CardNo`, `BankName` | **INFERRED** | No explicit mode column — §12.3 |
| Devotee name/address | `DailySevaNew` | `PersonName`, `Address`, `MobileNo`, `Email` | **YES** | Denormalised free text |
| Gotra / Nakshatra / Raashi | `DailySevaNew` | → `seva_gotra`, `seva_nakshatra` | **YES** | Ritual metadata |
| Income bucket | `seva_seva` | `ssv_sannidhi` → `seva_sannidhi` | **YES** | 4 buckets |
| Saree donation value | `SareeDonation` | `SareeValueDonor` | **YES** | `SareeKanike` is **all zeros** |
| Saree auction revenue | `SareeAuction` | `AuctionAmt` | **YES** | |
| Auction GST | `SareeAuction` | `CGSTAmt`, `SGSTAmt`, `TaxableAmount` | PARTIAL | **Zero before FY2023-24** |
| Gold/silver weight | `HKanikeItems` | `Qty` (g), `InKgs` | **YES** | Real per-item weight |
| Gold vs silver | `HKanikeItems` | `ssg_Itemslno` → `HItemMaster` | **YES** | 2 = gold, 1 = silver |
| **Gold/silver value** | `HKanikeItems` | `Rate`, `Amount` | **NO** | Zero on every row after FY2015-16 |
| **Gold/silver purity** | `HKanikeItems` | `Purity` | **NO** | Empty string on all 2,225 rows |
| Nirantara subscription | `seva_sevakartaDetails` | `ssk_code`, `ssk_SevaCode`, `SevaFromDate`, `SevaToDate` | **YES** | |
| Nirantara payment | `seva_sevakartaPayment` | `ssk_TotalAmount`, `ssk_TotalAmtpaid` | PARTIAL | **No rows after FY2023-24** |
| **Nirantara execution** | `seva_List` | `seva_Prepared`, `Seve_closed` | **NO** | All zero — §10.3 |
| **Nirantara attendance** | `Sevaattendedlist` | — | **NO** | Empty table |
| **Expense / expenditure** | — | — | **NO** | No source anywhere — §11 |
| **Vendor / purchase** | — | — | **NO** | No table exists |
| **Payroll / salary** | — | — | **NO** | No table exists |
| **Govt / DC grant** | — | — | **NO** | No table exists — §11.2 |
| **Ongoing works / projects** | — | — | **NO** | No table exists |
| Expense category names | `BudgetMainGroupMaster` | `BudgetMainGroupName` | LABELS ONLY | 16 Kannada categories, no amounts |
| Budget allocation | `BudgetDetails` | `BudgetAmount`, `BudgetIssue` | **NO** | FY2017-18 only, `BudgetIssue` ~0 |
| Accounting ledger | `FN_TRANSACTION` | `TrAmount`, `Trdate`, `TransactionType` | LIMITED | Saree auction only — §11.1 |

---

## 6. Kollur → Temple Registry Mapping

| Kollur source | Temple Registry target | Mapping | Transformation | Recommendation |
|---|---|---|---|---|
| `DailySevaNew` (5.4 M + 17 M rows) | *(none exists)* | No target | Aggregate to FY/month/seva | **New reporting table — never import raw rows** |
| `seva_seva` (164) | *(none exists)* | No target | Normalise name, active flag | **New `seva_dim` reference table** |
| `SareeDonation` (182 K) | *(none exists)* | No target | Aggregate by FY | **Fold into revenue-by-source aggregate** |
| `SareeAuction` (147 K) | *(none exists)* | No target | Aggregate by FY, split GST | **Fold into revenue-by-source aggregate** |
| `HKanikeItems` (2,225) | `decl_mov_precious_metal` (declaration) | **Not equivalent** | — | **New aggregate table**; use declaration only to cross-check |
| `seva_sevakartaDetails` (6,659) | *(none exists)* | No target | Status derivation | **New Nirantara table** |
| `seva_sevakartaPayment` (5,219) | *(none exists)* | No target | — | **Fold into Nirantara table** |
| `FN_TRANSACTION` (524 K) | *(none exists)* | No target | — | **Do not import** — misleading, §11.1 |
| *(no expense source)* | `trust_financials.annual_expenditure` | **Declared only** | — | **Use declaration; flag as self-reported** |
| *(no grant source)* | *(none exists)* | — | — | **Out of scope — needs new capture, §17** |
| `TempleCode = 43` | `temples.id = 300001` | **Not recorded anywhere** | Explicit map | **Must be configured, §20** |

**Conclusion.** Nothing in Kollur maps onto an existing Temple Registry table. The registry has no financial transaction model at all, and building one to hold 22 M raw receipt rows would be the wrong move. The correct shape is a small set of **pre-aggregated reporting tables** (§20).

---

## 7. Report-by-Report Data Availability

Classification: **AVAILABLE** · **AVAILABLE WITH TRANSFORMATION** · **PARTIALLY AVAILABLE** · **NOT AVAILABLE** · **NOT DERIVABLE**

### 7.1 Widget 1 — Total money collected

- **Purpose:** headline revenue per financial year, 8 years.
- **Source:** `DailySevaNew` + 6 `DailySevaNew<FY>` archives.
- **Columns:** `Amount`, `Finyear`, `BillCancled`, `Deleteflag`.
- **Joins:** none. **Aggregation:** `SUM(Amount) GROUP BY Finyear`. **Filter:** `BillCancled = 0 AND Deleteflag = 0`.
- **Status: AVAILABLE WITH TRANSFORMATION** — requires `UNION ALL` across 7 physical tables (no view or procedure references the archives; §14.6).
- **Concerns:** archives end FY2024-25 while the live table holds *two* years (FY2025-26 **and** FY2026-27); the FY2025-26 archive cut has not been made. Query logic must not assume one FY per table.

### 7.2 Widget 2 — Money collected, month on month

- **Source:** `DailySevaNew.ReceiptDate`, `Amount`.
- **Aggregation:** `SUM(Amount) GROUP BY YEAR(ReceiptDate), MONTH(ReceiptDate)`.
- **Status: AVAILABLE.**
- **Note:** the dashboard comment claims *"the temple's system reports revenue by financial year only, never by month."* **This is incorrect.** `ReceiptDate` is a real `smalldatetime` and `Finyear` agrees with it exactly (FY2025-26 → 2025-04-01…2026-03-31; FY2026-27 → 2026-04-01…2026-07-26). Monthly, weekly and daily revenue are all directly computable. This widget becomes *more* accurate than the mock.

### 7.3 Widget 3 — Paid in cash

- **Source:** `DailySevaNew.CardNo`, `BankName`.
- **Measured:** of 3,950,072 FY2025-26 receipts, exactly **1** has a non-empty `CardNo`/`BankName` (₹2,000). Cash share = **99.99997%**.
- **Status: AVAILABLE WITH TRANSFORMATION** — the value is right, but it is *inferred from the absence of card data*, not read from a payment-mode column. There is no `TranType`/`PayMode` column on `DailySevaNew`.
- **Concern:** a receipt where the operator simply left the card field blank is indistinguishable from a genuine cash sale. Label the widget "no digital payment recorded" rather than asserting cash.

### 7.4 Widget 4 — DC approved funds used overall (gauge)

- **Source:** none. **Status: NOT AVAILABLE.** See §11.2.

### 7.5 Widget 5 — Where the money comes from (donut)

- **Source:** `DailySevaNew` ⋈ `seva_seva`.
- **Join:** `seva_seva.ssv_code = DailySevaNew.SevaCode` — verified **0 orphans**.
- **Aggregation:** `SUM(Amount) GROUP BY ssv_code` ORDER BY revenue DESC, top *N*, remainder as "Other".
- **Status: AVAILABLE.** Real FY2025-26 top 5: Hundi ₹13.40 Cr, Laddu ₹7.37 Cr, Cloth bag ₹6.65 Cr, Anna Santarpana ₹5.81 Cr, Panchakajjaya ₹5.15 Cr.
- **Concern:** the mock labels this "all years combined" but its values (73.9/39.6/35.6/30.9/23.7 Cr) match neither one year nor the 8-year sum. Decide the period deliberately; combining the archives is possible but the pre-FY2019-20 history does not exist.
- **Optional:** `seva_seva.ssv_sannidhi` → `seva_sannidhi` gives a coarser 4-bucket breakdown (SEVAS / SPL SEVAS / KANIKE / PRASADA) suitable for a second ring.

### 7.6 Widget 6 — Where the money goes: expenditures

- **Source:** none. **Status: NOT AVAILABLE.** See §11. The income-register KPI (₹84.01 Cr) is also not reproducible — it is a *register* figure from a PDF, not a database aggregate, and it does not equal the computed FY2025-26 revenue of ₹90.62 Cr.

### 7.7 Widget 7 — DC Approved Funds approved vs spent

- **Source:** none. **Status: NOT AVAILABLE.** No grant, sanction-order, or utilisation table exists in either database. See §11.2.

### 7.8 Widget 8 — Ongoing Works

- **Source:** none. **Status: NOT AVAILABLE.** No project/works table in either database.

### 7.9 Widget 9 — Gold & silver donated (item counts)

- **Source:** `HKanikeItems`, `GROUP BY Finyear`, filter `BillCancel = 0 AND Deleteflag = 0`.
- **Status: AVAILABLE.** Verified against the mock — real counts 28/46/37/65/105/164/39 match `donationCounts` for FY2016-17 → FY2024-25 exactly.
- **Concerns:** FY2015-16 holds **1,666 items** (an opening-stock migration, zeroed in the mock); FY2021-22 and FY2022-23 have **no rows at all**; FY2025-26 (44) and FY2026-27 (17) exist but are omitted from the mock. Earliest `ssg_receiptdate` is **1969-09-26** — invalid.

### 7.10 Widget 10 — Gold & silver estimated value

- **Source:** `HKanikeItems.Rate`, `Amount` — **both zero on every row after FY2015-16** (only FY2015-16 has values, totalling ₹59.05 Cr, clearly an opening valuation).
- **Status: NOT DERIVABLE.** Value cannot be computed: no per-item rate, no purity (§14.4), and no market-rate table. The current ₹12,000/item assumption has no basis in the data and should not be presented as a figure.

### 7.11 Widget 11 — Gold & silver estimated weight

- **Source:** `HKanikeItems.Qty` (grams; confirmed `InKgs = Qty/1000`), `ssg_Itemslno` → `HItemMaster`.
- **Status: AVAILABLE** — and **strictly better than the mock**. Real totals: gold 325.516 kg over 1,895 items; silver 539.250 kg over 316 items. Per-year split:

| FY | Gold items | Gold kg | Silver items | Silver kg |
|---|---:|---:|---:|---:|
| 2015-16 | 0 | 0.000 | 1,666 | 256.790 |
| 2016-17 | 0 | 0.000 | 28 | 6.530 |
| 2017-18 | 26 | 56.995 | 20 | 6.654 |
| 2018-19 | 19 | 49.472 | 18 | 6.972 |
| 2019-20 | 32 | 137.607 | 33 | 8.398 |
| 2020-21 | 56 | 126.576 | 49 | 16.669 |
| 2023-24 | 137 | 72.519 | 27 | 7.179 |
| 2024-25 | 14 | 29.440 | 25 | 8.529 |
| 2025-26 | 21 | 41.494 | 23 | 6.648 |
| 2026-27 | 11 | 25.147 | 6 | 1.147 |

- **Action:** drop `ASSUMED_WEIGHT_PER_ITEM_GRAMS` and the "15 g per item" caption; replace with real weight split by metal.

### 7.12 Widget 12 — Special / high-value sevas

- **Source:** `seva_seva` where `ssv_amount` ≥ threshold.
- **Status: AVAILABLE WITH TRANSFORMATION.** The mock's 5 names and amounts are real `seva_seva` rows. Real rate card, enriched with actual FY2025-26 bookings:

| Seva | Rate ₹ | Bookings FY2025-26 | Revenue ₹ |
|---|---:|---:|---:|
| Ashtabandaha Gold Kalasha | 5,00,000 | **0** | 0 |
| Shata Chandi Havana Sthala Kanike | 1,25,000 | 15 | 6,22,111 |
| Sahasra Chandi Havana Sthala Kanike | 50,000 | **0** | 0 |
| Navagrahapoorva Chandi Yaga | 40,000 | 633 | 1,56,00,000 |
| Sabha Bhavan Rent (1 day) | 30,000 | 36 | 4,32,000 |
| Nanda Deepa for 1 year | 20,000 | 348 | 20,85,000 |
| Udayastamana Pooja (Golden Chariot) | 20,000 | 530 | 87,05,000 |

- **Concern:** the mock lists two sevas with **zero bookings** as headline "special sevas". Adding the booking count and revenue makes the widget honest and materially more useful.

### 7.13 Widget 13 — Seva Revenue Split (new)

**Status: AVAILABLE.** Full analysis in §9.

### 7.14 Widget 14 — Nirantara Seva Tracking (new)

**Status: PARTIALLY AVAILABLE** — bookings yes, execution no. Full analysis in §10.

---

## 8. Donation Analysis

### 8.1 There is no generic "donation" table

The `Donation` table exists with a full schema, a `sp_Donation` stored procedure (26 parameters) and a `View_Donation` view — and holds **zero rows**. `DonationTypeMaster` has 3 rows of unused reference data. `AnnadhanaDetails` (food-offering receipts) is also empty, as are all four `HundiCollection_*` tables.

**Donations are not recorded as donations.** They are recorded as sevas, in `DailySevaNew`, and classified through the seva's shrine bucket.

### 8.2 Donation revenue via the `sannidhi` bucket

`seva_seva.ssv_sannidhi` → `seva_sannidhi` classifies every seva into one of four income buckets. This is the only reliable donation/seva separator in the database:

**FY2025-26, `BillCancled = 0`:**

| Bucket | `ssn_Type` | Receipts | Revenue |
|---|---|---:|---:|
| SEVAS | DS | 2,475,769 | ₹46.20 Cr |
| **KANIKE/DONATION** | **KN** | **25,696** | **₹20.55 Cr** |
| PRASADA | PS | 1,443,387 | ₹17.83 Cr |
| SPL SEVAS | SS | 5,220 | ₹6.04 Cr |
| **Total** | | **3,950,072** | **₹90.62 Cr** |

**Donation revenue = ₹20.55 Cr for FY2025-26** — status **AVAILABLE**. This includes the hundi collection (`SevaCode 430`) and Anna Santarpana donations (`SevaCode 419`).

### 8.3 Donation fields — what exists and what does not

| Required field | Available | Source |
|---|:--:|---|
| Donation amount | **YES** | `DailySevaNew.Amount` |
| Donation date | **YES** | `DailySevaNew.ReceiptDate` |
| Donation type | **YES** | `seva_seva.ssv_code` / `ssv_Name_English` |
| Donation category | **YES** | `seva_sannidhi.ssn_Type = 'KN'` |
| Donor name | **YES** | `DailySevaNew.PersonName` (free text, denormalised) |
| Donor address / mobile / email | **YES** | `Address`, `MobileNo`, `Email` (free text) |
| Receipt number | **YES** | `DailySevaNew.ReceiptNo` — **nullable** |
| Cancellation | **YES** | `BillCancled` + `ReceiptCanceldetails` |
| **Donation mode (cash/card/UPI)** | **NO** | Only `CardNo`/`BankName` free text — §12.3 |
| **Campaign / appeal** | **NO** | No campaign table |
| **Refund** | **NO** | No refund table, no negative amounts |
| **Donor identity (PAN, 80G)** | **NO** | Not captured — relevant if tax receipts are ever needed |

### 8.4 In-kind donations

| Stream | Table | Rows | Value available |
|---|---|---:|---|
| Saree donations | `SareeDonation` | 182,675 | **YES** — `SareeValueDonor`. FY2025-26: ₹3.64 Cr over 30,320 sarees |
| Saree auction proceeds | `SareeAuction` | 147,747 | **YES** — `AuctionAmt`. FY2025-26: ₹2.30 Cr over 27,767 lots |
| Gold / silver | `HKanikeItems` | 2,225 | **Weight yes, value NO** — §7.10, §7.11 |

**Caution:** `SareeDonation.SareeValueDonor` is a **donor-declared** value, not an appraisal, and `SareeKanike` (which sounds like the money column) is **zero on every row** — do not use it. Saree donation and saree auction must not be added together: the auction proceeds are the realised value of the same physical sarees, so summing both double-counts.

---

## 9. Seva Revenue Analysis

### 9.1 The authoritative amount — a decisive finding

Three candidate amount columns exist. They do **not** agree:

| Candidate | FY2025-26 total | Verdict |
|---|---:|---|
| `DailySevaNew.Amount` (header) | **₹90,61,62,936** | **AUTHORITATIVE** |
| `DailySevaNewDetails.TotalAmount` | ₹53,77,53,226 | **Do not use — 41% short** |
| `DailySevaNewDetails.Amount × Qty` | ₹56,31,07,228 | **Do not use — inconsistent** |

Header and detail have exactly **1:1 row correspondence** (3,950,094 each) and **0 orphan detail rows**, so the gap is not missing records — the detail table's own `TotalAmount` is internally inconsistent with its `Amount × Qty` (a ₹2.5 Cr divergence), and both understate the header.

Observed example rows make the inconsistency concrete:

| `Qty` | `Amount` | `TotalAmount` | Expected |
|---:|---:|---:|---:|
| 1 | 15.00 | 15.00 | 15.00 ✓ |
| 20 | 30.00 | 600.00 | 600.00 ✓ |
| 3 | 100.00 | 300.00 | 300.00 ✓ |
| **2** | **20.00** | **20.00** | **40.00 ✗** |

> **Source of truth for seva revenue: `DailySevaNew.Amount`.** It is the amount actually charged on the receipt, it reconciles year-on-year with the dashboard's own illustrative series, and it is the only one of the three that is internally consistent. `DailySevaNewDetails` should be used **only** for line-item composition (which sevas were on a receipt), never for money.

Note also that `MultiRecpNo` is **not** a receipt-group identifier — the same value recurs across unrelated receipts with different `ReceiptNo`s. Do not use it to group a "basket".

### 9.2 Seva Revenue Split — the required report

**Status: AVAILABLE.** Fully supported today.

```sql
SELECT  s.ssv_code                         AS seva_code,
        s.ssv_Name_English                 AS seva_name,
        s.ssv_Name                          AS seva_name_kn,
        n.ssn_name                          AS bucket,
        COUNT_BIG(*)                        AS booking_count,
        SUM(d.Amount)                       AS revenue,
        SUM(d.Amount) / COUNT_BIG(*)        AS avg_ticket,
        s.ssv_amount                        AS rate_card
FROM    DailySevaNew      d
JOIN    seva_seva         s ON s.ssv_code    = d.SevaCode
LEFT JOIN seva_sannidhi   n ON n.ssn_code    = s.ssv_sannidhi
WHERE   d.Finyear     = @finyear
  AND   d.BillCancled = 0
  AND   d.Deleteflag  = 0
GROUP BY s.ssv_code, s.ssv_Name_English, s.ssv_Name, n.ssn_name, s.ssv_amount
ORDER BY revenue DESC;
```

Verified output, FY2025-26, top 20 of 164 sevas:

| Seva code | Seva | Bookings | Revenue ₹ |
|---:|---|---:|---:|
| 430 | HUNDIALS | 13 | 13,40,21,747 |
| 83 | LADDU | 690,189 | 7,37,24,400 |
| 76 | CLOTH BAG | 458,991 | 6,64,73,790 |
| 419 | ANNA SANTARPANA DONATION | 18,979 | 5,81,12,487 |
| 82 | PANCHAKAJJAYA | 377,022 | 5,15,28,900 |
| 81 | THEERTHA BOTTLE | 374,643 | 5,15,00,700 |
| 35 | GHEE LAMP | 391,565 | 4,63,53,300 |
| 26 | MOOKAMBIKA ALANKARA POOJA | 121,120 | 3,81,86,100 |
| 407 | CHANDIKA HOMA | 4,050 | 3,60,40,000 |
| 17 | MAHA TRIMADHURA | 163,740 | 3,53,28,660 |
| 4 | SAHASRANAMA BHASMARCHANA | 85,705 | 3,17,87,775 |
| 1 | SAHASRANAMA KUMKUMARCHANA | 245,815 | 3,13,60,250 |
| 15 | PUSHPANJALI | 314,051 | 2,99,53,120 |
| 58 | MAHA PRASADA | 80,488 | 2,39,80,800 |
| 27 | VEERABHADRA ALANKARA POOJA | 39,456 | 1,94,79,860 |
| 23 | KARPOORA ARATHI | 39,001 | 1,93,16,175 |
| 3 | ASHTOTHARA KUMKUMARCHANA | 85,871 | 1,74,99,000 |
| 20 | EKAWARA RUDRABHISHEKA | 31,673 | 1,60,53,670 |
| 408 | NAVAGRAHAPOORVA CHANDI YAGA | 633 | 1,56,00,000 |
| 75 | DIRECT ENTRANCE | 29,752 | 1,48,76,000 |

Every dimension the requirement asks for is supported:

| Requirement | Supported | Source |
|---|:--:|---|
| Seva type | **YES** | `seva_seva.ssv_code` / `ssv_Name_English` |
| Booking count | **YES** | `COUNT(*)` on `DailySevaNew` |
| Amount per booking | **YES** | `DailySevaNew.Amount` |
| Total revenue | **YES** | `SUM(Amount)` |
| Date / period | **YES** | `ReceiptDate` — any grain down to daily |
| Cancellation | **YES** | `BillCancled = 0` |
| **Payment status** | **N/A** | No credit model — a receipt *is* the payment (§12.4) |
| **Refund status** | **NO** | No refund concept exists |

### 9.3 Interpretation cautions

- **`HUNDIALS` (code 430) is not a seva.** 13 "bookings" produced ₹13.40 Cr — these are **hundi donation-box counting events**, booked as receipts. Average ₹1.03 Cr per row. It will dominate any "top seva" chart and must be labelled as donation-box collection, not a purchased service. The dedicated `HundiCollection_*` tables are empty.
- **Prasadam items are counted as sevas.** Laddu, Cloth Bag, Panchakajjaya, Theertha Bottle and Maha Prasada together are ₹27.7 Cr — these are **retail prasadam sales**, not rituals. The `PRASADA (PS)` bucket separates them cleanly; consider splitting "sevas" from "prasadam sales" in the UI.
- **`DIRECT ENTRANCE` (code 75)** is an entry/darshan fee, again not a ritual.
- **Booking count ≠ devotee count.** One devotee buying 20 laddus is one row with `Qty = 20` in the detail table; the header is one row. Call the measure "receipts", not "devotees".

### 9.4 Seva rate card vs charged amount

`seva_seva.ssv_amount` is the **list price**; `DailySevaNew.Amount` is what was **charged**. They diverge legitimately (quantity, concessions, differential-amount sevas such as `CHANDIKA HOMA - DIFFARANCE AMOUNT`). Use `Amount` for revenue and `ssv_amount` only to display a rate card.

`seva_seva.IsNotActive` and `Deleteflag` exist and should filter the seva master, but note that revenue rows may legitimately reference a seva later deactivated — filter the *dimension* for pickers, not the *fact* for history.

---

## 10. Nirantara Seva Analysis

### 10.1 Terminology

"Nirantara Seva" (continuous/perpetual seva) corresponds to this system's **Sashwatha Seva** (`ಶಾಶ್ವತ ಪೂಜೆ`), the endowment model where a devotee funds a recurring ritual in perpetuity. All `seva_sevakarta*` / `seva_List*` tables belong to this module.

### 10.2 Which sevas are Nirantara — a classification gap

There is **no flag on `seva_seva` marking a seva as perpetual.** `seva_period` exists as a lookup with exactly the right archetypes — `SEVA_SASHWATHAPUJA`, `SEVA_YEARLYONECE`, `SEVA_PORNAMIPUJA`, `SEVA_AMAWAYAPUJA`, `SEVA_EVERYMASA`, and three more — but:

> **`seva_seva.ssv_period` is NULL on all 164 sevas.** The `seva_period` lookup is joined to nothing.

The only way to identify Nirantara sevas is **empirically**, from which sevas actually carry subscriptions in `seva_sevakartaDetails`:

| `ssk_SevaCode` | Seva | Subscriptions |
|---:|---|---:|
| 424 | **PERMANENT SEVA DEPOSIT** | 3,538 |
| 26 | MOOKAMBIKA ALANKARA POOJA | 699 |
| 3 | ASHTOTHARA KUMKUMARCHANA | 639 |
| 1 | SAHASRANAMA KUMKUMARCHANA | 618 |
| 6 | ASHTOTHARA BHASMARCHANA | 363 |
| 27 | VEERABHADRA ALANKARA POOJA | 330 |
| 419 | ANNA SANTARPANA DONATION | 114 |
| 17 | MAHA TRIMADHURA | 63 |
| 23 | KARPOORA ARATHI | 61 |
| 4 | SAHASRANAMA BHASMARCHANA | 24 |
| *(+ ~20 more, ≤ 23 each)* | | |

`ssv_code 424 "PERMANENT SEVA DEPOSIT"` is the clearest marker — 53% of all subscriptions.

### 10.3 Lifecycle stage-by-stage

| Stage | Table | Populated? | Evidence |
|---|---|:--:|---|
| **1. Master** | `seva_seva` | **PARTIAL** | Sevas exist, but no perpetual flag (`ssv_period` all NULL) |
| **2. Subscriber** | `seva_sevakarta` (5,177) | **YES** | 0 orphans against `NameMaster` / `AddressMaster` |
| **3. Booking** | `seva_sevakartaDetails` (6,659) | **YES** | Seva, dates, recurrence in 3 calendars |
| **4. Payment** | `seva_sevakartaPayment` (5,219) | **PARTIAL** | **No rows after FY2023-24** |
| **5. Schedule** | `seva_List` (15,091), `seva_List_Fridaypooja` (317,892) | **YES** | Due dates materialised through 2027 |
| **6. Execution** | `seva_List.seva_Prepared` / `Seve_closed` | **NO** | **All zero on all 15,091 rows** |
| **7. Attendance** | `Sevaattendedlist` | **NO** | **Table empty (0 rows)** |
| **8. Completion** | `seva_sevakartaDetails.NoOfSevaIssue` | **NO** | **Zero on all 6,659 rows** |

### 10.4 The execution evidence in full

This is the crux of the requirement, so the evidence is set out explicitly.

**(a) `seva_List` — all execution flags unused.**

| `seva_Prepared` | `Seve_closed` | `LetterPrinted` | Rows | Date span |
|---:|---:|---:|---:|---|
| 0 | 0 | 0 | **15,091** (100%) | 2022-01-01 → 2026-12-31 |

There is no second row in that result. Every scheduled Nirantara seva across five years is flagged not-prepared, not-closed, letter-not-printed.

**(b) `Sevaattendedlist` is empty.** The attendance table — keyed `(ssk_codeDetails, seva_Date)`, exactly the right grain — has **0 rows**.

**(c) `NoOfSevaIssue` is never incremented.**

| `NoOfSeva` | `NoOfSevaIssue` | Rows |
|---:|---:|---:|
| 1 | **0** | 5,285 |
| 0 | **0** | 1,373 |
| **-1** | **0** | 1 |

The "sevas issued" counter is zero on all 6,659 subscriptions. (One row has `NoOfSeva = -1`, an invalid negative.)

**(d) `seva_List_Fridaypooja.seva_Prepared` looks promising but does not mean execution.**

| Period | `seva_Prepared` | `Printed` | `Returned` | Rows |
|---|---:|---:|---:|---:|
| **FUTURE** (> 2026-09-16) | **1** | 0 | 0 | **17,808** |
| past | 1 | 0 | 0 | 209,143 |
| past | 1 | 1 | 0 | 90,929 |
| past | 0 | 0 | 0 | 12 |

> `seva_Prepared = 1` on **17,808 future-dated rows**, including dates into 2027. A flag that is already set for rituals that have not yet occurred cannot mean "the ritual was performed". It means "the schedule row was generated".

`Printed = 1` (90,929 past rows, never on future rows) is the closest available proxy for *something having happened* — but it records that an intimation letter or receipt was printed, not that the ritual took place. `Returned` is 0 everywhere.

**(e) Status columns are constant.**

| `ACTIVE` | `SEVACLOSED` | `Renewal` | `paymentflag` | Rows |
|---:|---:|---:|---|---:|
| 1 | 0 | 0 | P | 6,625 |
| 1 | 0 | 0 | S | 34 |

`ACTIVE = 1`, `SEVACLOSED = 0` and `Renewal = 0` on **every** subscription. Cancellation, closure and renewal are modelled but never used. Nothing in the data distinguishes an active subscription from a lapsed one.

**(f) `FDAMOUNT` — the endowment corpus — is never recorded.** All 6,659 rows have a non-NULL `FDAMOUNT`, but **zero rows have a positive value**. The deposit that funds the perpetual seva has no amount.

### 10.5 The seven questions, answered

| # | Question | Answer |
|---:|---|---|
| **1** | Can we identify all booked Nirantara Sevas? | **YES.** 6,659 subscriptions in `seva_sevakartaDetails`, 5,177 subscribers, joinable to seva name and to devotee name/address. Caveat: "Nirantara" must be inferred from subscription membership, not from a flag (§10.2). |
| **2** | Can we identify whether payment was made? | **PARTIALLY.** `seva_sevakartaPayment` has 5,219 receipts where `ssk_TotalAmtpaid = ssk_TotalAmount` on every row. **But it stops at FY2023-24** — zero payment rows for FY2024-25, FY2025-26, FY2026-27, while `seva_List_Fridaypooja` continues to schedule ₹10.88 Cr of sevas in FY2025-26. Payment coverage does not match booking coverage. |
| **3** | Can we identify whether the Nirantara Seva was actually performed? | **NO.** All execution flags are zero, the attendance table is empty, the issued counter is zero, and the one flag that *is* set is set on future dates. |
| **4** | Can we identify missed / pending / unfulfilled sevas? | **NO.** This requires a performed/not-performed signal, which does not exist. Everything before today is indistinguishable from everything after. |
| **5** | Can we identify cancellations? | **NO.** `SEVACLOSED = 0` and `ACTIVE = 1` on all 6,659 rows. No cancellation has ever been recorded. |
| **6** | Can we calculate Nirantara Seva revenue? | **PARTIALLY.** `seva_sevakartaPayment.ssk_TotalAmount` gives ₹2.02 Cr (FY2017-18), ₹0.09 Cr (FY2020-21), ₹0.19 Cr (FY2021-22), ₹0.07 Cr (FY2022-23), ₹0.85 Cr (FY2023-24) — then **nothing**. `seva_List_Fridaypooja.Amount` gives a continuing series (₹10.88 Cr FY2025-26) but that is a **scheduled/expected** amount, not a received payment, and the two cannot be reconciled. |
| **7** | Can we track active vs completed vs cancelled? | **NO.** All three status columns are constant across the entire table. Everything is "active"; nothing is ever completed or cancelled. |

### 10.6 Verdict

> **Booking information exists, but actual seva execution cannot be independently verified from the available data.**

A Nirantara Seva widget can honestly show: subscription counts, subscribers, seva mix, booking dates, recurrence pattern, and historical payments through FY2023-24. It **must not** show fulfilment rates, missed sevas, completion percentages, or active-vs-cancelled status. Any such figure would be fabricated.

The single highest-value data-capture change available to this programme is to begin recording execution — §17.1.

---

## 11. Expense Analysis

### 11.1 `FN_TRANSACTION` is not a general ledger

At 524,296 rows with perfectly balanced double-entry (debits equal credits to the rupee in every financial year), `FN_TRANSACTION` appears to be the temple's accounting system. It is not.

**FY2025-26, the only accounts with any movement:**

| Account | Name | Type | Rows | Amount |
|---|---|---|---:|---:|
| TEM002 | Saree Auction | C | 27,790 | ₹2.32 Cr |
| SAL001 | Sales | D | 58,110 | ₹2.32 Cr |
| TEM012 | cc9 | C | 30,320 | ₹0.00 |

Two accounts, one transaction type: **saree auction sales**. Total movement ₹2.32 Cr against ₹90.62 Cr of actual seva income — the ledger captures **2.6%** of the temple's money, and captures **no expenditure at all**.

Ledger totals by year (both sides equal, confirming income-only posting):

| FY | Rows (each side) | Amount |
|---|---:|---:|
| 2019-20 | 14,683 | ₹0.59 Cr |
| 2020-21 | 14,161 | ₹0.46 Cr |
| 2021-22 | 21,194 | ₹0.71 Cr |
| 2022-23 | 40,386 | ₹1.44 Cr |
| 2023-24 | 41,283 | ₹1.61 Cr |
| 2024-25 | 43,265 | ₹1.86 Cr |
| 2025-26 | 58,110 | ₹2.32 Cr |
| 2026-27 | 29,066 | ₹0.95 Cr |

`FN_VOUCHERPAYEMENT` — the payment-voucher table that would hold expenditure — has **0 rows**, despite having a 31-parameter stored procedure and a `View_Voucher` view.

**Conclusion: there is no expenditure data in the Kollur database.** Not partial, not low-quality — none.

### 11.2 What the expenditure widgets need, and what exists

| Dashboard element | Required data | Exists |
|---|---|:--:|
| Total expenditure ₹51.25 Cr | Expense transactions | **NO** |
| Net surplus ₹32.76 Cr | Income − expenditure | **NO** |
| Spend by category (7 bars) | Categorised expense postings | **NO** |
| Top 10 line items | Individual expense entries | **NO** |
| DC Approved Funds (4 funds × 5 yrs) | Grant master, sanction order, approving authority, category, per-year approved/spent | **NO** |
| Utilisation proof (invoice + photo) | Invoice records, document attachments | **NO** |
| Ongoing Works (3 projects) | Project master, budget, spend, dates, status | **NO** |

No table in **either** database holds a government grant, a sanction order, a work order, an invoice, a vendor, a purchase or a salary payment. The Temple Registry has `contractors` and `employees` registries, but they are master data with no amounts.

### 11.3 The one usable fragment: category names

`BudgetMainGroupMaster` (16 rows) holds a real, temple-specific expense taxonomy in Kannada that aligns closely with the dashboard's invented categories:

| # | Kannada | English | Dashboard category it matches |
|---:|---|---|---|
| 1 | ಸಿಬ್ಬಂದಿ ವರ್ಗ | Staff | Staff salaries & benefits |
| 2 | ಸಾದಿಲ್ವಾರು | Contingency/misc | Administration, welfare & other |
| 3 | ನಿತ್ಯ ಕಟ್ಲೆ | Daily rituals | Ritual & seva expenses |
| 4 | ಹೆಚ್ಚು ಕಟ್ಲೆ | Special rituals | Ritual & seva expenses |
| 5 | ರಥೋತ್ಸವ | Chariot festival | — |
| 6 | ಕಟ್ಟಡಗಳು | Buildings | Infrastructure, utilities & maintenance |
| 7 | ತೆರಿಗೆಗಳು | Taxes | Statutory dues, taxes & compliance |
| 8 | ಸಾಧನ ಸರಂಜಾಮು | Equipment | Infrastructure |
| 9 | ಜನಾರೋಗ್ಯ | Public health | — |
| 10 | ಶಿಕ್ಷಣ ಧಾರ್ಮಿಕ ಮತ್ತು ಧರ್ಮದಾಯಗಳು | Education, religious & charitable | Education institutions |
| 11 | ವ್ಯಾಜ್ಯ | Litigation | — |
| 12 | ಹೂಡಿಕೆಗಳು | Investments | — |
| 13 | ದೇವಸ್ಥಾನ ನೌಕರರಿಗೆ ಸೇವಾಂತ್ಯ ಸೌಲಭ್ಯಗಳು | Staff retirement benefits | Staff salaries & benefits |
| 14 | ವಿಶೇಷ ವೆಚ್ಚ | Special expenditure | — |
| 15 | ವಂತಿಗೆಗಳು | Contributions | — |
| 15 | ಇತರೆ | Other | Administration, welfare & other |

**Use this taxonomy** if an expense-capture feature is built — it is the temple's own, and reusing it avoids inventing categories. (Note the duplicate key `15`, a data-quality defect — §14.5.)

`BudgetDetails` holds 12 allocation rows, **all FY2017-18**, with `BudgetIssue` (amount spent) zero on 10 of 12. It is a nine-year-old planning artefact, not actuals.

### 11.4 The only expenditure figure available anywhere

`trust_financials.annual_expenditure` in the Temple Registry — a **single self-declared scalar per financial year**, submitted by the trust, with no breakdown, no transactions and no verification. It can populate a "declared expenditure" KPI but cannot support the category chart or the line-item list, and must be labelled as declared rather than measured.

---

## 12. Revenue & Financial KPI Analysis

### 12.1 Total revenue

**Formula:** `SUM(DailySevaNew.Amount)` where `BillCancled = 0 AND Deleteflag = 0`, grouped by `Finyear`, unioned across the live table and the six `DailySevaNew<FY>` archives.

**Do not include `DailySevaNewOld`** — it is a byte-exact duplicate of all six archives combined (16,982,270 rows both ways, identical per-year counts). Including it double-counts every historical year.

Total revenue should also state whether it means *seva revenue only* or *all income streams*. The three streams are separately available:

| Stream | FY2025-26 |
|---|---:|
| Seva/donation receipts (`DailySevaNew`) | ₹90.62 Cr |
| Saree auction proceeds (`SareeAuction`) | ₹2.30 Cr |
| Saree donations, donor-declared (`SareeDonation`) | ₹3.64 Cr *(in-kind, not cash)* |

Recommendation: headline = ₹90.62 Cr (cash receipts), with auction shown separately and saree donations shown as in-kind. Do not sum all three.

### 12.2 Monthly revenue

**Formula:** `SUM(Amount) GROUP BY YEAR(ReceiptDate), MONTH(ReceiptDate)`. **Status: AVAILABLE.** `ReceiptDate` is a genuine `smalldatetime`, and `Finyear` agrees with it exactly in both live years — no reconciliation needed. Daily and weekly grains are equally available.

### 12.3 Cash share

**Formula:** `COUNT(CASE WHEN (CardNo IS NULL OR CardNo='') AND (BankName IS NULL OR BankName='') THEN 1 END) * 100.0 / COUNT(*)`.

**Measured FY2025-26:** 3,950,071 of 3,950,072 receipts have no card/bank data → **99.99997%**.

**Caveat that must be carried into the UI.** `DailySevaNew` has **no payment-mode column**. The only columns are `CardNo`, `BankName` and `RemarksBanking`, all free-text and all effectively unused. A blank `CardNo` means "no card number was typed", which is not the same as "the devotee paid cash". By contrast `SareeDonation` and `SareeAuction` *do* have a `TranType smallint` column. Label this widget "no digital payment recorded (%)" rather than "paid in cash".

### 12.4 There is no payment status

`DailySevaNew` has no `PaymentStatus`, no `PaidFlag`, no due/outstanding column. The business model is counter-sale: a receipt is issued when money changes hands, so **receipt existence is payment**. There is no credit, no partial payment and no accounts-receivable concept for daily sevas.

This means **"booking" and "payment" are the same event** for daily sevas — a rare case where the usual caution does not apply. It does **not** hold for Nirantara sevas, where booking (`seva_sevakartaDetails`) and payment (`seva_sevakartaPayment`) are separate tables and diverge badly after FY2023-24 (§10.5 Q2).

### 12.5 Net income

**NOT DERIVABLE** from the Kollur database — there is no expenditure side (§11). The only possible net figure is `trust_financials.annual_income − annual_expenditure`, both self-declared, which is a different and non-comparable basis from computed receipt revenue.

### 12.6 Additional KPIs that ARE available but not currently shown

All of these are computable today and would add real oversight value:

| KPI | Source | Note |
|---|---|---|
| Revenue by counter | `COUNTERNO` → `CounterMaster` | 3 counters |
| Revenue by cashier | `ModifiedBy` → `UserTable` | Useful for audit |
| Average receipt value | `SUM(Amount)/COUNT(*)` | ₹229 FY2025-26 |
| Receipts per day / peak days | `ReceiptDate` | Festival peak analysis |
| Cancellation rate & value | `BillCancled` + `ReceiptCanceldetails` | 22 receipts, ₹1.14 L FY2025-26 |
| Revenue by income bucket | `seva_sannidhi` | 4-way split (§8.2) |
| Gold/silver received by metal | `HKanikeItems` ⋈ `HItemMaster` | kg per year (§7.11) |
| GST collected on auctions | `SareeAuction.CGSTAmt` + `SGSTAmt` | FY2023-24 onward only |
| YoY growth % | Derived | FY2025-26 +8.6% over FY2024-25 |

---

## 13. Chart & Tabular Reporting Requirements

Only fields that the data actually supports are listed.

### 13.1 Total revenue trend

**Chart** — line (or bar). X = financial year (or month). Y = revenue ₹Cr. Aggregation `SUM(Amount)`. Filter: FY range, `BillCancled = 0`.
**Table** — FY · Receipts · Gross revenue · Cancelled count · Cancelled value · Net revenue · YoY %. Totals row. Sort by FY. 8 rows, no pagination needed.

### 13.2 Revenue by seva type (Seva Revenue Split)

**Chart** — horizontal bar, top 15 + "Other". X = revenue ₹. Y = seva name. Group by `ssv_code`. Optional colour by `ssn_name` bucket.
**Table** — Seva code · Seva name (EN) · Seva name (KN) · Bucket · Receipts · Gross revenue · Avg ticket · Rate card · % of total. Totals row. Sortable on every numeric column. **Pagination required — 164 sevas.** Filters: FY, date range, bucket, counter.

### 13.3 Income by source bucket

**Chart** — donut. Segments = `seva_sannidhi.ssn_name` (4). Value = `SUM(Amount)`.
**Table** — Bucket · Receipts · Revenue · % of total. 4 rows + total.

### 13.4 Monthly revenue

**Chart** — bar or line. X = month. Y = revenue ₹Cr. Filter by FY.
**Table** — Month · Receipts · Revenue · Avg/day · % of FY. 12 rows + total.

### 13.5 Gold & silver received

**Chart** — grouped bar, two series (gold, silver). X = FY. Y = weight kg. A second chart with Y = item count.
**Table** — FY · Gold items · Gold kg · Silver items · Silver kg · Total items · Total kg. 10 rows + total.
**Must not include a value column** (§7.10). Show a footnote: *"Weight is recorded per item; the temple's system does not record a valuation or purity grade."*

### 13.6 Special / high-value sevas

**Chart** — bar. X = seva. Y = revenue. (Or omit the chart; 7 rows is a table.)
**Table** — Seva · Rate card ₹ · Bookings · Revenue ₹. Sort by rate card desc. Include zero-booking sevas but show the zero.

### 13.7 Nirantara Seva

**Chart** — bar, X = seva name, Y = subscription count. A second chart: subscriptions by booking year.
**Table** — Seva · Subscriptions · Subscribers · Earliest booking · Latest booking · Last recorded payment FY.
**Must not include** fulfilment %, missed count, completion status or active/cancelled split (§10.6). Show a prominent caveat: *"Execution of these sevas is not recorded in the source system; only bookings are shown."*

### 13.8 Cancellations

**Chart** — not worth one at current volume (22/year).
**Table** — Receipt no · Date · Seva · Amount · Cancelled by · Reason · Cancelled date. From `DailySevaNew` ⋈ `ReceiptCanceldetails`.

### 13.9 Formats not supportable

| Requested | Why not |
|---|---|
| Expenditure by category (chart + table) | No expense data (§11) |
| Net income trend | No expense data |
| DC approved funds approved-vs-spent | No grant data |
| Ongoing works progress | No project data |
| Gold/silver value trend | No valuation (§7.10) |
| Nirantara fulfilment rate | No execution data (§10.4) |
| Refund analysis | No refund concept (§5) |
| Payment-mode breakdown | No payment-mode column (§12.3) |

---

## 14. Data Quality Findings

### 14.1 Detail table amounts are unreliable — **affects accuracy, CRITICAL**

`DailySevaNewDetails.TotalAmount` ≠ `Amount × Qty` on an unknown subset of rows, and the table total (₹53.78 Cr) understates the header total (₹90.62 Cr) by 41% for FY2025-26. **Use header `Amount` only** (§9.1).

### 14.2 `DailySevaNewOld` duplicates every archive — **affects accuracy, CRITICAL**

16,982,270 rows identical to the six per-year archives combined, same `Finyear` values, same per-year counts. Any query that unions it with the archives **doubles all history**. Exclude it explicitly.

### 14.3 Nirantara payment data stops at FY2023-24 — **affects accuracy, HIGH**

`seva_sevakartaPayment` has zero rows for FY2024-25, FY2025-26 and FY2026-27, while `seva_List_Fridaypooja` continues to schedule ₹10.88 Cr (FY2025-26) and ₹3.55 Cr (FY2026-27). Either payments moved to another system or recording stopped. **Nirantara revenue for recent years must not be reported as zero** — it must be reported as *not recorded*.

### 14.4 Gold/silver purity never captured — **affects completeness, MEDIUM**

`HKanikeItems.Purity` is an **empty string on all 2,225 rows**, though `PurityMaster` defines 9 grades (916, BIS Hallmark, 22/24 carat, Bar Silver, etc.). Combined with `Rate`/`Amount` being zero, valuation is impossible.

### 14.5 Invalid and impossible values — **affects accuracy, MEDIUM**

| Issue | Location | Detail |
|---|---|---|
| Impossible earliest date | `HKanikeItems.ssg_receiptdate` | **1969-09-26** |
| Impossible earliest date | `seva_sevakartaPayment.ReceiptDate` | **1955-01-18** |
| FY/date mismatch | `FN_TRANSACTION` | Rows dated **2004-12-26** carry `Finyear = 20252026` |
| Negative quantity | `seva_sevakartaDetails.NoOfSeva` | 1 row with **−1** |
| Duplicate key | `BudgetMainGroupMaster.BudgetMainGroup` | Value **15** used twice |
| Orphan detail rows | `DailySevaNewDetails20192020` | 2,530,986 details vs 2,530,984 headers (**+2**) |
| All-zero money column | `SareeDonation.SareeKanike` | Zero on every row; use `SareeValueDonor` |

Every financial query must bound `ReceiptDate` to a sane window (e.g. ≥ 2015-01-01) rather than trusting `MIN()`.

### 14.6 Structural risks inherited from the source schema — **affects reliability, HIGH**

From the companion structural analysis:

- **Zero foreign keys, zero unique constraints, zero check constraints** in the entire database. Integrity is application-enforced. Spot checks came back clean (0 orphan `SevaCode`s, 0 orphan detail rows on the live pair, 0 orphan name/address refs), but nothing prevents future drift.
- **Only 3 non-PK indexes** across ~50 M rows. `DailySevaNew` has `idx_ReceiptDate`; **none of the six archives has a date index at all.** Any multi-year report is a multi-gigabyte scan. This is the single biggest performance constraint on the integration design (§19).
- **No view or stored procedure references the archive tables** — all historical access is by dynamically-built table names in application code.
- `ReceiptNo` is **nullable and unindexed** on `DailySevaNew`; `ModifiedBy char(10)` sits inside the primary key.
- Compatibility level 100 (SQL Server 2008) on a 2025 engine.

### 14.7 Future-dated records — **by design, must be filtered**

`seva_List_Fridaypooja` extends to **2027-07-26** and `seva_List` to **2026-12-31**. These are legitimately generated forward schedules, not errors — but any "sevas performed" or "revenue" measure must filter `seva_Date <= CURRENT_DATE`. `DailySevaNew` contains **no** future-dated receipts (verified 0).

### 14.8 Test / mock records

None found in the financial tables. `DailySevaNew` has no null, zero or negative amounts and no null receipt numbers in either live financial year. `seva_seva.ssv_SevaType` has 18 single-row categories that look ad-hoc, but this does not affect financial aggregation.

### 14.9 Kannada under a Latin collation — **affects display, LOW**

Database collation is `SQL_Latin1_General_CP1_CI_AS`. Kannada is stored correctly in `nvarchar` columns (`ssv_Name`, `BudgetMainGroupName`, `ItemName`) but sorts by code point, and any `varchar` column cannot hold it. Reads must use UTF-8 (`sqlcmd -f 65001`, or an appropriately configured JDBC driver) or all Kannada arrives as `?`.

---

## 15. Historical Data Coverage

| Stream | Earliest | Latest | Usable from |
|---|---|---|---|
| Seva receipts — live (`DailySevaNew`) | 2025-04-01 | **2026-07-26** | FY2025-26 |
| Seva receipts — archive (`DailySevaNew<FY>`) | 2019-04-01 | 2025-03-31 | FY2019-20 |
| Saree donations | 2016-04-14 | 2026-07-26 | FY2016-17 |
| Saree auctions | 2016-07-12 | 2026-07-26 | FY2016-17 |
| Gold/silver kanike | *1969-09-26* (invalid) | 2026-07-25 | FY2015-16 |
| Accounting ledger | *2004-12-26* (invalid) | 2026-07-26 | FY2019-20 (auction only) |
| Nirantara payments | *1955-01-18* (invalid) | **2025-01-31** | FY2017-18 → **FY2023-24** |
| Nirantara schedule (Friday pooja) | 2019-05-01 | **2027-07-26** (future) | FY2018-19 |

### 15.1 Coverage matrix by financial year

| FY | Seva revenue | Saree don. | Saree auction | Gold/silver | Nirantara payment |
|---|:--:|:--:|:--:|:--:|:--:|
| 2015-16 | — | — | — | ✅ *(opening stock)* | — |
| 2016-17 | — | ✅ | ✅ | ✅ | — |
| 2017-18 | — | ✅ | ✅ | ✅ | ✅ |
| 2018-19 | — | ✅ | ✅ | ✅ | — |
| 2019-20 | ✅ | ✅ | ✅ | ✅ | — |
| 2020-21 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2021-22 | ✅ | ✅ | ✅ | **❌ gap** | ✅ |
| 2022-23 | ✅ | ✅ | ✅ | **❌ gap** | ✅ |
| 2023-24 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2024-25 | ✅ | ✅ | ✅ | ✅ | **❌ stops** |
| 2025-26 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 2026-27 | 🟡 to 26 Jul | 🟡 | 🟡 | 🟡 | ❌ |

### 15.2 Consequences for date filters

1. **Seva revenue (the main series) begins FY2019-20.** Any filter offering earlier years will return empty. Constrain the picker.
2. **FY2026-27 is partial** — data ends 2026-07-26, roughly seven weeks before this analysis. Label it clearly (the mock uses an asterisk: `2026-27*`). Do not annualise it silently.
3. **Two financial years live in `DailySevaNew` simultaneously** (FY2025-26 and FY2026-27) because the FY2025-26 archive cut has not been made. Never assume one FY per table.
4. **Gold/silver has real gaps** at FY2021-22 and FY2022-23 — zero rows, not zero donations necessarily. Render as a gap, not as 0.
5. **Nirantara payments end FY2023-24.** Render recent years as "not recorded", never as ₹0.
6. **Saree data starts three years earlier** than seva data (FY2016-17 vs FY2019-20), so a combined "total income" series will show a discontinuity at FY2019-20.
7. `GST` on auctions only exists from **FY2023-24**; earlier years show ₹0 taxable, which is an absence of recording, not an exemption.

---

## 16. Data Gaps

### 16.1 CRITICAL — the report cannot be generated at all

| # | Missing data | Required by | Derivable? | Where it must come from |
|---:|---|---|---|---|
| C1 | **Expense transactions** — amount, date, category, payee, description | Widget 6 (total expenditure, net surplus, category chart, top-10 items) | **No.** `FN_TRANSACTION` holds income postings only; `FN_VOUCHERPAYEMENT` is empty | New capture in Temple Registry, **or** a different temple accounting system |
| C2 | **Government / DC grant master** — fund name, category, sanction order no., approving authority, approved amount per FY | Widget 7, Widget 4 (gauge) | **No.** No such table in either database | New Temple Registry module — this is DC-office data, not temple-POS data |
| C3 | **Grant utilisation** — spent amount per fund per FY, invoice no., description, proof document | Widget 7 (drill-down + receipt proof) | **No** | New Temple Registry module |
| C4 | **Nirantara Seva execution record** — performed yes/no, date performed, by whom | Widget 14 fulfilment, "whether they are actually taking place" | **No.** All flags zero; `Sevaattendedlist` empty; `NoOfSevaIssue` zero (§10.4) | Source-system change at Kollur, **or** a new fulfilment-capture screen |
| C5 | **Gold/silver valuation** — per-item rate or purity | Widget 10 | **No.** `Rate`/`Amount` zero post-FY2015-16; `Purity` empty on all rows | Appraisal process + capture; cannot be back-filled |

### 16.2 IMPORTANT — the report works but is limited or must carry a caveat

| # | Missing data | Required by | Derivable? | Mitigation |
|---:|---|---|---|---|
| I1 | **Payment mode** (cash / card / UPI / cheque) | Widget 3 | **Inferred only** from `CardNo`/`BankName` emptiness | Relabel to "no digital payment recorded"; add a real mode column at source |
| I2 | **Nirantara payments FY2024-25 onward** | Widget 14 revenue | **No** — recording stopped (§14.3) | Show "not recorded", never ₹0; investigate whether collection moved elsewhere |
| I3 | **Nirantara cancellation / active status** | Widget 14 status split | **No** — `ACTIVE`/`SEVACLOSED` constant (§10.4e) | Omit the status widget entirely |
| I4 | **Perpetual-seva classifier** | Identifying which sevas are Nirantara | **Partially** — infer from subscription membership (§10.2) | Maintain an explicit seva-classification map in Temple Registry |
| I5 | **Endowment corpus (`FDAMOUNT`)** | Nirantara corpus value | **No** — positive on zero rows | Capture at source if corpus reporting is wanted |
| I6 | **Refund / reversal** | Net revenue | **No** — no refund table, no negative amounts | Treat cancellation as the only reversal; state that net = gross − cancelled |
| I7 | **Ongoing works / projects** | Widget 8 | **No** | New Temple Registry module (pairs with C2/C3) |
| I8 | **Temple 300001 ↔ TempleCode 43 link** | Every query | **No** — recorded nowhere | Configuration/mapping table (§20) |

### 16.3 OPTIONAL — useful but not required

| # | Missing data | Value if added |
|---:|---|---|
| O1 | Devotee identity key | `PersonName` is free text; no devotee master for daily sevas, so repeat-donor analysis is impossible |
| O2 | Donor PAN / 80G details | Needed only if tax receipts are in scope |
| O3 | Campaign / appeal tagging | Would enable donation-drive reporting |
| O4 | Seva category beyond the 4 `sannidhi` buckets | Would let "prasadam retail" split cleanly from "rituals" (§9.3) |
| O5 | Hundi collection detail (denominations) | Tables exist and are empty; hundi is booked as a single ₹1 Cr receipt |
| O6 | Budget vs actual | `BudgetDetails` is FY2017-18 only |

---

## 17. Required Additional Data

### 17.1 The highest-value change: Nirantara Seva execution capture

The explicit requirement — *"determine what Nirantara Sevas are booked and whether they are actually taking place"* — **cannot be met with the current data**, and no amount of transformation will change that.

The encouraging part is that the source system was *designed* for it and the plumbing already exists:

- `seva_List.seva_Prepared`, `Seve_closed`, `LetterPrinted` — three unused boolean columns at the right grain
- `Sevaattendedlist(ssk_codeDetails, seva_Date)` — an empty attendance table at exactly the right grain
- `seva_sevakartaDetails.NoOfSevaIssue` — an unused issued-count column

Nothing needs to be designed. Someone needs to **start setting these flags** when a perpetual seva is performed. Two options:

1. **At source (preferred).** Change the temple's operational process so the daily seva list is marked off. Gives an authoritative record and needs no schema change. Requires the temple's software vendor and a workflow change.
2. **In Temple Registry.** A fulfilment-confirmation screen writing to a new registry table keyed on `(ssk_code, ssk_codeDetails, seva_date)`. Within this programme's control, but it is a second record of truth and depends on staff double-entry.

Either way, **history cannot be recovered.** Fulfilment reporting can only begin from the day capture starts.

### 17.2 Expenditure and grants

C1–C3 are not gaps in the Kollur data — they are **an entire domain the temple's POS system was never meant to hold**. The expenditure figures on the current dashboard came from a scanned Kannada register (`Expenditure.pdf`), not a database.

Three honest options:

| Option | Effort | Fidelity | Note |
|---|---|---|---|
| **A. Declared totals only** | Minimal | Low | Use `trust_financials.annual_expenditure`. Two scalars per year. No category chart, no line items. **Available today.** |
| **B. Structured annual register capture** | Medium | Medium | A DC-office form capturing the income/expenditure register by category, using `BudgetMainGroupMaster`'s 16 Kannada heads (§11.3). Reproduces the current widget with *verified declared* data. |
| **C. Full expense transactions** | High | High | Requires the temple to run actual accounting. Out of scope for a registry portal. |

**Recommendation: Option B**, with Option A as the interim. It reproduces what the dashboard already shows, at annual grain, with provenance — and it is the only one that makes the DC-approved-funds widget (C2/C3) achievable, since grant sanction and utilisation are DC-office facts that the DC office can capture directly.

### 17.3 Fields to add at source (recommendations only — no change made)

| Table | Field | Why |
|---|---|---|
| `DailySevaNew` | `PaymentMode` (tinyint) | Removes the inference in §12.3 |
| `HKanikeItems` | populate `Purity`, `Rate` | Enables valuation (C5) |
| `seva_seva` | populate `ssv_period` | Enables perpetual-seva classification (I4) |
| `seva_List` | populate `seva_Prepared` / `Seve_closed` | Enables fulfilment (C4) |
| `seva_sevakartaDetails` | maintain `ACTIVE` / `SEVACLOSED` | Enables status split (I3) |

> Per Step 15, none of these has been applied. The Kollur database was opened read-only and is unmodified.

---

## 18. Source-of-Truth Definitions

| Concept | Source of truth | Rationale |
|---|---|---|
| **Seva revenue** | `DailySevaNew.Amount` | Only internally consistent amount; detail table is 41% short and self-contradictory (§9.1) |
| **Transaction date** | `DailySevaNew.ReceiptDate` | Real date; agrees with `Finyear` exactly |
| **Financial year** | `DailySevaNew.Finyear` | Verified consistent with `ReceiptDate` in both live years |
| **Seva identity** | `seva_seva.ssv_code` | 0 orphans from 5.4 M receipt rows |
| **Donation revenue** | `DailySevaNew.Amount` where `seva_sannidhi.ssn_Type = 'KN'` | `Donation` table is empty; bucket is the only classifier (§8.2) |
| **Saree donation value** | `SareeDonation.SareeValueDonor` | `SareeKanike` is zero on every row |
| **Saree auction revenue** | `SareeAuction.AuctionAmt` | Also the only stream posted to `FN_TRANSACTION` |
| **Gold/silver weight** | `HKanikeItems.Qty` (grams) | `InKgs = Qty/1000` confirms the unit |
| **Gold vs silver** | `HKanikeItems.ssg_Itemslno` → `HItemMaster` | 2 = ಬಂಗಾರ, 1 = ಬೆಳ್ಳಿ |
| **Gold/silver value** | **None exists** | `Rate`/`Amount` zero post-FY2015-16 |
| **Booking (daily seva)** | `DailySevaNew` row | Counter sale — booking and payment are one event (§12.4) |
| **Payment (daily seva)** | Same row | No credit model, no payment status |
| **Booking (Nirantara)** | `seva_sevakartaDetails` row | Distinct from payment |
| **Payment (Nirantara)** | `seva_sevakartaPayment.ssk_TotalAmtpaid` | **Valid only through FY2023-24** |
| **Nirantara execution** | **None exists** | §10.4 — do not substitute `seva_Prepared` |
| **Cancellation** | `DailySevaNew.BillCancled` (+ `ReceiptCanceldetails` for audit) | Only reversal mechanism |
| **Refund** | **None exists** | No refund table, no negative amounts |
| **Expense** | **None exists in Kollur.** Interim: `trust_financials.annual_expenditure` (declared) | §11 |
| **Net income** | **Not derivable** | No expense side |
| **Temple identity** | `temples.id = 300001` ↔ `TempleCode = 43` | Must be explicitly configured (§20) |

### 18.1 Standard filter predicate

Every revenue query should apply, and the API should not allow it to be switched off:

```sql
WHERE Deleteflag  = 0        -- soft delete
  AND BillCancled = 0        -- cancelled receipts excluded from revenue
  AND TempleCode  = 43       -- defensive; all rows are 43 today
  AND ReceiptDate >= '2015-01-01'   -- guards against invalid legacy dates (§14.5)
```

Cancelled receipts should be **reported separately**, not silently dropped — the DC office needs to see cancellation volume as an integrity signal.

---

## 19. Recommended Reporting Architecture

### 19.1 Option comparison

| | **A — Direct DB reads** | **B — Sync to Registry** | **C — Read replica / reporting layer** | **D — Scheduled ETL to aggregates** |
|---|---|---|---|---|
| Kollur load | Every page view scans GBs | One-off + deltas | Replica absorbs it | One nightly window |
| Performance | **Unacceptable** — no date index on any archive (§14.6) | Good | Good | **Excellent** — reads are pre-aggregated |
| Cross-DB joins | MySQL ⟷ SQL Server at query time | Native | Still cross-DB | Native |
| Data volume | — | **22 M rows imported for ~200 aggregate numbers** | — | ~2,000 aggregate rows |
| Freshness | Real time | Near real time | Real time | Daily (source is 7 weeks stale anyway) |
| Failure blast radius | Dashboard dies if Kollur is down | Contained | Contained | Contained |
| Kollur modification | None | None | Replica setup | None |
| Fit to this codebase | Poor — no SQL Server datasource exists | Poor | Poor | **Good** — Flyway + Spring already in place |

### 19.2 Recommendation — Option D (scheduled ETL into aggregate tables)

**Why D:**

1. **The indexing reality forces it.** Three non-PK indexes across ~50 M rows, and *no date index on any of the six archive tables*. A live multi-year query is a multi-gigabyte scan. This alone rules out A and C for interactive use.
2. **The output is tiny.** Every widget on this dashboard resolves to a few hundred aggregate rows: 8 years × 164 sevas, 12 months × 8 years, 10 years of gold/silver. Importing 22 M raw receipts to compute ~2,000 numbers is the wrong trade.
3. **Freshness is not a requirement.** The source data is already ~7 weeks stale (latest receipt 2026-07-26). This is a DC oversight dashboard reviewed periodically, not an operational console. Nightly is ample.
4. **It fits the existing stack.** Flyway migrations, Spring Boot, MySQL. Aggregate tables are ordinary registry tables; no second datasource in the request path.
5. **Cross-database joins disappear.** Once aggregates land in MySQL, joining to `temples`, `trusts` and `trust_financials` is native.
6. **The Kollur database stays untouched and read-only** — satisfying Step 15 permanently, not just during analysis.

**Shape:**

```
KOLSOHAM_LOCAL (SQL Server, read-only)
        │  nightly, read-only JDBC, off-peak
        ▼
  ETL job  ── aggregate in SQL ── validate ── upsert
        │
        ▼
Temple Registry (MySQL): 5 aggregate tables (§20)
        │
        ▼
  Finance REST APIs  ──►  React dashboard (charts + tables + filters + export)
```

**Guardrails:**

- Connect with a **read-only SQL Server login**; never issue DDL or DML.
- Run off-peak; the archive scans are heavy.
- **Exclude `DailySevaNewOld`** explicitly (§14.2) — a single mistake here doubles all history.
- Recompute the current and prior FY each run (late entries and cancellations); treat closed years as immutable after one confirming pass.
- Record `source_extracted_at` and `source_max_receipt_date` on every row so the UI can show data currency honestly.
- Reconcile each run against the previous; alert on >2% movement in a closed year.

**Interim:** for a first demo, a one-off extract into the same tables is acceptable — the table shape does not change when the job is automated.

---

## 20. Recommended Reporting Data Model

Five new tables. All are **aggregates**, not row-level copies. The existing registry is reused wherever it already has the answer.

### 20.1 `temple_source_system_map`

**Purpose:** the missing link between the two databases (gap I8).

| Field | Type | Note |
|---|---|---|
| `temple_id` | BIGINT **FK → `temples.id`** | 300001 |
| `source_system` | VARCHAR(50) | `KOLSOHAM` |
| `source_temple_code` | VARCHAR(20) | `43` |
| `is_active` | TINYINT(1) | |
| `last_synced_at` | DATETIME | Data-currency badge |

**Why:** the 300001 ↔ 43 mapping currently exists nowhere but in the head of whoever wrote the ETL. Without it the integration is hard-coded and cannot extend to a second temple.

### 20.2 `temple_revenue_summary`

**Purpose:** widgets 1, 2, 3, 5 — headline revenue, monthly trend, cash share, bucket donut.

| Field | Type | Source |
|---|---|---|
| `temple_id` | BIGINT FK | map |
| `financial_year` | VARCHAR(10) | `Finyear` → `2025-26` |
| `period_type` | ENUM('FY','MONTH') | grain |
| `period_start` / `period_end` | DATE | from `ReceiptDate` |
| `income_bucket` | VARCHAR(50) | `seva_sannidhi.ssn_name` |
| `receipt_count` | BIGINT | `COUNT(*)` |
| `gross_amount` | DECIMAL(18,2) | `SUM(Amount)` |
| `cancelled_count` / `cancelled_amount` | BIGINT / DECIMAL | `BillCancled = 1` |
| `net_amount` | DECIMAL(18,2) | gross − cancelled |
| `digital_payment_count` | BIGINT | `CardNo`/`BankName` non-empty |
| `source_extracted_at` | DATETIME | provenance |

Unique: `(temple_id, financial_year, period_type, period_start, income_bucket)`. ~400 rows.

### 20.3 `temple_seva_revenue`

**Purpose:** widget 13 (Seva Revenue Split) and widget 12 (special sevas).

| Field | Type | Source |
|---|---|---|
| `temple_id`, `financial_year` | | |
| `seva_code` | INT | `ssv_code` |
| `seva_name_en` / `seva_name_local` | VARCHAR | `ssv_Name_English` / `ssv_Name` |
| `income_bucket` | VARCHAR(50) | `ssn_name` |
| `rate_card_amount` | DECIMAL(18,2) | `ssv_amount` |
| `booking_count` | BIGINT | `COUNT(*)` |
| `gross_amount` / `cancelled_amount` / `net_amount` | DECIMAL(18,2) | |
| `is_active_seva` | TINYINT(1) | `IsNotActive = 0` |

Unique: `(temple_id, financial_year, seva_code)`. ~164 × 8 ≈ 1,300 rows.

### 20.4 `temple_inkind_donation_summary`

**Purpose:** widgets 9 and 11 (gold/silver), plus saree streams.

| Field | Type | Source |
|---|---|---|
| `temple_id`, `financial_year` | | |
| `donation_type` | ENUM('GOLD','SILVER','SAREE','SAREE_AUCTION') | `ssg_Itemslno`; saree tables |
| `item_count` | BIGINT | |
| `total_weight_grams` | DECIMAL(18,3) | `SUM(Qty)` — **gold/silver only** |
| `declared_value` | DECIMAL(18,2) | `SareeValueDonor` / `AuctionAmt`; **NULL for gold/silver** |
| `gst_amount` | DECIMAL(18,2) | `CGSTAmt + SGSTAmt`; FY2023-24+ |
| `value_basis` | VARCHAR(30) | `'DONOR_DECLARED'` / `'AUCTION_REALISED'` / **`'NOT_RECORDED'`** |

`value_basis` is deliberate: it forces the UI to distinguish a real value from an absent one, so no future developer reintroduces a ₹12,000/item assumption.

### 20.5 `temple_nirantara_seva_summary`

**Purpose:** widget 14, with the execution gap encoded in the schema itself.

| Field | Type | Source |
|---|---|---|
| `temple_id` | BIGINT FK | |
| `seva_code` | INT | `ssk_SevaCode` |
| `seva_name_en` | VARCHAR | |
| `subscription_count` | BIGINT | `COUNT(*)` on `seva_sevakartaDetails` |
| `subscriber_count` | BIGINT | `COUNT(DISTINCT ssk_code)` |
| `earliest_booking_date` / `latest_booking_date` | DATE | `ssk_Date`, bounded ≥ 2000-01-01 |
| `last_payment_financial_year` | VARCHAR(10) | `seva_sevakartaPayment` — **FY2023-24 max** |
| `total_recorded_payment` | DECIMAL(18,2) | |
| `execution_data_available` | TINYINT(1) | **Hard-coded `0` (§10.4)** |

`execution_data_available` makes the limitation a first-class, queryable fact. The API returns it, and the UI renders the caveat from it rather than from a hard-coded string — so the day execution capture begins (§17.1), the flag flips and the caveat disappears on its own.

### 20.6 Reused, not rebuilt

| Need | Existing structure |
|---|---|
| Temple identity, grade, status | `temples`, `temple_profile_current` |
| Declared annual income/expenditure | **`trust_financials`** — the only expenditure figure available |
| Trust identity | `trusts` |
| Access control on the finance tab | `access_control_policies`, `TARGET_KEYS.TAB_DC_TEMPLE_FINANCE` |
| Audit of exports | `audit_export_events`, `export_job_records` |
| Document attachments | `documents` |

### 20.7 Deliberately not built

- **No raw transaction table.** 22 M rows for ~2,000 aggregate numbers.
- **No expense table yet.** Until §17.2 Option B is agreed there is nothing to put in it; building an empty table invites fabricated data.
- **No grant/works table yet.** Same reasoning — these are DC-office capture, a separate feature.
- **No devotee master.** `PersonName` is free text with no key; a devotee dimension cannot be built reliably (gap O1).

---

## 21. Master Report Matrix

| # | Dashboard report | Available? | Source table(s) | Source columns | Transformation | Missing data | Can generate? |
|---:|---|---|---|---|---|---|:--:|
| 1 | Total money collected (by FY) | **AVAILABLE W/ TRANSFORM** | `DailySevaNew` + 6 archives | `Amount`, `Finyear`, `BillCancled` | UNION 7 tables (exclude `…Old`), SUM, GROUP BY FY | — | **YES** |
| 2 | Money collected month-on-month | **AVAILABLE** | `DailySevaNew` | `Amount`, `ReceiptDate` | SUM GROUP BY YEAR/MONTH | — | **YES** (better than mock) |
| 3 | Paid in cash (gauge) | **AVAILABLE W/ TRANSFORM** | `DailySevaNew` | `CardNo`, `BankName` | Inferred from emptiness | Real payment-mode column | **YES, relabel** |
| 4 | DC approved funds used (gauge) | **NOT AVAILABLE** | — | — | — | Entire grant domain | **NO** |
| 5 | Where the money comes from (donut) | **AVAILABLE** | `DailySevaNew` ⋈ `seva_seva` ⋈ `seva_sannidhi` | `Amount`, `ssv_code`, `ssn_name` | JOIN + SUM + top-N | — | **YES** |
| 6 | Where the money goes: expenditures | **NOT AVAILABLE** | — | — | — | All expense transactions | **NO** |
| 7 | DC Approved Funds approved vs spent | **NOT AVAILABLE** | — | — | — | Grant master + utilisation + proof | **NO** |
| 8 | Ongoing Works | **NOT AVAILABLE** | — | — | — | Project/works domain | **NO** |
| 9 | Gold & silver — item counts | **AVAILABLE** | `HKanikeItems` | `Finyear`, `BillCancel` | COUNT GROUP BY FY | — | **YES** |
| 10 | Gold & silver — estimated value | **NOT DERIVABLE** | `HKanikeItems` | `Rate`, `Amount` (all 0) | — | Valuation + purity | **NO — remove** |
| 11 | Gold & silver — weight | **AVAILABLE** | `HKanikeItems` ⋈ `HItemMaster` | `Qty` (g), `ssg_Itemslno` | SUM/1000, split by metal | — | **YES** (better than mock) |
| 12 | Special / high-value sevas | **AVAILABLE W/ TRANSFORM** | `seva_seva` ⋈ `DailySevaNew` | `ssv_amount`, `Amount` | Threshold + LEFT JOIN bookings | — | **YES, enriched** |
| 13 | **Seva Revenue Split** | **AVAILABLE** | `DailySevaNew` ⋈ `seva_seva` | `Amount`, `ssv_code`, `ssv_Name_English` | JOIN + GROUP BY seva | — | **YES** |
| 14 | **Nirantara Seva — bookings** | **AVAILABLE W/ TRANSFORM** | `seva_sevakartaDetails` ⋈ `seva_sevakarta` ⋈ `seva_seva` | `ssk_SevaCode`, `ssk_Date` | JOIN + GROUP BY | Perpetual-seva flag (inferred) | **YES** |
| 15 | **Nirantara Seva — payments** | **PARTIALLY AVAILABLE** | `seva_sevakartaPayment` | `ssk_TotalAmtpaid`, `Finyear` | SUM GROUP BY FY | **No data after FY2023-24** | **PARTIAL** |
| 16 | **Nirantara Seva — execution / fulfilment** | **NOT AVAILABLE** | `seva_List`, `Sevaattendedlist` | all flags = 0 / empty | — | Execution record | **NO** |
| 17 | Nirantara — active vs cancelled | **NOT AVAILABLE** | `seva_sevakartaDetails` | `ACTIVE`/`SEVACLOSED` constant | — | Status maintenance | **NO** |
| 18 | Net income / surplus | **NOT DERIVABLE** | — | — | — | Expense side | **NO** |
| 19 | Donation revenue | **AVAILABLE** | `DailySevaNew` ⋈ `seva_sannidhi` | `Amount`, `ssn_Type='KN'` | Bucket filter + SUM | — | **YES** (₹20.55 Cr FY25-26) |
| 20 | Saree donation & auction | **AVAILABLE** | `SareeDonation`, `SareeAuction` | `SareeValueDonor`, `AuctionAmt` | SUM GROUP BY FY | — | **YES** |
| 21 | GST on auctions | **PARTIALLY AVAILABLE** | `SareeAuction` | `CGSTAmt`, `SGSTAmt` | SUM | Zero before FY2023-24 | **PARTIAL** |
| 22 | Cancellations / reversals | **AVAILABLE** | `DailySevaNew`, `ReceiptCanceldetails` | `BillCancled`, `Remarks` | Filter + JOIN | — | **YES** |
| 23 | Refunds | **NOT AVAILABLE** | — | — | — | No refund concept | **NO** |
| 24 | Revenue by counter / cashier | **AVAILABLE** | `DailySevaNew` ⋈ `CounterMaster` / `UserTable` | `COUNTERNO`, `ModifiedBy` | JOIN + GROUP BY | — | **YES** (new, valuable) |

---

## 22. Implementation Recommendations

### 22.1 Phasing

**Phase 0 — Decisions before code (blocking)**

1. Confirm the expenditure approach (§17.2): Option A declared-only, or Option B structured annual register. **This decides whether 5 of 12 widgets survive or get replaced.**
2. Confirm whether Nirantara execution capture (§17.1) is in scope. If not, widget 16/17 are permanently out and the UI caveat becomes permanent.
3. Confirm the retention/refresh policy for a nightly read against a production temple database.

**Phase 1 — Integration foundation**
Read-only SQL Server login · Flyway migrations for the 5 tables in §20 · `temple_source_system_map` seeded with 300001 ↔ 43 · connectivity test.

**Phase 2 — ETL**
Aggregation queries per §18.1 · **explicit exclusion of `DailySevaNewOld`** · invalid-date guards · reconciliation against the §1 control totals · provenance columns populated.

**Phase 3 — APIs**
`GET /api/dc/temples/{id}/finance/revenue-summary` · `…/seva-revenue` · `…/inkind-donations` · `…/nirantara-sevas`. FY and date-range parameters. Every response carries `dataAsOf` and, where relevant, `executionDataAvailable`.

**Phase 4 — Dashboard rebuild**
Replace the static `<iframe>` with a React feature module under `frontend/src/features/dc/`. Delete `frontend/public/dc/temple-300001-dashboard.html` and the `FINANCE_DASHBOARD_TEMPLE_ID` gate once parity is reached. Charts and tables per §13, filters (FY, date range, bucket, counter), CSV export through the existing `export_job_records` / `audit_export_events` plumbing.

**Phase 5 — Validation**
Reconcile every rendered number against a direct query. Compare computed revenue with `trust_financials.annual_income` and report divergence rather than hiding it.

### 22.2 Non-negotiables

1. **Never use `DailySevaNewDetails` for money** (§9.1).
2. **Never include `DailySevaNewOld` in a union** (§14.2).
3. **Never present an assumed value as a figure.** If it is assumed, it is not a number — it is a blank with an explanation. This applies directly to the ₹12,000/item valuation.
4. **Never render "booked" as "performed"** (§10.6).
5. **Never show ₹0 where the truth is "not recorded"** — Nirantara payments after FY2023-24, gold/silver in FY2021-22 and FY2022-23.
6. **Never write to the Kollur database.** Read-only credentials, enforced at the account level.
7. **Always show data currency.** Latest source data is 2026-07-26; a dashboard that looks live when it is seven weeks stale is misleading to a DC officer.

### 22.3 Immediate wins available before any backend work

Even with the static file, three corrections would make the current dashboard materially more honest:

1. Replace the 15 g/item weight assumption with the real per-metal weights in §7.11.
2. Remove the ₹12,000/item value chart, or blank it with an explanation.
3. Add booking counts to the special-seva list so the two zero-booking entries are visible.

---

## 23. Final Conclusion

### A. What can we generate TODAY

| Report | Source |
|---|---|
| Total revenue by FY (8 years) | `DailySevaNew` + archives |
| Revenue by month / week / day | `ReceiptDate` |
| **Seva Revenue Split — all 164 sevas** | `DailySevaNew` ⋈ `seva_seva` |
| Income by bucket (Seva / Donation / Prasada / Special) | `seva_sannidhi` |
| Donation revenue (₹20.55 Cr FY2025-26) | bucket `KN` |
| Saree donations and auction proceeds | `SareeDonation`, `SareeAuction` |
| Gold & silver — counts **and real weights, split by metal** | `HKanikeItems` ⋈ `HItemMaster` |
| Special / high-value sevas with real bookings | `seva_seva` ⋈ `DailySevaNew` |
| Cash vs digital share | `CardNo` / `BankName` (relabelled) |
| Cancellations | `BillCancled`, `ReceiptCanceldetails` |
| Revenue by counter and by cashier | `COUNTERNO`, `ModifiedBy` |
| Nirantara **bookings** and subscribers | `seva_sevakartaDetails` |

### B. What requires transformation

Multi-year revenue (UNION of 7 tables, excluding `…Old`) · cash share (inference from empty card fields) · Nirantara classification (inferred from subscription membership, since `ssv_period` is NULL everywhere) · special sevas (threshold + LEFT JOIN for bookings) · gold/silver (grams → kg, metal split via `ssg_Itemslno`) · FY normalisation (`20252026` → `2025-26`).

### C. What cannot currently be generated

| Report | Reason |
|---|---|
| Total expenditure, net surplus, spend by category, top line items | **No expense data anywhere** (§11) |
| DC Approved Funds — approved vs spent, sanction orders, utilisation proof | **No grant domain** |
| Ongoing Works | **No project domain** |
| Gold/silver **value** | `Rate`/`Amount` zero post-FY2015-16; no purity |
| **Nirantara Seva execution / fulfilment** | All flags zero, attendance table empty, issued count zero |
| Nirantara active vs cancelled | Status columns constant across all 6,659 rows |
| Nirantara revenue FY2024-25 onward | Payment recording stopped |
| Refund analysis | No refund concept exists |
| Payment-mode breakdown | No payment-mode column |

### D. What additional data is required

**Critical:** expense transactions (or structured annual register) · grant master + utilisation · Nirantara execution record · gold/silver valuation.
**Important:** payment mode · Nirantara payments post-FY2023-24 · subscription status maintenance · perpetual-seva flag · temple-code mapping.
**Optional:** devotee key · campaign tagging · hundi denomination detail · budget-vs-actual.

### E. Source of truth

| Concept | Source |
|---|---|
| Donations | `DailySevaNew.Amount` where `ssn_Type = 'KN'` (the `Donation` table is empty) |
| Sevas | `seva_seva.ssv_code` |
| Payments (daily) | `DailySevaNew` — the receipt *is* the payment |
| Payments (Nirantara) | `seva_sevakartaPayment` — **valid only to FY2023-24** |
| Revenue | **`DailySevaNew.Amount`** — never the detail table |
| Expenses | **None.** Interim: `trust_financials.annual_expenditure` (declared) |
| Refunds | **None.** Cancellation is the only reversal |
| **Nirantara execution** | **None. Booking data alone cannot prove execution.** |

### F. Next implementation phase

1. **Decide the expenditure and execution-capture questions (§22.1 Phase 0) — blocking.**
2. Database integration: read-only login, `temple_source_system_map`, 5 aggregate tables.
3. ETL with reconciliation against the control totals in §1.
4. Finance REST APIs carrying `dataAsOf` and `executionDataAvailable`.
5. React dashboard replacing the static iframe.
6. Charts (§13) · 7. Tables (§13) · 8. Filters (FY, date range, bucket, counter).
9. Export via existing `export_job_records` / `audit_export_events`.
10. Validation against the source database, and against `trust_financials`.

---

## The Most Important Question

| Requirement | Fully supported | Partially supported | Not supported | Missing data |
|---|:--:|:--:|:--:|---|
| **Finance Dashboard KPIs** | Total revenue, monthly trend, cash share, avg receipt, YoY | Income-register total (₹84.01 Cr is a PDF figure, not a DB aggregate) | Total expenditure, net surplus, DC-fund utilisation | Expense transactions; grant master |
| **Donation Reports** | Donation revenue by bucket/seva/FY/month; donor name & contact; cancellations; saree donations & auctions | GST on auctions (FY2023-24+ only) | Refunds; donation mode; campaign tagging; donor tax identity | Refund model; payment-mode column; campaign entity |
| **Seva Revenue Split** | **Fully supported** — seva type, booking count, revenue, avg ticket, rate card, period, bucket, all 164 sevas | — | Payment status (no credit model — N/A); refund status | Nothing required |
| **Nirantara Seva Tracking** | Bookings, subscribers, seva mix, booking dates, recurrence | Payments (**ends FY2023-24**); classification (inferred, `ssv_period` NULL) | **Execution / fulfilment; missed sevas; cancellations; active-vs-completed; corpus value** | **Execution record; status maintenance; post-FY2023-24 payments; `FDAMOUNT`** |
| **Expense Reports** | — | Declared annual total via `trust_financials` | **Everything else** — category split, line items, vendors, payroll, grants, works | **The entire expenditure domain** |
| **Revenue Trends** | FY, month, week, day; by seva, bucket, counter, cashier; YoY | FY2026-27 partial (to 26 Jul); seva history starts FY2019-20 | Pre-FY2019-20 seva revenue | Older archives not present |
| **Chart Reports** | Revenue trend; seva split bar; income donut; monthly bar; gold/silver weight & count; special sevas | Nirantara bookings (with caveat) | Expenditure charts; grant charts; gold/silver value; fulfilment charts | As above |
| **Tabular Reports** | All of the above, with totals, sorting, pagination (164 sevas), FY/date/bucket/counter filters, CSV export | Nirantara table (bookings only, caveated) | Expenditure tables; grant tables; works tables | As above |

### The answer in one paragraph

We have **eight financial years of high-quality, receipt-level income data** — 22 million rows totalling ₹488 Cr — from which total revenue, monthly trends, the complete seva revenue split across all 164 sevas, donation revenue, cash share, cancellations, and gold/silver donation weights can all be generated accurately today, several of them more accurately than the figures currently displayed. We have **no expenditure data at all**, and no government-grant or works data, so five of the twelve current widgets — the entire "where the money goes" and "DC approved funds" half of the dashboard — cannot be built from this source and must either be dropped or fed by a new capture process. On the explicit Nirantara Seva question: we can say with confidence **what is booked, by whom, and when**, but **booking information exists while actual seva execution cannot be independently verified from the available data** — every execution flag in the source system is unset, the attendance table is empty, and the one flag that is set is set on rituals scheduled for 2027.
