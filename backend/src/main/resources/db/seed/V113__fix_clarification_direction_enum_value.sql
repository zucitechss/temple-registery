-- ============================================================================
-- V113: Fix invalid ClarificationDirection value in seeded declaration_clarifications
--
-- V100 seeded row id=100 with direction='OUTBOUND', but the Java enum
-- (com.templeregistry.entity.declaration.ClarificationDirection) only has
-- DC_TO_TEMPLE and TEMPLE_TO_DC. Hibernate throws IllegalArgumentException on
-- read ("No enum constant ... OUTBOUND"), which was unreachable until TODO
-- 10's schema-drift fix let the app get far enough to actually query this row.
--
-- The row's own comment calls it "One outbound thread" and its message asks
-- the temple to provide supporting documents — a request FROM the DC TO the
-- temple, i.e. DC_TO_TEMPLE.
-- ============================================================================

UPDATE declaration_clarifications
SET direction = 'DC_TO_TEMPLE'
WHERE id = 100 AND direction = 'OUTBOUND';
