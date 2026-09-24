# KOLSOHAM_LOCAL — Database Analysis

**Analysis date:** 2026-09-16
**Method:** Read-only inspection via `sqlcmd` (Windows Authentication). No DDL or DML was executed — only `SELECT` against `INFORMATION_SCHEMA`, `sys.*` catalog views, and aggregate queries over user tables.

**Connection verified as:**

```
sqlcmd -S localhost -d KOLSOHAM_LOCAL -E -C -Q "SELECT DB_NAME(), SUSER_SNAME();"
-> KOLSOHAM_LOCAL / ZTSS\MiliSrivastava
```

> `sqlcmd` is not on the system `PATH`. It was invoked from its installed location:
> `C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\180\Tools\Binn\SQLCMD.EXE`

---

## 1. Database Overview

| Property | Value |
|---|---|
| Server | `ZTLW-18` (default instance, `localhost`) |
| Product | Microsoft SQL Server 2025 (RTM) 17.0.1000.7, Standard Developer Edition |
| Database | `KOLSOHAM_LOCAL` |
| Collation | `SQL_Latin1_General_CP1_CI_AS` |
| Compatibility level | **100 (SQL Server 2008)** |
| Recovery model | SIMPLE |
| State | ONLINE |
| Data file | `KolSoham.mdf` — **14,993 MB** |
| Log file | `KolSoham_log.ldf` — **10,668 MB** |
| Logical file names | `KolSoham` / `KolSoham_log` (do not match the database name — restored/renamed copy) |

### Object inventory

| Object type | Count |
|---|---|
| User tables | 167 |
| Views | 170 |
| Stored procedures | 129 |
| Table-valued functions | 1 (`dbo.ListDates`) |
| Scalar functions | 0 |
| Triggers | **0** |
| Primary keys | 45 |
| Foreign keys | **0** |
| Unique constraints/indexes | **0** |
| Check constraints | **0** |
| Default constraints | 716 |
| Non-PK indexes | **3** |
| Identity columns | 12 |

### Schemas

Only **`dbo`** holds objects (all 167 tables). The remaining schemas are the SQL Server built-ins (`sys`, `INFORMATION_SCHEMA`, `guest`, and the nine fixed `db_*` role schemas), all empty. There is no schema-level modularisation.

### What this database is

`KOLSOHAM` = **KOL**lur + **SOHAM**. `TempleMaster` row `TempleCode = 43` is **"Kolur Mookambika"** (Kollur Mookambika Temple, Karnataka). Every transactional row in the database carries `TempleCode = 43`.

It is the back-office system for temple operations: seva (ritual service) ticketing at counters, perpetual-seva subscriptions, offerings (hundi, gold/silver kanike, saree donation and auction), property/tenancy rent, hall booking, and a small double-entry accounting ledger.

**Scale of live data:**

| Financial year | Receipts | Total amount |
|---|---:|---:|
| 2025–2026 | 3,950,072 | ₹ 90,61,62,936 |
| 2026–2027 (partial, to 2026-07-26) | 1,415,755 | ₹ 38,61,44,664 |

Latest activity anywhere in the database is **2026-07-26** (`DailySevaNew.ReceiptDate` and `LogDetails.LoginDate`), roughly seven weeks before this analysis. 26 distinct users appear in the login log, which runs from 2018-03-16.

---

## 2. Complete Table List

Row counts are from `sys.partitions` (index_id 0/1), which is exact for these tables.

### Daily Seva — transaction core (hot + archives)

| Table | Rows | Size (MB) |
|---|---:|---:|
| `DailySevaNewDetailsOld` | 16,982,272 | 1,753.6 |
| `DailySevaNewOld` | 16,982,270 | 2,941.8 |
| `DailySevaNew` | 5,365,855 | 2,073.0 |
| `DailySevaNewDetails` | 5,365,855 | 847.0 |
| `DailySevaNew20232024` | 3,877,213 | 670.2 |
| `DailySevaNewDetails20232024` | 3,877,213 | 397.4 |
| `DailySevaNew20242025` | 3,809,026 | 655.9 |
| `DailySevaNewDetails20242025` | 3,809,026 | 390.6 |
| `DailySevaNew20222023` | 3,602,460 | 623.2 |
| `DailySevaNewDetails20222023` | 3,602,460 | 369.8 |
| `DailySevaNewDetails20192020` | 2,530,986 | 261.5 |
| `DailySevaNew20192020` | 2,530,984 | 435.1 |
| `DailySevaNew20212022` | 1,712,113 | 295.9 |
| `DailySevaNewDetails20212022` | 1,712,113 | 175.3 |
| `DailySevaNewDetails20202021` | 1,450,474 | 149.8 |
| `DailySevaNew20202021` | 1,450,474 | 247.6 |
| `DailySevaNewAllyear` | 0 | — |
| `DailySevaNewDetailsALLYEAR` | 0 | — |
| `DAILYSEVANEWMULTI` | 0 | — |

### Sashwatha Seva (perpetual/endowment seva)

| Table | Rows |
|---|---:|
| `seva_List_Fridaypooja` | 317,892 |
| `seva_List` | 15,091 |
| `seva_sevakartaDetails` | 6,659 |
| `seva_RWsevakartaDetails` | 6,659 |
| `ssk_seva_Familydetails` | 5,275 |
| `seva_sevakartaPaymentdet` | 5,247 |
| `seva_sevakartaPayment` | 5,219 |
| `seva_sevakarta` | 5,177 |
| `Sevaattendedlist`, `seva_ListMulti`, `seva_seva_PERREF`, `seva_sevegaluDailySpecialdarshan` | 0 |

### Seva catalogue and calendar (panchanga)

| Table | Rows | Table | Rows |
|---|---:|---|---:|
| `seva_seva` | 164 | `seva_nakshatra` | 28 |
| `SEVALISTMASTER` | 162 | `seva_pachanga_nakshatra` | 27 |
| `SET_Seva_seva` | 52 | `seva_tithi` | 16 |
| `PanchangaMaster` | 356 | `seva_masa_surya` | 13 |
| `seva_gotra` | 452 | `seva_masa` | 12 |
| `SpecialDayMaster` | 42 | `seva_masa_panchanga_surya` | 12 |
| `SpeOptionOnSevaCode` | 589 | `seva_month` | 12 |
| `Seva_DayWiseMulti` | 2,205 | `seva_month_sashwathseva` | 12 |
| `seva_period` | 8 | `seva_weekday` | 7 |
| `seva_sannidhi` | 4 | `seva_weeks` | 5 |
| `seva_paksha` | 2 | `Week` | 7 |
| `Timings` | 24 | `Numbers` | 8,000 |
| `seva_seva_sub`, `Seva_DayWise`, `seva_prasadamaster`, `seva_BhaktaNivasa` | 0 | | |

### Offerings — saree, kanike, hundi, tulabhara

| Table | Rows | Table | Rows |
|---|---:|---|---:|
| `SareeDonation` | 182,675 | `KanikeItemsMultiDetails` | 3,616 |
| `SareeAuction` | 147,747 | `KanikeItemsMulti` | 2,796 |
| `SareeAuctionDetails` | 147,689 | `HKanikeItemsPicDetails` | 2,227 |
| `sareetypemaster` | 4 | `HKanikeItems` | 2,225 |
| `SareeIssueTypeMaster` | 9 | `HKanikeItemGroup` | 7 |
| `SareeKanikeMaster` | 7 | `HItemMaster` | 45 |
| `SareeDonationOld` | 0 | `PurityMaster` | 9 |
| `ColorTypeMaster` | 18 | `HTulabharaMaster` | 24 |
| `ClothTypeMaster` | 2 | `HTulabhara`, `HTulabharaDetails` | 0 |
| `EvaluatorMaster` | 3 | `HundiCollection_Cash/_Coins/_GoldSilver/_Others` | 0 |

### Finance and accounting

| Table | Rows | Table | Rows |
|---|---:|---|---:|
| `FN_TRANSACTION` | 524,296 | `CASHTRAN` | 25 |
| `FN_MASTERMAIN` | 136 | `ChequeMaster` | 6 |
| `FN_ACCOUNTMASTER` | 22 | `CurrencyMaster` | 3 |
| `HeadwiseGroupTbl` | 51 | `DollarEuroINR` | 6 |
| `BudgetMainGroupMaster` | 16 | `KolCommissionDetails` | 68 |
| `BudgetDetails` | 12 | `OtherIncome` | 7 |
| `DonationTypeMaster` | 3 | `TaxMaster` | 1 |
| `FN_BANKMASTER`, `FN_TrialBalance`, `FN_VOUCHERPAYEMENT`, `FN_CashFlowTemp`, `CommissionDetails`, `Donation` | 0 | | |

### Property, tenancy, rent, accommodation

| Table | Rows |
|---|---:|
| `GenerateTenantMaster` | 4 |
| `BhakthaHomeReasons` | 11 |
| `BhakthaHome` | 2 |
| `RentCollectionMaster` | 1 |
| `TenantMaster`, `TenantPropertyDetails`, `TenantReceipt`, `TenantReceiptDetails`, `GenerateTenatRent`, `ShopRentMaster`, `LandRentMaster`, `PropertyStatusMaster`, `HallBookingDetails`, `HallBooking_Payment`, `ElectricalReading` | **all 0** |

### Party / address master data

| Table | Rows | Table | Rows |
|---|---:|---|---:|
| `NameMaster` | 5,164 | `CountryMaster` | 9 |
| `AddressMaster` | 5,164 | `NamePrefixMaster` | 17 |
| `CityMaster` | 266 | `CategoryMaster` | 2 |
| `StateMaster` | 44 | `PersonReference` | 0 |

### Security, configuration, operations

| Table | Rows | Table | Rows |
|---|---:|---|---:|
| `LogDetails` | 58,233 | `UserTable` | 19 |
| `CounterWisePermission` | 435 | `RolesDetails` | 28 |
| `Menu` | 62 | `Roles` | 4 |
| `CashierMaster` | 54 | `CounterMaster` | 3 |
| `TempleMaster` | 43 | `Modules` | 1 |
| `ReceiptCanceldetails` | 465 | `LookupDetails` | 17 |
| `DayEndProcess` | 200 | `DayEndProcessDetails` | 19 |
| `IdentityValue` | 10 | `AllotReceiptno` | 0 |
| `PrintDesign` | 80 | `Reportmaster` | 5 |
| `DEFAULTPRINTS` | 2 | `PACKING` | 11 |
| `KP_Mast` | 20 | `PreviousRecord` | 5 |
| `TransferDate` | 1 | `Housekeepingsetting` | 1 |
| `tmp_HundiCollectionNos` | 3 | `ValidationMaster` | 2 |
| `TypeMaster` | 9 | `PurposeMaster` | 21 |
| `Duplicateitemmaster` | 1 | `DuplicateTypeMaster` | 1 |
| `localDataBaseLicenseCheck`, `localDataBaseLicenseCheck1`, `HANDDEVICEMASTER` | 0 | | |

### Money order, communication, misc (all empty)

`MOSender`, `MOSenderDetails`, `BackUp_MOSender`, `AnnadhanaDetails`, `SMS_TemplateMaster`, `SMSSevaTemplate`, `TotalSMSCount` — 0 rows. `MoAccountTypeMaster` — 1 row.

---

## 3. Table Descriptions / Inferred Purpose

### 3.1 The Daily Seva engine

This is the operational heart of the system — the counter-side receipting for devotee-purchased ritual services.

- **`DailySevaNew`** — receipt header. One row per (receipt, seva) with devotee name/address, amount, `Nakshatra`/`Gotra`/`Raashi` (birth-star, lineage, moon-sign, needed to perform the ritual), payment metadata (`CardNo`, `BankName`, `RefNo`), counter number, printing flags, and bill-cancellation audit columns.
- **`DailySevaNewDetails`** — line items for a receipt: `PujaName`, `SevaCode`/`SubSevaCode`, `Qty`, `Amount`, `TotalAmount`.
- **`DailySevaNew<FY>` / `DailySevaNewDetails<FY>`** — per-financial-year archives, 2019–2020 through 2024–2025. **Schema-identical** to the live tables (45 and 14 columns respectively — a column-by-column diff found zero drift).
- **`DailySevaNewOld` / `DailySevaNewDetailsOld`** — see §10.1; these are a full-fidelity *duplicate* of all six archives combined.
- **`DAILYSEVANEWMULTI`, `DailySevaNewAllyear`, `DailySevaNewDetailsALLYEAR`** — empty; scaffolding for multi-seva receipts and a cross-year consolidated table that was never populated.

The live `DailySevaNew` currently holds **two** financial years (20252026 and 20262027), not one — so the archive table for 2025–2026 has not yet been cut.

### 3.2 Sashwatha Seva (perpetual seva subscriptions)

A devotee endows a seva to be performed on a recurring date — annually, on a given lunar month/star, every full moon, etc. — in perpetuity.

- **`seva_sevakarta`** — the subscriber (*sevakarta*). Thin: `ssk_code` plus references into `NameMaster`/`AddressMaster`.
- **`seva_sevakartaDetails`** (94 columns) — one subscribed seva per row, with a full recurrence specification expressed three ways in parallel: **Chandramana** (lunar: `ssk_CHANDRA_MASA/PAKSHA/NAKSHATRA/THITHI/WEEK/WEEK_DAY`), **Souramana** (solar: `ssk_SURYA_MASA/NAKSHATRA/WEEK/WEEK_DAY/DAY`), and **English/Gregorian** (`ssk_EnglishYear*`/`ssk_EnglishMonth*`). Flags `ssk_Chandra`/`ssk_Surya`/`ssk_English` select which calendar governs.
- **`seva_RWsevakartaDetails`** — an 86-column near-copy with the same 6,659 rows; the *renewal* mirror (`Renslno` in its PK).
- **`seva_List`** — the materialised schedule: which subscription is due on which `seva_Date`, with `seva_Prepared`, `Seve_closed`, and SMS/email reminder flags.
- **`seva_List_Fridaypooja`** (317,892 rows) — the same idea specialised to the Friday pooja cycle; the largest non-DailySeva table.
- **`seva_sevakartaPayment` / `…Paymentdet`** — receipts against subscriptions.
- **`ssk_seva_Familydetails`** — family members named in the sankalpa for the seva.

### 3.3 Seva catalogue and panchanga

- **`seva_seva`** (68 columns, 164 rows) — the seva master. Only ~20 columns are business data (`ssv_Name`, `ssv_Name_English`, `ssv_amount`, `ssv_period`, `ssv_sannidhi`, `ssv_SevaType`, `Accountcode`); the remaining ~48 are **receipt-printing layout configuration** — header/footer text, print start positions, font sizes, thermal-printer offsets, and five `nvarchar(max)` image columns (`HeaderPictureLeft/Right`, `PoojaPicture`, `SignaturePicture`, `BackGroundPicture`). The presentation layer of the printed receipt lives in the data model.
- **`seva_sannidhi`** — the four shrine/category buckets: `SEVAS (DS)`, `SPL SEVAS (SS)`, `KANIKE/DONATION (KN)`, `PRASADA (PS)`.
- **`seva_period`** — recurrence archetypes, named in Kannada with English flag codes: `SEVA_YEARLYONECE`, `SEVA_TWELVEMONTHLYONECE`, `SEVA_SASHWATHAPUJA`, `SEVA_PORNAMIPUJA` (full moon), `SEVA_AMAWAYAPUJA` (new moon), `SEVA_SANKASTIPUJA`, `SEVA_SHRASTIPUJA`, `SEVA_EVERYMASA`.
- **`seva_masa` / `seva_masa_surya` / `seva_nakshatra` / `seva_tithi` / `seva_paksha` / `seva_weekday` / `seva_weeks` / `PanchangaMaster`** — the Hindu-calendar dimension tables that the recurrence columns above resolve against.
- **`seva_gotra`** (452) — patrilineal lineages, selected per receipt.
- **`Numbers`** (8,000 rows) — a classic tally table, used for date/series generation.

### 3.4 Offerings

- **Saree** — `SareeDonation` (182,675) records donated sarees with donor, type, colour, cloth type, declared value, and a `BarCodeImage`. Donated sarees are later auctioned: `SareeAuction` (147,747) + `SareeAuctionDetails`, which carry `CGSTper`/`SGSTper`/`CGSTAmt`/`SGSTAmt`/`TaxableAmount` — GST is charged on auction sales but not on donations.
- **Kanike (gold/silver offerings)** — `HKanikeItems` with purity, weight (`Qty decimal(18,3)`, `InKgs`), rate, barcode picture, and reconciliation fields against the physical register (`Asperregister`, `QTYAsperregister`, `OldReceiptNo`, `OldSLNO`). `HKanikeItemsPicDetails` (164 MB) stores per-item photographs. `KanikeItemsMulti`/`…Details` is a multi-item receipt variant.
- **Hundi (donation box) counting** — `HundiCollection_Cash` denominates the count (`QtyRupee1` … `QtyRupee1000`, `bigint`), with parallel tables for `_Coins`, `_GoldSilver`, `_Others`, and `EvaluatorMaster` for the counting witnesses. All currently empty.
- **Tulabhara** — weighing a devotee against a commodity; `HTulabharaMaster` holds the commodity rate card. Transaction tables empty.

### 3.5 Finance

A compact double-entry ledger, not a general accounting package.

- **`FN_MASTERMAIN`** — chart-of-accounts group hierarchy (`ParentGrp` self-reference, `MasterType`).
- **`FN_ACCOUNTMASTER`** — ledger accounts with opening balance, period debit/credit, closing balance, `Sannidhi`, `BudgetCode`.
- **`FN_TRANSACTION`** (524,296 rows) — journal lines. `TrAccCodeDRCR` + `TransactionType` carry the debit/credit side; `ReceiptNo` and `ssv_code` link entries back to the seva receipts that generated them.
- **`BudgetDetails` / `BudgetMainGroupMaster` / `HeadwiseGroupTbl`** — budget heads and report grouping.

### 3.6 Security and operations

- **`UserTable`** — 19 users. `IsAdmin`, `IsSubAdmin`, `IsCashier`, `RolesID`, `CounterNo`, plus biometric `Fingerprint1`/`Fingerprint2` and a `Photo` (all `nvarchar(max)`).
- **`Roles`** (Manager, Data Entry Operator, USERCANCEL, USERREPRINT) + **`RolesDetails`** (named permission flags).
- **`Menu`** — menu-item permissions as **26 hard-coded boolean columns** (`ADMIN`, `SUBADMIN`, `SMT`, `GSJ`, `CC1`…`CC13`, `RC`, `RC1`, `SA`, `FDP`, `UG`) rather than rows. Adding a counter or role requires a schema change.
- **`CounterMaster`** (3 counters) + **`CounterWisePermission`** (435) + **`CashierMaster`**.
- **`LogDetails`** — login/logout audit, 58,233 rows, 2018-03-16 → 2026-07-26.
- **`IdentityValue`** — see §10.3. A 59-column, one-row-per-financial-year **application-managed sequence table**.
- **`DayEndProcess` / `DayEndProcessDetails` / `TransferDate`** — day-end close and upload-to-central-server sync. Last meaningful activity **2016**; `TransferFlag` is 0 on every row and `TransferDate` is NULL throughout — this mechanism is dormant.

---

## 4. Columns for Important Tables

### `DailySevaNew` (45 columns) — receipt header

| # | Column | Type | Null | Default |
|---:|---|---|---|---|
| 1 | ReceiptNo | varchar(12) | NULL | |
| 2 | ReceiptDate | smalldatetime | NOT NULL | |
| 3 | PersonName | nvarchar(400) | NOT NULL | |
| 4 | Address | nvarchar(600) | NULL | |
| 5 | SevaType | char(4) | NULL | |
| 6 | RefNo | varchar(25) | NULL | |
| 7 | RefDate | smalldatetime | NULL | |
| 8 | Amount | money | NULL | |
| 9 | AmountInWords | nvarchar(600) | NULL | |
| 10 | MobileNo | varchar(50) | NULL | |
| 11 | Email | varchar(50) | NULL | |
| 12 | Finyear | int | NOT NULL | |
| 13 | ReportFinYear | varchar(50) | NULL | |
| 14 | BillCancled | bit | NOT NULL | `0` |
| 15–17 | Nakshatra / Gotra / Raashi | smallint | NULL | |
| 18 | SevaDay | smalldatetime | NULL | |
| 19 | Remarks | nvarchar(100) | NULL | |
| 20 | ModifiedBy | char(10) | NOT NULL | |
| 21 | ModifiedDate | smalldatetime | NOT NULL | `getdate()` |
| 22 | Deleteflag | bit | NOT NULL | `0` |
| 23–24 | BillCancledBy / BillCancledReason | char(10) / nvarchar(400) | NULL | |
| 25 | Receiptslno | int | NOT NULL | `0` |
| 26 | TempleCode | int | NOT NULL | `0` |
| 27 | SevaCode | smallint | NOT NULL | `0` |
| 28 | DayWiseSevacode | int | NULL | |
| 29 | ssv_code_sub | smallint | NULL | |
| 30 | COUNTERNO | smallint | NULL | |
| 31–33 | MultiReceiptNo / MultiRecpNo / MultiReceipt | varchar(50) / int / bit | NOT NULL | |
| 34–35 | Printed / RePrinted | bit | NULL | `0` |
| 36–38 | CardNo / BankName / RemarksBanking | varchar(50) | NULL | |
| 39 | SendPrasada | bit | NULL | `0` |
| 40–41 | Mukantara / MukantaraId | bit / int | NULL | `0` |
| 42–43 | IsSet / Set_ssvcode | bit / int | NULL | `0` |
| 44 | Billcanceldate | smalldatetime | NULL | `getdate()` |
| 45 | RePrintdate | smalldatetime | NULL | |

**PK:** `(Finyear, TempleCode, SevaCode, MultiRecpNo, ModifiedBy)` — clustered.
**Indexes:** `idx_ReceiptDate` on `ReceiptDate`; `inx_Modifieddate` on `ModifiedDate`.

### `DailySevaNewDetails` (14 columns) — receipt lines

`ReceiptNo varchar(12) NOT NULL`, `slno smallint NOT NULL`, `PujaName nvarchar(400)`, `SevaCode smallint NOT NULL`, `SubSevaCode smallint`, `Qty smallint NOT NULL`, `Amount money`, `TotalAmount money`, `Finyear int NOT NULL`, `TempleCode int NOT NULL DEFAULT 0`, `Deleteflag bit NOT NULL DEFAULT 0`, `MultiReceiptNo varchar(50)`, `MultiRecpNo int NOT NULL`, `ModifiedBy char(10) NOT NULL`.

**PK:** `(SevaCode, Finyear, TempleCode, MultiRecpNo, ModifiedBy)`.

### `seva_seva` (68 columns) — seva master

Business columns: `ssv_code smallint`, `ssv_Name nvarchar(1000)`, `ssv_Name_English nvarchar(400)`, `ssv_Flag smallint`, `ssv_SevaType char(1)`, `ssv_period int`, `ssv_sannidhi int`, `ssv_amount money`, `ssv_NoOfTimes int`, `ssv_IsSet bit`, `ssv_setNo int`, `TempleCode int`, `Accountcode varchar(6)`, `IsNotActive bit`, `NoOfSevasPerDay int`, `NoOfSevasPerYear int`, `Deleteflag bit`, `ssv_code_sub smallint`, `Device_seva_code smallint`, `Barcodescan bit`.

Presentation columns (~48): `Header1/2`, `Footer1/2/3`, `OrderNo`, `PrintType`, `PrintStartPoistion` *(sic)*, `PrintStartPositionTempleName/Address1/OrderNo/Email`, `PrintPOSThermalTemplename/Address1/Address2/OrderNo`, `PrintTempleNameFont`, `PrintPreview`, `PreformatPrint`, `EnglishReceiptPrint`, `ColorPrint`, `PrintLandScape`, `NoOfPrints`, `Reportcodefirstcopy/secondcopy`, `SignatureName`, and five `nvarchar(max)` picture columns with their width/height pairs.

**Clustered index:** `IX_Sannidi` on `ssv_sannidhi` (non-unique, and **not** a primary key — `seva_seva` has no PK).

### `seva_sevakartaDetails` (94 columns) — perpetual-seva subscription

Identity/link: `ssk_code varchar(20)`, `ssk_codeDetails varchar(20)`, `ssk_SevaCode int`, `ssk_Date smalldatetime`, `ssk_name_pujaName nvarchar(1000)`, `Finyear int`, `NoOfSeva`, `NoOfSevaIssue`, `Remarks nvarchar(2000)`.
Calendar selector: `ssk_Chandra`, `ssk_Surya`, `ssk_English` (bit).
Lunar: `ssk_CHANDRA_MASA`, `_PAKSHA`, `_NAKSHATRA`, `_THITHI`, `_WEEK`, `_WEEK_DAY`.
Solar: `ssk_SURYA_MASA`, `_NAKSHATRA`, `_WEEK`, `_WEEK_DAY`, `_DAY`.
Gregorian: `ssk_EnglishWeeklyDay`, `ssk_EnglishYearKramanka/Week/Month/Day`, `ssk_EnglishMonthKramanka/Week`, `ssk_EnglishYearFlag`, `ssk_EnglishMonthFlag`.

**PK:** `(ssk_code, ssk_codeDetails)`.

### `UserTable` (22 columns)

`UserNo int IDENTITY`, `UserId char(10)`, `UserName char(25)`, **`UserPassword char(10)`**, `LoggedOn bit`, `LoginTime smalldatetime DEFAULT getdate()`, `ModuleID smallint`, `Deleteflag bit`, `FinYear char(10)`, `IsAdmin bit`, `IsSubAdmin bit`, `CashierCode nvarchar(100)`, `IsCashier bit`, `RolesID smallint`, `CounterNo int`, `IsActive bit DEFAULT 1`, `Fingerprint1/2 nvarchar(max)`, `IdProof int`, `IdproofNo varchar(50)`, `Photo nvarchar(max)`, `Mobileno varchar(12)`.

**PK:** `(UserId, CashierCode)`.

### `FN_TRANSACTION` (30 columns) — journal

`TrCode varchar(12)`, `Trdate datetime`, `TrAccountSubMain int`, `TrAccountSub int`, `TrAccCodeDRCR varchar(50)`, `TrAccName varchar(50)`, `TrPayMode varchar(50)`, `TrAccountClosingBal money`, `TrAmount money NOT NULL`, `TrType bit`, `TransactionType char(1)`, `TrAccountCurrClosingBal money`, `TrCheNo_DDNo varchar(50)`, `TrDescription nvarchar(600)`, `TrPaid_RecAccName nvarchar(300)`, `BankName`, `BanAccBankAdd1/2/3`, `BanAccCity`, `Finyear int`, `DeleteFlag bit`, `Referenceno varchar(20)`, `TrCancled bit`, `slno int IDENTITY`, `TempleCode int`, `ReceiptNo varchar(12)`, `ssv_code int`, `BudgetMainGroup int`, `BudgetCode int`.

**PK:** `(TrCode, TrAccCodeDRCR, Finyear, TempleCode)`.

### `NameMaster` (8 columns) and `AddressMaster` (31 columns)

`NameMaster`: `Namecode int` **PK**, `Prefix int`, `Name nvarchar(1000)`, `MobileNo varchar(20)`, `Email varchar(200)`, `Modifiedby char(10)`, `Modifieddate smalldatetime`, `Deleteflag bit`.

`AddressMaster`: `Code int` **PK**, `FirstName`, `LastName`, `Address`, plus **`Address1`…`Address5`** added later, `City`/`State`/`Country` as free-text `varchar(50)` (*not* references to `CityMaster`/`StateMaster`/`CountryMaster`), `Zip`, `Landline`, `MobileNo`, `MobileNo2`, `Email varchar(25)`, `District`, `CategoryCode`/`SubCategoryCode`/`SubSubCategoryCode`, `PrintCount`, `PrintedBy`, `LastPrintedDate varchar(50)`, `AddrBook bit`, `ModifiedBy char(10) NOT NULL DEFAULT getdate()` (see §11.1).

### `SareeDonation` (31 columns) — representative offering table

`ReceiptNo varchar(12)` **PK**, `ReceiptDate`, `DonorName nvarchar(1000)`, `SareeKanike money`, `SareeTypeCode int`, `ColorTypeCode int`, `ClothTypeCode int`, `SareeValueDonor money`, `SareeReceiptCost varchar(50)`, `billcancled bit`, `Finyear int`, `slno int IDENTITY`, `BarCodeImage image`, `TempleCode int`, `KanikeType smallint`, `PoojaDate`, `CardNo`/`BankName`/`RemarksBanking`, `TranType smallint`, `RefNo`/`RefDate`, `COUNTERNO smallint`, `BlousePiece bit DEFAULT 1`, audit columns.

---

## 5. Primary Keys

45 of 167 tables (27%) have a primary key. All 45 are clustered.

| Table | Primary key columns |
|---|---|
| AddressMaster | Code |
| AllotReceiptno | Userid, Finyear |
| AnnadhanaDetails | AnnadhanaReceiptNo, Finyear |
| DailySevaNew *(and `…20192020` … `…20242025`, `…Allyear`, `…Old`)* | Finyear, TempleCode, SevaCode, MultiRecpNo, ModifiedBy |
| DailySevaNewDetails *(and all 8 variants)* | SevaCode, Finyear, TempleCode, MultiRecpNo, ModifiedBy |
| DAILYSEVANEWMULTI | ReceiptNo, SevaCode, Finyear, TempleCode |
| FN_TRANSACTION | TrCode, TrAccCodeDRCR, Finyear, TempleCode |
| KolCommissionDetails | Seva_Code |
| KP_Mast | KP_ID |
| NameMaster | Namecode |
| ReceiptCanceldetails | Slno |
| SareeAuction | AuctionNo, Finyear, TempleCode |
| SareeAuctionDetails | AuctionNo, ReceiptNo, Finyear |
| SareeDonation | ReceiptNo |
| seva_List | ssk_code, ssk_codeDetails, seva_Date |
| seva_List_Fridaypooja | ssk_codeDetails, ssk_SevaCode, seva_Date |
| seva_RWsevakartaDetails | ssk_code, ssk_codeDetails, Renslno |
| seva_seva_PERREF | ssv_code |
| seva_sevakarta | ssk_code |
| seva_sevakartaDetails | ssk_code, ssk_codeDetails |
| seva_sevakartaPayment | ReceiptNo, Finyear |
| seva_sevakartaPaymentdet | ReceiptNo, ssk_code, ssk_codeDetails, ssk_PaymentType, ssk_RefNo, ssk_RefDate, Finyear |
| seva_sevegaluDailySpecialdarshan | ssg_receiptno, ssg_seva |
| Sevaattendedlist | ssk_codeDetails, seva_Date |
| SMS_TemplateMaster | Slno |
| SMSSevaTemplate | Sevacode |
| TaxMaster | Slno |
| TenantReceipt | ReceiptNo, Finyear |
| UserTable | UserId, CashierCode |

**Notably missing a PK:** `seva_seva` (the seva master), `TempleMaster`, `FN_ACCOUNTMASTER`, `FN_MASTERMAIN`, `LogDetails`, `HKanikeItems`, `Roles`, `RolesDetails`, `Menu`, `CounterMaster`, `CashierMaster`, `IdentityValue`, `CityMaster`/`StateMaster`/`CountryMaster`, and all tenancy/hundi/hall tables.

**121 of 167 tables are heaps** (no clustered index at all). None of the large tables is a heap — the largest heap is `LogDetails` at 58,233 rows.

### Identity columns (12)

| Table | Column | Type | Last value |
|---|---|---|---:|
| FN_TRANSACTION | slno | int | 543,783 |
| SareeDonation | slno | int | 199,526 |
| UserTable | UserNo | int | 95 |
| ReceiptCanceldetails | Slno | int | 468 |
| TempleMaster | Slno | int | 44 |
| LookupDetails | LookupDetailsCode | smallint | 17 |
| Roles | RolesID | smallint | 4 |
| DollarEuroINR | SlNo | int | 2 |
| Modules | ModuleID | smallint | 1 |
| GenerateTenatRent | Slno | int | (unused) |
| MOSender | recordslno | int | (unused) |
| SareeDonationOld | slno | int | (unused) |

Note: `FN_TRANSACTION.slno` last value (543,783) exceeds the row count (524,296) — roughly 19,500 identity values were consumed by rolled-back or deleted rows.

---

## 6. Foreign Keys and Relationships

> **There are zero foreign key constraints in this database.** There are also zero unique constraints, zero unique indexes, and zero check constraints.

Every relationship below is **implicit** — enforced only by application code and stored procedures, and discoverable only from column naming and join patterns in the 170 views.

### Verified integrity

Despite the absence of constraints, spot-checks found the data internally consistent:

| Check | Result |
|---|---|
| `DailySevaNew.SevaCode` values absent from `seva_seva.ssv_code` | **0** orphans (0 distinct bad codes) |
| `DailySevaNewDetails` rows with no matching header on `(ReceiptNo, Finyear, TempleCode)` | **0** orphans |
| `seva_sevakarta.ssk_Namecode` not in `NameMaster` | **0** of 5,177 |
| `seva_sevakarta.ssk_Addresscode` not in `AddressMaster` | **0** of 5,177 |

The application has kept referential integrity clean. The risk is structural, not (currently) realised.

### Implicit relationship map

**Daily seva**

```
seva_seva.ssv_code             1 --> *  DailySevaNew.SevaCode
seva_seva.ssv_sannidhi         * --> 1  seva_sannidhi.ssn_code
seva_seva.ssv_period           * --> 1  seva_period.spr_code
seva_seva.Accountcode          * --> 1  FN_ACCOUNTMASTER.AccountCode
DailySevaNew                   1 --> *  DailySevaNewDetails
        on (ReceiptNo | MultiRecpNo, Finyear, TempleCode)
DailySevaNew.Gotra             * --> 1  seva_gotra
DailySevaNew.Nakshatra         * --> 1  seva_nakshatra
DailySevaNew.COUNTERNO         * --> 1  CounterMaster.CounterID
DailySevaNew.ModifiedBy        * --> 1  UserTable.UserId
DailySevaNew.TempleCode        * --> 1  TempleMaster.TempleCode
DailySevaNew.ReceiptNo         * --> *  FN_TRANSACTION.ReceiptNo   (posting link)
```

**Sashwatha seva**

```
seva_sevakarta.ssk_code        1 --> *  seva_sevakartaDetails.ssk_code
seva_sevakarta.ssk_Namecode    * --> 1  NameMaster.Namecode
seva_sevakarta.ssk_Addresscode * --> 1  AddressMaster.Code
seva_sevakartaDetails          1 --> *  seva_List    on (ssk_code, ssk_codeDetails)
seva_sevakartaDetails          1 --> *  ssk_seva_Familydetails
seva_sevakartaDetails          1 --> 1  seva_RWsevakartaDetails  (renewal mirror, + Renslno)
seva_sevakartaPayment          1 --> *  seva_sevakartaPaymentdet on (ReceiptNo, Finyear)
seva_sevakartaDetails.*_MASA      * --> 1  seva_masa / seva_masa_surya
seva_sevakartaDetails.*_NAKSHATRA * --> 1  seva_nakshatra
seva_sevakartaDetails.*_THITHI    * --> 1  seva_tithi
seva_sevakartaDetails.*_PAKSHA    * --> 1  seva_paksha
seva_List                      1 --> *  Sevaattendedlist  on (ssk_codeDetails, seva_Date)
```

**Offerings**

```
SareeDonation.SareeTypeCode    * --> 1  sareetypemaster
SareeDonation.ColorTypeCode    * --> 1  ColorTypeMaster
SareeDonation.ClothTypeCode    * --> 1  ClothTypeMaster
SareeAuction                   1 --> *  SareeAuctionDetails  on (AuctionNo, Finyear)
SareeAuctionDetails.ReceiptNo  * --> 1  SareeDonation.ReceiptNo   (lot -> donated saree)
SareeAuction.SareeIssueTypeCode  * --> 1  SareeIssueTypeMaster
HKanikeItems                   1 --> *  HKanikeItemsPicDetails   on ssg_receiptno
HKanikeItems.Purity            * --> 1  PurityMaster
KanikeItemsMulti               1 --> *  KanikeItemsMultiDetails
HundiCollection_*.EvaluatorName  * --> 1  EvaluatorMaster
HTulabhara                     1 --> *  HTulabharaDetails --> 1 HTulabharaMaster
```

**Finance**

```
FN_MASTERMAIN.ParentGrp        * --> 1  FN_MASTERMAIN.Code   (self-referencing hierarchy)
FN_ACCOUNTMASTER.AccountMain/SubMain/Sub  * --> 1  FN_MASTERMAIN.Code
FN_TRANSACTION.TrAccCodeDRCR   * --> 1  FN_ACCOUNTMASTER.AccountCode
FN_TRANSACTION.BudgetCode      * --> 1  BudgetDetails.BudgetCode
BudgetDetails.BudgetMainGroup  * --> 1  BudgetMainGroupMaster
```

**Security**

```
UserTable.RolesID              * --> 1  Roles.RolesID  --> * RolesDetails
UserTable.ModuleID             * --> 1  Modules.ModuleID
UserTable.CounterNo            * --> 1  CounterMaster.CounterID
UserTable.CashierCode          * --> 1  CashierMaster.CashierCode
LogDetails.UserId              * --> 1  UserTable.UserId
CounterWisePermission          * --> 1  CounterMaster
Menu.ModuleId                  * --> 1  Modules.ModuleID
```

### ER-style narrative

Everything is scoped by two pervasive quasi-keys that appear in nearly every table but are never constrained:

- **`TempleCode`** — multi-tenant discriminator. `TempleMaster` lists 43 temples, but 100% of transactional rows are `TempleCode = 43`. The schema is built for a temple *group*; this instance runs one temple.
- **`Finyear`** — an `int` in the form `YYYYYYYY` (e.g. `20252026`). It participates in most composite primary keys and is the partitioning axis for the whole archive strategy.

The central flow: a devotee arrives at a **counter** (`CounterMaster`), a **cashier** (`UserTable` / `CashierMaster`) selects one or more **sevas** (`seva_seva`) from a **shrine bucket** (`seva_sannidhi`), and a **receipt** is written as one `DailySevaNew` header plus `DailySevaNewDetails` lines, stamped with the devotee's `Gotra` / `Nakshatra` / `Raashi` so the ritual can be performed under their name. The receipt prints using layout metadata carried on the seva row itself. Money is posted to `FN_TRANSACTION` against the seva's `Accountcode`. At day end, `DayEndProcess` was designed to roll the day up and push it to a central server.

In parallel, **`seva_sevakarta`** subscribers endow recurring sevas. `seva_sevakartaDetails` stores each subscription's recurrence in whichever of the three calendars the devotee chose; a scheduler materialises the due dates into `seva_List` (and `seva_List_Fridaypooja`), which the temple works through each day, marking `seva_Prepared` and `Seve_closed` and sending SMS/email reminders.

Physical offerings run on their own tracks — saree donation to auction (with GST), gold/silver kanike with photographs and register reconciliation, and hundi box counting by denomination.

`NameMaster` + `AddressMaster` form a shared party master used by the subscription module, but the daily-seva receipts store devotee name and address as **denormalised free text** on the receipt row instead.

---

## 7. Indexes

This is the most severe technical finding in the database.

**Total non-PK indexes: 3.**

| Table | Index | Type | Key columns |
|---|---|---|---|
| `DailySevaNew` | `idx_ReceiptDate` | NONCLUSTERED | `ReceiptDate` |
| `DailySevaNew` | `inx_Modifieddate` | NONCLUSTERED | `ModifiedDate` |
| `seva_seva` | `IX_Sannidi` | **CLUSTERED**, non-unique | `ssv_sannidhi` |

Plus the 45 clustered primary keys listed in §5. Neither of the two `DailySevaNew` indexes has an `INCLUDE` list, so queries that use them still incur key lookups.

### Consequences

- **`DailySevaNewOld` (16.98 M rows, 2.9 GB) and `DailySevaNewDetailsOld` (16.98 M rows, 1.75 GB)** have only their clustered PK, whose leading column is `Finyear`. Any query filtering by receipt number, date, or devotee name scans gigabytes.
- **The six per-year archive pairs** (each 1.4 M–3.9 M rows) have **no date index at all** — only the live `DailySevaNew` has `idx_ReceiptDate`. Historical date-range reporting against any prior year is a full table scan.
- **`DailySevaNewDetails` (5.37 M rows, 847 MB)** has a clustered PK leading with `SevaCode`. The natural join from header to detail is on `ReceiptNo`/`MultiRecpNo`, which is the *fourth* PK column — so header-to-detail joins cannot seek efficiently.
- **`seva_List_Fridaypooja` (317,892 rows)** is keyed `(ssk_codeDetails, ssk_SevaCode, seva_Date)`. Date-range scheduling queries — the table's primary use — put `seva_Date` last and cannot seek.
- **`FN_TRANSACTION` (524,296 rows)** has no index on `Trdate`, `ReceiptNo`, or `TrAccCodeDRCR`. Every ledger and trial-balance report scans 178 MB.
- The **`ModifiedBy char(10)` column sits inside the primary key** of all 17 DailySeva tables. A ten-byte user-id string is carried through every index page and is the fifth key column — this both widens the index and makes the PK semantically wrong (see §10.7).

---

## 8. Views, Stored Procedures and Functions

### 8.1 Views — 170

Two-thirds were written in 2019 (created 2019-01-28, bulk-modified 2019-10-03), with a later wave on 2022-07-06 for the Sashwatha Seva module.

| Family | Count | Examples |
|---|---:|---|
| Sashwatha Seva reporting | ~22 | `VIEW_SASHWATHASEVA`, `…ALL`, `…IDCARD`, `…INTIMrpt`, `…NoPay`, `…search`, `VIEW_RptSASHWATHASEVA`, `VIEW_ReceiptShashwathaseva59` |
| Daily-seva consolidation | ~15 | `VIEW_CONSOLIDDAILYSEVA`, `…ALL`, `…_COUNTERWISE`, `…NOUSER`, `…HEADWISEREPORTNEW`, `…Cloud` |
| Daily-seva detail/receipt | ~12 | `VIEW_DAILYSEVANEW`, `…1`, `…1Multi`, `…43`, `…clerk`, `…commdet43`, `VIEW_DAILYSEVAMULTISEARCH` |
| Finance | ~10 | `Vw_Income`, `Vw_Expense`, `Vw_Openbal`, `Vw_Closbal`, `vw_rptIncomeExpense`, `Vw_RP_report`, `View_Voucher` |
| Tenant / property / rent | ~8 | `VIEW_TenantMaster`, `VIEW_TENANTRECEIPTDETAILS`, `VIEW_SHOPRENTBALANCE` |
| Saree donation/auction | ~7 | `View_SareeAuction`, `View_SareeAuctionDetailsReport`, `VIEW_SAREEAUCTIONFLAGREPORT`, `view_sareedonationcount` |
| Bhandara (feeding) statements | ~6 | `Vw_BhandaraStatement`, `…1`, `…temp1`, `view_BhandaraReport` |
| Hundi | ~5 | `View_HundiCollection_Cash/_Coins/_GoldSilver/_Others`, `VIEW_HundiCollectionSearch` |
| Money order | ~5 | `VIEW_MOSENDER`, `…SEARCH`, `…CONCATENATE`, `VIEW_MOADVANCEAMOUNTSEARCH`, `VIEW_MOGROUP` |
| Clerk collection | ~5 | `Vw_clerkCollectionrpt43`, `Vw_KClerkcollectionrpt`, `…1` |
| Master-data lookups | ~15 | `View_CityMaster`, `View_StateMaster`, `View_CountryMaster`, `View_PrefixMaster`, `View_PurityMaster` (+ `_Gold` / `_Silver`) |

**A 2025-12-09 refactor wave** created 11 views suffixed `Old` in a single six-minute window (11:56–12:01), each pairing with a live view: `VIEW_DAILYSEVANEW43Old`, `VIEW_DAILYSEVANEWclerkOld`, `VIEW_DAILYSEVANEWcommdet43Old`, `VIEW_DATEWISECOLLECTIONOld`, `VIEW_DATEWISESEVATYPEOld`, `Vw_BhandaraStatementOld`, `Vw_BhandaraStatement1Old`, `Vw_BhandaraStatementtemp1Old`, `Vw_clerkCollectionrpt43Old`, `Vw_KClerkcollectionrptOld`, `Vw_KClerkcollectionrpt1Old`, plus a new `VIEW_WithoutSannidhiDAILYSD`. These are snapshots of the pre-change definitions, retained as rollback copies.

### 8.2 Stored procedures — 129

Naming is inconsistent across three generations: `sp_*` (majority), `SP_*`, `PROC_FN_*` (finance), and `Pro_*` (one).

| Group | Count | Notes |
|---|---:|---|
| Daily seva write path | 12 | `sp_KolSevaDailySevaNew` / `…New1` / `…NewSetMuk` each take **65 parameters**; `sp_KOLSevaDailySevaNewMultiDet` takes 44 |
| Sashwatha seva | 10 | `sp_Seva_SevakartaDet` takes **80 parameters** — the widest procedure in the database |
| Finance | 19 | `PROC_FN_*`, incl. four near-identical `PROC_FN_AccountTransfer_Cr1/Cr2/Cr3/Cr4` (22 params each) alongside `_Dr` |
| Hundi | 4 | `sp_HundiCollection_Cash` (36 params), `_Coins`, `_GoldSilver`, `_Others` |
| Saree | 7 | Includes `sp_SareeAuctionOLD` and `sp_SareeAuctionDetailsOLD` kept beside the live versions |
| Tenant / property | 6 | |
| Master-data CRUD | ~25 | `sp_PUR_*` (upsert) paired with `sp_Del*` (delete): City, State, Country, Prefix |
| Receipt numbering | 7 | `sp_AllotReceiptno`, `SP_KOlAllotReceiptCUR`, `sp_KOLMultiReceiptNo`, `sp_MultiReceiptNo`, `sp_KolMultiRecptDS`, `sp_KolMultiRecptDSNew`, `SP_IdentityValue` |
| Security | 4 | `sp_UserCreate`, `sp_UserMaintenance`, `SP_InsertRoleDetails`, `sp_InsertMenuPermissionDetails` |

**Most recently modified procedures (2026-02-24):** `sp_KolSevaDailySevaNew1` and `sp_KolMultiRecptDSNew` — the only objects changed in 2026, indicating the daily-seva write path and the receipt-number allocator were the last things touched.

`sp_CursorSevaprepare` is explicitly cursor-based, as its name states.

### 8.3 Functions — 1

`dbo.ListDates(@StartDate varchar(15), @EndDate varchar(15))` — a multi-statement TVF returning a `datetime` table of dates in a range. It guards with `ISDATE()` and silently returns an empty set on bad input rather than raising. **Both parameters are `varchar(15)`, not `date`** — date ranges are passed around this database as strings.

There are **no scalar functions and no inline TVFs**.

### 8.4 Triggers — 0

No DML or DDL triggers exist anywhere. All audit-column maintenance (`ModifiedBy`, `ModifiedDate`, `Deleteflag`) is done by application code or the stored procedures.

---

## 9. Business Modules Discovered

| # | Module | Status | Key tables |
|---|---|---|---|
| 1 | **Daily Seva ticketing** | **Live, primary** | `DailySevaNew(+Details)` and 8 archive pairs, `seva_seva`, `seva_sannidhi`, `SEVALISTMASTER`, `SET_Seva_seva` |
| 2 | **Sashwatha Seva (perpetual seva)** | **Live** | `seva_sevakarta`, `seva_sevakartaDetails`, `seva_RWsevakartaDetails`, `seva_List`, `seva_List_Fridaypooja`, `seva_sevakartaPayment(+det)`, `ssk_seva_Familydetails` |
| 3 | **Panchanga / Hindu calendar** | **Live (reference)** | `seva_masa`, `seva_masa_surya`, `seva_nakshatra`, `seva_tithi`, `seva_paksha`, `seva_weekday`, `seva_weeks`, `seva_month`, `PanchangaMaster`, `seva_gotra`, `SpecialDayMaster` |
| 4 | **Saree donation & auction** | **Live** | `SareeDonation`, `SareeAuction(+Details)`, `sareetypemaster`, `ColorTypeMaster`, `ClothTypeMaster`, `SareeIssueTypeMaster` |
| 5 | **Kanike (gold/silver offerings)** | **Live** | `HKanikeItems`, `HKanikeItemsPicDetails`, `HKanikeItemGroup`, `HItemMaster`, `KanikeItemsMulti(+Details)`, `PurityMaster`, `EvaluatorMaster` |
| 6 | **Accounting / ledger** | **Live** | `FN_TRANSACTION`, `FN_ACCOUNTMASTER`, `FN_MASTERMAIN`, `HeadwiseGroupTbl`, `CASHTRAN` |
| 7 | **Budgeting** | Partly used | `BudgetDetails` (12), `BudgetMainGroupMaster` (16) |
| 8 | **Security / RBAC / counters** | **Live** | `UserTable`, `Roles`, `RolesDetails`, `Menu`, `Modules`, `CounterMaster`, `CounterWisePermission`, `CashierMaster`, `LogDetails` |
| 9 | **Receipt numbering** | **Live** | `IdentityValue`, `AllotReceiptno`, `Numbers`, `tmp_HundiCollectionNos` |
| 10 | **Receipt/report printing** | **Live** | `PrintDesign` (80), `DEFAULTPRINTS`, `Reportmaster`, `PACKING`, plus ~48 layout columns on `seva_seva` |
| 11 | **Party / address master** | **Live** | `NameMaster`, `AddressMaster`, `CityMaster`, `StateMaster`, `CountryMaster`, `NamePrefixMaster`, `CategoryMaster` |
| 12 | **Commission** | Partly used | `KolCommissionDetails` (68), `CommissionDetails` (0) |
| 13 | **Hundi (donation box) counting** | **Built, never used** | `HundiCollection_Cash/_Coins/_GoldSilver/_Others` — all 0 rows |
| 14 | **Tulabhara** | **Built, never used** | `HTulabhara`, `HTulabharaDetails` 0 rows (`HTulabharaMaster` has 24 rate rows) |
| 15 | **Tenancy / shop / land rent** | **Built, never used** | `TenantMaster`, `TenantPropertyDetails`, `TenantReceipt(+Details)`, `ShopRentMaster`, `LandRentMaster`, `GenerateTenatRent`, `PropertyStatusMaster` — all 0 |
| 16 | **Hall booking / Bhaktha Nivasa** | **Built, never used** | `HallBookingDetails`, `HallBooking_Payment`, `seva_BhaktaNivasa` — 0 rows |
| 17 | **Money order (postal remittance)** | **Built, never used** | `MOSender`, `MOSenderDetails`, `BackUp_MOSender` — 0 rows |
| 18 | **Annadhana (food offering)** | **Built, never used** | `AnnadhanaDetails` — 0 rows |
| 19 | **Donation (generic)** | **Built, never used** | `Donation` 0; `DonationTypeMaster` 3 |
| 20 | **SMS / email notification** | **Built, never used** | `SMS_TemplateMaster`, `SMSSevaTemplate`, `TotalSMSCount` — 0 rows (though `seva_List` carries `Seva_Closed_SMSReminder`) |
| 21 | **Day-end close & central sync** | **Dormant since 2016** | `DayEndProcess` (200 rows, last 2016-10-08), `DayEndProcessDetails`, `TransferDate` (single row: 2016-05-02) |
| 22 | **Licensing / hardware** | Unused | `localDataBaseLicenseCheck`, `localDataBaseLicenseCheck1`, `HANDDEVICEMASTER` |
| 23 | **Utilities / housekeeping** | Unused | `ElectricalReading`, `Housekeepingsetting`, `DollarEuroINR`, `TaxMaster` |

**Roughly 10 of 23 modules are fully built out in schema, view, and stored-procedure form but hold zero rows.** This is a packaged temple-management product with a broad feature set, deployed at Kollur Mookambika where only the seva, offering, and accounting modules were switched on.

---

## 10. Important Observations

### 10.1 `DailySevaNewOld` is an exact duplicate of all six archives — ~4.7 GB wasted

| Source | Rows |
|---|---:|
| `DailySevaNew20192020` | 2,530,984 |
| `DailySevaNew20202021` | 1,450,474 |
| `DailySevaNew20212022` | 1,712,113 |
| `DailySevaNew20222023` | 3,602,460 |
| `DailySevaNew20232024` | 3,877,213 |
| `DailySevaNew20242025` | 3,809,026 |
| **Sum** | **16,982,270** |
| `DailySevaNewOld` | **16,982,270** |

`DailySevaNewOld` contains exactly the same six `Finyear` values with exactly the same per-year counts. The same holds on the detail side (16,982,272 both ways).

`DailySevaNewOld` (2,941.8 MB) + `DailySevaNewDetailsOld` (1,753.6 MB) = **4.7 GB, roughly 31% of the 15 GB data file**, holding nothing that the year tables do not already hold.

### 10.2 The archive-by-year pattern is manual and currently behind

Splitting by financial year into physically separate, identically-shaped tables is a hand-rolled substitute for table partitioning. Consequences:

- The live `DailySevaNew` presently holds **two** financial years (20252026 with 3.95 M rows and 20262027 with 1.42 M rows) — the 2025–2026 cut has not been made.
- None of the year tables is referenced by any view or stored procedure (confirmed against `sys.sql_expression_dependencies`). All historical access is assembled in application code, presumably by building table names as strings.
- Every reporting query spanning years must `UNION ALL` a variable set of table names.
- A schema change to `DailySevaNew` must be replicated by hand across 8 header tables and 9 detail tables. They have not drifted so far — a column-by-column diff found zero differences — but nothing enforces that.

### 10.3 `IdentityValue` — a 59-column application-managed sequence table

One row per financial year, 10 rows total, each column a next-number counter: `RECEIPT`, `TRNO`, `VONOCASH`, `VONOCHQ`, `DailysevaSlnonew`, `ssv_code`, `SashwathaSevaCode`, `GothraCode`, `logslno`, `TulabharaMasterCode`, `PropertySlNo`, `Namecode`, `Addressno`, `CityNo`, `StateNo`, and so on — 59 in all.

The database has SQL Server `IDENTITY` on only 12 columns and uses **zero** `SEQUENCE` objects. All other surrogate keys are allocated by reading, incrementing and writing this single row — a read-modify-write on one page that every concurrent counter transaction must serialise through. At 3.95 M receipts a year across three counters this is the system's hottest contention point, and the table has no primary key or unique index to protect it from a double allocation.

`AllotReceiptno` (`StartReceiptNo` / `EndReciptNo` / `RunningNo` per user per year) implements a second, parallel receipt-block allocation scheme. It is empty.

### 10.4 No declarative integrity whatsoever

Zero foreign keys, zero unique constraints, zero check constraints, zero triggers. 122 of 167 tables have no primary key; 121 are heaps. The only things standing between this data and corruption are the 129 stored procedures and the application layer. The checks in §6 show the application has done its job so far — but nothing in the database would stop a bad `INSERT` from a maintenance script.

### 10.5 Passwords are stored in plaintext

`UserTable.UserPassword` is `char(10)`. Measured length across the 19 users is 0–10 characters. No hash of any kind fits in ten characters — these are the passwords themselves. `LogDetails` shows 26 distinct user ids have logged in.

Related: `UserTable.Fingerprint1` / `Fingerprint2` hold biometric templates and `Photo` holds an image, all as `nvarchar(max)` in the same table, unencrypted.

*(Password values were deliberately not read or reproduced during this analysis — only length and character-class aggregates.)*

### 10.6 Indexing is effectively absent

Three non-PK indexes across 167 tables and roughly 50 million rows. See §7. Both indexes that do exist are on `DailySevaNew`, the one table that already gets the most attention. Every historical query, every ledger report, and every header-to-detail join on the detail table's non-leading key runs as a scan.

### 10.7 `ModifiedBy` inside primary keys

All 17 DailySeva tables use `PK (Finyear, TempleCode, SevaCode, MultiRecpNo, ModifiedBy)`. Including the editing user's `char(10)` id in the primary key means:

- The key is not a true identity of the receipt — a re-key by a different user would create a second row rather than collide.
- Ten bytes of low-cardinality string are carried in every clustered index page and every row.
- `ReceiptNo`, the natural business key, is **nullable** in `DailySevaNew` and appears in no index at all.

### 10.8 Data-type inconsistency for the same logical column

The same concept is declared differently across tables:

| Column | Distinct declarations | Tables |
|---|---|---:|
| `ReceiptNo` | `varchar(12)`, `varchar(50)`, `varchar(7)`, `int`, `numeric(9)` | 36 |
| `FinYear` | `int`, `char(10)`, `nchar(20)`, `varchar(8)` | 70 |
| `ModifiedDate` | `smalldatetime`, `datetime`, `varchar(20)`, `varchar(50)` | 64 |
| `ModifiedBy` | `char(10)`, `char(25)`, `varchar(10)`, `varchar(50)` | 71 |
| `MobileNo` | `varchar(10/12/20/30/50)` | 18 |
| `ssk_code` | `varchar(8)`, `varchar(12)`, `varchar(20)` | 10 |
| `ssv_code` | `smallint`, `int`, `numeric(9)` | 12 |
| `Sevacode` | `smallint`, `int` | 24 |
| `Amount` | `money`, `int` | 30 |
| `Email` | `varchar(25)`, `varchar(50)`, `varchar(200)` | 12 |

Every join across such a pair forces an implicit conversion, which defeats index seeks and can silently truncate — for example `ssk_code varchar(20)` in `seva_sevakartaDetails` joining `ssk_code varchar(12)` in `seva_sevakarta`, or `AddressMaster.Email varchar(25)` against `NameMaster.Email varchar(200)`.

### 10.9 Kannada text with a Latin collation

Temple names, seva names (`ssv_Name`) and `seva_period.spr_name` are Kannada script stored in `nvarchar` columns — correct. But the database collation is `SQL_Latin1_General_CP1_CI_AS`, so sorting and comparison of Kannada text fall back to binary code-point order, and any `varchar` column receiving Kannada text would lose it outright. `AddressMaster.City` / `State` / `Country` and the `TenantMaster` fields are `varchar` and cannot hold Kannada.

---

## 11. Suspicious / Inconsistent Schema Findings

### 11.1 Type-mismatched default constraints

`getdate()` is the default on seven **non-date** columns:

| Table | Column | Declared type | Default |
|---|---|---|---|
| `AddressMaster` | **`ModifiedBy`** | `char(10)` | `getdate()` |
| `FN_ACCOUNTMASTER` | `ModifiedDate` | `varchar(50)` | `getdate()` |
| `PropertyStatusMaster` | `StayDate` | `varchar(20)` | `getdate()` |
| `PropertyStatusMaster` | `DateOfFillingPWR` | `varchar(20)` | `getdate()` |
| `PropertyStatusMaster` | `DateOfFillingOfObjections` | `varchar(20)` | `getdate()` |
| `PropertyStatusMaster` | `DisposalDate` | `varchar(20)` | `getdate()` |
| `PropertyStatusMaster` | `ModifiedDate` | `varchar(20)` | `getdate()` |

`AddressMaster.ModifiedBy` is the worst of these: a `NOT NULL char(10)` *user-id* column whose default is the current timestamp, **silently truncated to 10 characters**. Any insert that omits `ModifiedBy` writes a mangled date fragment into an audit field. The table has 5,164 rows.

The `PropertyStatusMaster` group stores five dates as `varchar(20)`, so they sort lexicographically and cannot be range-queried without conversion.

### 11.2 Numeric defaults on string columns

Thirteen `varchar` / `nvarchar` / `char` columns default to `((0))`, several of them business identifiers:

`FN_ACCOUNTMASTER.AccountCode`, `FN_TRANSACTION.TrCode`, `FN_VOUCHERPAYEMENT.VoucherNo`, `Donation.ReceiptNo`, `Duplicateitemmaster.ReceiptNo`, `OtherIncome.Incomedet`, `OtherIncome.TypeCode`, `seva_seva.Header1` *(an `nvarchar(400)` receipt header defaulting to the string `"0"`)*, `ClothTypeMaster.ClothTypeName`, `BhakthaHome.TimeslnoFrom` / `TimeslnoTo`, `seva_sevegaluDailySpecialdarshan.ModifiedBy`, `localDataBaseLicenseCheck1.IsActive`.

A missing account code or voucher number therefore becomes the literal string `"0"` rather than failing.

### 11.3 Compatibility level 100 on SQL Server 2025

The database runs at **compatibility level 100 (SQL Server 2008)** on a 2025 engine — seventeen years of query-optimiser improvements are switched off, including the cardinality estimator introduced in 2014 and batch-mode-on-rowstore. Given §7, this compounds the scan problem. Raising it is not risk-free and would need regression testing; flagging it, not recommending it blindly.

### 11.4 Transaction log is 10.7 GB under SIMPLE recovery

`KolSoham_log.ldf` is 10,668 MB against a 14,993 MB data file. Under SIMPLE recovery a log this size implies a very large single transaction at some point — most plausibly the bulk copy that produced `DailySevaNewOld` — and the file was never shrunk afterwards. No log backups are being taken (SIMPLE recovery), so point-in-time recovery is unavailable for a database recording roughly ₹90 crore a year.

### 11.5 Logical file names do not match the database

The database is `KOLSOHAM_LOCAL` but its files are logically named `KolSoham` / `KolSoham_log` (physical: `KOLSOHAM_LOCAL.mdf`). The database was also created **2026-09-16** — the date of this analysis — confirming this is a freshly restored copy of a production database, not production itself. `create_date` should be read in that light; row counts and data dates are genuine.

### 11.6 Table name typos and inconsistent casing

Carried in production schema and therefore hard to fix:

- `GenerateTenatRent` — "Tenat", should be "Tenant" (`GenerateTenantMaster` spells it correctly two tables away).
- `FN_VOUCHERPAYEMENT` and procedure `PROC_FN_VOUCHER_PAYEMENT` — "PAYEMENT".
- `seva_pachanga_nakshatra` — "pachanga", vs. `seva_masa_panchanga_surya` and `PanchangaMaster` which spell it "panchanga".
- `seva_seva.PrintStartPoistion` — "Poistion".
- `DailySevaNew.BillCancled`, `SareeDonation.billcancled`, `HKanikeItems.BillCancel`, `AnnadhanaDetails.BillCancle` — four spellings of "cancelled" across four tables.
- Casing is arbitrary: `sareetypemaster` vs `SareeIssueTypeMaster`, `Duplicateitemmaster` vs `DuplicateTypeMaster`, `DAILYSEVANEWMULTI` vs `DailySevaNew`, `seva_seva` vs `Seva_DayWise` vs `SET_Seva_seva`.
- `IX_Sannidi` indexes a column named `ssv_sannidhi` — the index name drops an "h".

### 11.7 Duplicated and abandoned objects

- **`seva_RWsevakartaDetails`** duplicates `seva_sevakartaDetails` — 6,659 rows in both, 86 vs 94 columns, differing essentially by `Renslno`. A renewal cycle implemented by cloning a 94-column table.
- **`PROC_FN_AccountTransfer_Cr1`, `_Cr2`, `_Cr3`, `_Cr4`** — four procedures with identical 22-parameter signatures, presumably differing only in a hard-coded account. Plus `_Dr`.
- **`sp_KolSevaDailySevaNew`, `sp_KolSevaDailySevaNew1`, `sp_KolSevaDailySevaNewSetMuk`** — three 65-parameter variants of the same write path. Only `…New1` was modified in 2026; the other two may be dead.
- **`sp_SareeAuctionOLD`, `sp_SareeAuctionDetailsOLD`** kept alongside the live versions.
- **`sp_MultiReceiptNo` / `sp_KOLMultiReceiptNo`** and **`sp_KolMultiRecptDS` / `sp_KolMultiRecptDSNew`** — parallel receipt-number allocators.
- **11 `…Old` views** created 2025-12-09 as rollback copies (§8.1) and never removed.
- **`VIEW_DAILYSEVANEW43temp`, `VIEW_DAILYSEVANEW43test`, `Vw_BhandaraStatementtemp1`** — temp/test views in the production schema.
- **`FN_CashFlowTemp`, `tmp_HundiCollectionNos`, `BackUp_MOSender`, `localDataBaseLicenseCheck1`, `SareeDonationOld`, `DailySevaNewOld`, `DailySevaNewDetailsOld`** — temp/backup/duplicate tables in the production schema.

### 11.8 Two orphan detail rows

`DailySevaNewDetails20192020` has **2,530,986** rows against `DailySevaNew20192020`'s **2,530,984** — two detail rows more than headers. The same +2 discrepancy propagates into `DailySevaNewDetailsOld` (16,982,272) vs `DailySevaNewOld` (16,982,270). Small, but it is exactly the class of drift that the absent foreign keys would have prevented. (The *live* `DailySevaNew` / `DailySevaNewDetails` pair is balanced at 5,365,855 each, and a direct orphan check on it returned zero.)

### 11.9 `seva_seva` has no primary key but a non-unique clustered index

The seva master — referenced by `SevaCode` from 5.4 M live receipt rows and 34 M archived ones — has **no primary key and no unique constraint on `ssv_code`**. Its clustered index `IX_Sannidi` is on `ssv_sannidhi`, which has just **four** distinct values across 164 rows. Nothing prevents a duplicate `ssv_code`, and lookups by `ssv_code` — the actual access path — cannot seek.

### 11.10 `Menu` permissions are columns, not rows

`Menu` encodes authorisation as 26 fixed `bit` columns (`ADMIN`, `SUBADMIN`, `SMT`, `GSJ`, `CC1`…`CC13`, `RC`, `RC1`, `SA`, `FDP`, `UG`). Adding a counter or a role requires `ALTER TABLE` plus changes to every query that reads it — and `Menu` is referenced by no view or procedure, so all of that logic lives in application code. Meanwhile `Roles` / `RolesDetails` implements a *proper* row-based permission model alongside it. Two competing authorisation schemes coexist.

### 11.11 Redundant and contradictory master data

- `seva_seva.ssv_SevaType` is `char(1)` with 19 distinct values, of which **146 of 164 rows are `'D'`** and the other 18 values (`A`,`B`,`C`,`E`,`F`,`G`,`K`,`M`,`N`,`O`,`P`,`T`,`U`,`V`,`W`,`X`,`Y`,`Z`) have exactly **one row each**. Either the classification is unused or eighteen one-off categories were created ad hoc.
- `LookupDetails` is a generic key-value lookup (`LookupTypeCode` 1 = unit of measure, 3 = tax rate, 4 = payment mode, 5 = a person's name) whose last modification was **2016-03-13**. Type 5 contains a single individual's name as lookup data.
- `AddressMaster` has both `Address` and `Address1`–`Address5`, plus `City` / `State` / `Country` as free text *and* `CityMaster` / `StateMaster` / `CountryMaster` tables it does not reference.
- `AddressMaster.LastPrintedDate` is `varchar(50)` and `FN_ACCOUNTMASTER.ModifiedDate` is `varchar(50)` — dates as strings in tables that also carry proper `smalldatetime` columns.
- Two parallel "duplicate item" tables, `Duplicateitemmaster` and `DuplicateTypeMaster`, hold one row each.

### 11.12 Multi-tenancy that was never used

`TempleCode` appears in most tables and in nearly every composite primary key, and `TempleMaster` holds 43 temples with grades and types. **Every transactional row is `TempleCode = 43`.** The column costs storage and index width in every large table for a capability this deployment does not use. `seva_sannidhi` likewise scopes all four of its rows to temple 43.

### 11.13 32 tables are referenced by no view or stored procedure

From `sys.sql_expression_dependencies`: `CASHTRAN`, all 12 `DailySevaNew<FY>` / `DailySevaNewDetails<FY>` archives, `DailySevaNewAllyear`, `DailySevaNewDetailsALLYEAR`, `DollarEuroINR`, `ElectricalReading`, `HANDDEVICEMASTER`, `Housekeepingsetting`, `localDataBaseLicenseCheck1`, **`Menu`**, **`Modules`**, `SareeDonationOld`, `SareeKanikeMaster`, `Seva_DayWiseMulti`, `SpeOptionOnSevaCode`, `TaxMaster`, **`TempleMaster`**, `TotalSMSCount`, `TypeMaster`, `ValidationMaster`, `Week`.

That `TempleMaster`, `Menu` and `Modules` — all live, populated tables — appear here means the corresponding logic sits entirely in application code with no database-side abstraction. For the archive tables it confirms §10.2: historical access is by dynamically-constructed table names.

---

## 12. Summary of Concerns, by Severity

| # | Finding | Severity | Section |
|---:|---|---|---|
| 1 | Plaintext passwords in `UserTable.UserPassword char(10)`; biometrics and photos unencrypted in the same table | **Critical** | §10.5 |
| 2 | Zero foreign keys / unique / check constraints; 122 tables without a primary key | **Critical** | §10.4, §6 |
| 3 | 3 non-PK indexes across ~50 M rows; no date index on any archive table; `FN_TRANSACTION` unindexed | **High** | §7, §10.6 |
| 4 | `DailySevaNewOld` + `DailySevaNewDetailsOld` duplicate all archives — 4.7 GB, ~31% of the data file | **High** | §10.1 |
| 5 | `IdentityValue` single-row sequence table with no unique index — serialisation point and double-allocation risk at ~4 M receipts/year | **High** | §10.3 |
| 6 | 10.7 GB log under SIMPLE recovery; no point-in-time recovery for ₹90 cr/yr of records | **High** | §11.4 |
| 7 | `AddressMaster.ModifiedBy char(10) NOT NULL DEFAULT getdate()` — silent truncation into an audit column | **Medium** | §11.1 |
| 8 | Same logical column declared 3–5 different ways across 70+ tables | **Medium** | §10.8 |
| 9 | `seva_seva` has no PK/unique on `ssv_code` — the most-referenced master table is unprotected | **Medium** | §11.9 |
| 10 | Dates stored as `varchar` in `PropertyStatusMaster`, `FN_ACCOUNTMASTER`, `AddressMaster` | **Medium** | §11.1 |
| 11 | Manual year-table archiving; the 2025–2026 cut is overdue and the live table holds two years | **Medium** | §10.2 |
| 12 | `ModifiedBy` inside 17 primary keys; `ReceiptNo` nullable and unindexed | **Medium** | §10.7 |
| 13 | Compatibility level 100 (SQL Server 2008) on a SQL Server 2025 engine | **Medium** | §11.3 |
| 14 | Kannada data under a Latin collation; `varchar` address columns cannot hold it | **Low–Medium** | §10.9 |
| 15 | Duplicated procedures and `…Old` / `…temp` / `…test` objects in the production schema | **Low** | §11.7 |
| 16 | Two competing authorisation models (`Menu` columns vs `Roles` rows) | **Low** | §11.10 |
| 17 | Persistent name typos and inconsistent casing | **Low** | §11.6 |
| 18 | Two orphan detail rows in the 2019–2020 archive | **Low** | §11.8 |
| 19 | ~10 fully-built modules with zero rows | Informational | §9 |
| 20 | Day-end / central-sync mechanism dormant since 2016 | Informational | §9 #21 |
| 21 | `TempleCode` multi-tenancy present everywhere but unused | Informational | §11.12 |

---

## Appendix A — Queries Used

All read-only. Representative examples:

```sql
-- Connection check
SELECT DB_NAME() AS DatabaseName, SUSER_SNAME() AS LoginName;

-- Tables
SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_SCHEMA, TABLE_NAME;

-- Row counts (metadata only, no table scan)
SELECT s.name + '.' + t.name,
       SUM(CASE WHEN p.index_id IN (0,1) THEN p.rows ELSE 0 END)
FROM sys.tables t
JOIN sys.schemas s    ON t.schema_id = s.schema_id
JOIN sys.partitions p ON t.object_id = p.object_id
GROUP BY s.name, t.name
ORDER BY 2 DESC;

-- Storage
SELECT t.name, SUM(a.total_pages) * 8.0 / 1024 AS total_mb
FROM sys.tables t
JOIN sys.indexes i          ON t.object_id = i.object_id
JOIN sys.partitions p       ON i.object_id = p.object_id AND i.index_id = p.index_id
JOIN sys.allocation_units a ON p.partition_id = a.container_id
GROUP BY t.name ORDER BY 2 DESC;

-- Columns, data types, nullability, defaults, identity
SELECT t.name, c.column_id, c.name, TYPE_NAME(c.user_type_id), c.max_length,
       c.precision, c.scale, c.is_nullable, c.is_identity, dc.definition
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
ORDER BY t.name, c.column_id;

-- Keys, indexes, constraints
SELECT * FROM sys.indexes WHERE is_primary_key = 1;   -- 45 rows
SELECT * FROM sys.indexes WHERE is_unique = 1 AND is_primary_key = 0;  -- 0 rows
SELECT * FROM sys.foreign_keys;                       -- 0 rows
SELECT * FROM sys.check_constraints;                  -- 0 rows
SELECT * FROM sys.identity_columns;                   -- 12 rows
SELECT * FROM sys.triggers WHERE parent_class = 1;    -- 0 rows

-- Programmable objects
SELECT * FROM sys.views;
SELECT * FROM sys.procedures;
SELECT m.definition FROM sys.sql_modules m JOIN sys.objects o ON m.object_id = o.object_id;
SELECT * FROM sys.sql_expression_dependencies;

-- Type-mismatched defaults
SELECT t.name, c.name, TYPE_NAME(c.user_type_id), dc.definition
FROM sys.default_constraints dc
JOIN sys.columns c ON dc.parent_object_id = c.object_id
                  AND dc.parent_column_id = c.column_id
JOIN sys.tables  t ON t.object_id = dc.parent_object_id
WHERE dc.definition LIKE '%getdate%'
  AND TYPE_NAME(c.user_type_id) NOT IN ('datetime','smalldatetime','date','datetime2');

-- Integrity spot-checks
SELECT COUNT_BIG(*) FROM DailySevaNew d
WHERE NOT EXISTS (SELECT 1 FROM seva_seva s WHERE s.ssv_code = d.SevaCode);

SELECT COUNT_BIG(*) FROM DailySevaNewDetails d
WHERE NOT EXISTS (SELECT 1 FROM DailySevaNew h
                  WHERE h.ReceiptNo = d.ReceiptNo
                    AND h.Finyear   = d.Finyear
                    AND h.TempleCode= d.TempleCode);

-- Archive duplication proof
SELECT Finyear, COUNT_BIG(*) FROM DailySevaNewOld GROUP BY Finyear ORDER BY Finyear;
```

## Appendix B — Reproducing This Analysis

`sqlcmd` is not on the system `PATH`. Invoke it by full path:

```bash
SQLCMD="/c/Program Files/Microsoft SQL Server/Client SDK/ODBC/180/Tools/Binn/SQLCMD.EXE"
"$SQLCMD" -S localhost -d KOLSOHAM_LOCAL -E -C -W -s"|" -f 65001 -Q "SET NOCOUNT ON; <query>"
```

Flags that matter:

- `-E` Windows Authentication, `-C` trust the server certificate (required against the local 2025 instance).
- `-f 65001` sets the UTF-8 output code page. **Without it, all Kannada text renders as `?`** — temple names, seva names and `seva_period.spr_name` are unreadable.
- `-W` (trim whitespace) and `-y`/`-Y` (display width) are **mutually exclusive**; passing both fails with `Sqlcmd: The -W and the -y/-Y options are mutually exclusive.`
