package com.templeregistry.service.finance.sync.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one place configuration can contribute text to generated SQL (FIN-040).
 *
 * <p>Every value the connector reads is a bound parameter. A table name and a column name cannot
 * be — JDBC has no placeholder for an identifier — so these are the strings that get concatenated,
 * and this is where injection would enter if it entered anywhere.
 */
class SqlIdentifierTest {

    @ParameterizedTest(name = "[{0}] is a plain identifier")
    @ValueSource(strings = {"receipts", "SOURCE_RECEIPTS", "_private", "a", "col1", "Transaction_Date"})
    @DisplayName("Plain identifiers are accepted unchanged")
    void should_accept_when_identifierIsPlain(String value) {
        assertThat(SqlIdentifier.of("column", value).value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = {
            "receipts; DROP TABLE users",
            "receipts--",
            "receipts/*x*/",
            "receipts'",
            "receipts\"",
            "receipts`",
            "receipts WHERE 1=1",
            "1 OR 1=1",
            "sum(amount)",
            "schema.receipts",
            "réceipts",
            "9receipts",
            ""})
    @DisplayName("Anything that is not a plain identifier is refused rather than escaped")
    void should_refuse_when_identifierIsNotPlain(String value) {
        assertThatThrownBy(() -> SqlIdentifier.of("table", value))
                .as("an allow-list answers \"is this a plain identifier\"; escaping would have to "
                        + "answer \"can this be made safe\", which has endless counter-examples")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Surrounding whitespace is trimmed rather than refused")
    void should_trim_when_identifierHasSurroundingWhitespace() {
        assertThat(SqlIdentifier.of("column", "  AMOUNT  ").value())
                .as("configuration written by hand acquires whitespace; the trimmed result is "
                        + "still validated as a plain identifier, so trimming is safe")
                .isEqualTo("AMOUNT");
    }

    @Test
    @DisplayName("A null identifier is refused")
    void should_refuse_when_identifierIsNull() {
        assertThatThrownBy(() -> SqlIdentifier.of("table", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
    }

    @ParameterizedTest(name = "[{0}] names a catalogue and is refused")
    @ValueSource(strings = {"information_schema", "INFORMATION_SCHEMA", "mysql", "sys", "pg_catalog"})
    @DisplayName("Database catalogues are refused even though they are plain identifiers")
    void should_refuse_when_identifierNamesACatalogue(String value) {
        assertThatThrownBy(() -> SqlIdentifier.of("table", value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalogue");
    }

    @Test
    @DisplayName("An over-long identifier is refused")
    void should_refuse_when_identifierIsTooLong() {
        assertThatThrownBy(() -> SqlIdentifier.of("column", "c".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The failure names the configuration role, so an operator can find the property")
    void should_nameTheRole_when_refusing() {
        assertThatThrownBy(() -> SqlIdentifier.of("business-date-column", "bad name"))
                .hasMessageContaining("business-date-column")
                .hasMessageContaining("bad name");
    }
}
