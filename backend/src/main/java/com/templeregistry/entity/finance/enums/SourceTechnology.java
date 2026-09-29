package com.templeregistry.entity.finance.enums;

/**
 * Database or interface technology of a temple source system.
 *
 * <p>{@link #MANUAL} is the honest answer for a channel whose "technology" is a
 * person and a web form (V122). Recording it as {@code FILE} or {@code API}
 * would suggest something for a connector to talk to.
 */
public enum SourceTechnology {
    SQL_SERVER,
    MYSQL,
    POSTGRESQL,
    ORACLE,
    /** A file the platform collects or is given, including an uploaded workbook. */
    FILE,
    API,
    /** No external system at all: temple staff enter the figures directly. */
    MANUAL
}
