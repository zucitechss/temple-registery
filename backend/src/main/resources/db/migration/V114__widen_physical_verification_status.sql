-- AssetDeclaration.physicalVerificationStatus is @Column(length = 50) and is persisted via
-- PhysicalVerificationStatusConverter, which writes PhysicalVerificationStatus.name() (the
-- full enum name) — the same convention used for every other enum-backed status column in
-- this schema (e.g. asset_declarations.status VARCHAR(40) for DeclarationStatus, whose
-- longest value is 24 chars; system_verification_status VARCHAR(30) for SystemVerificationStatus,
-- whose longest value is 15 chars).
--
-- V1 created physical_verification_status as VARCHAR(30), but
-- PhysicalVerificationStatus.ORDERED_FOR_PHYSICAL_VERIFICATION is 34 characters — longer than
-- the column itself, not just narrower than the entity's declared length. Any DC ordering a
-- physical verification fails with "Data truncation: Data too long for column
-- 'physical_verification_status'". Widening to VARCHAR(50) to match the entity (the correct,
-- already-consistent side) is lossless. Nullability/default are left exactly as V1 defined
-- them (nullable, no DB default) — this migration touches only the length.
ALTER TABLE asset_declarations
    MODIFY COLUMN physical_verification_status VARCHAR(50) NULL;
