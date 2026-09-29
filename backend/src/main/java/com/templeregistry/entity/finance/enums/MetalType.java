package com.templeregistry.entity.finance.enums;

/**
 * Canonical kind of precious item (FR7), resolved from a source value through
 * {@link MappingType#METAL_TYPE}.
 *
 * <p>{@link #UNMAPPED} follows the revenue category precedent: an item whose
 * kind no rule covers is still an item the temple received, and hiding it in
 * {@link #OTHER_PRECIOUS} would make it indistinguishable from something a
 * reviewer had deliberately classified.
 */
public enum MetalType {
    GOLD,
    SILVER,
    /** A precious item a reviewer has classified as neither gold nor silver. */
    OTHER_PRECIOUS,
    /** No mapping rule covers the source value. Deliberately visible. */
    UNMAPPED
}
