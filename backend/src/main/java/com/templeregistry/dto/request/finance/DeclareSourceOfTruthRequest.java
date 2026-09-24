package com.templeregistry.dto.request.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Declare which source field is authoritative for one metric (FIN-140-C, ADR-008).
 *
 * <p>There is no update request beside this one, and that is the design rather than an omission.
 * Every call creates a <b>new version</b>: the prior one is closed with an {@code effective_to}
 * and kept, because facts already loaded carry the version that produced them and a figure
 * published last quarter has to stay explicable. Editing a declaration in place would leave the
 * stamp on those facts pointing at a row that no longer says what it said.
 *
 * <p>{@code version} is absent for the same reason — it is assigned by the server as one past the
 * highest that has ever existed for this source and metric. What the caller states instead is
 * {@link #supersedesVersion}: which version they were looking at. That is the lost-update guard,
 * and it is a different question from "what number should this be".
 */
@Getter
@Setter
@NoArgsConstructor
public class DeclareSourceOfTruthRequest {

    /**
     * The metric this declares, e.g. {@code REVENUE_AMOUNT}.
     *
     * <p>Checked against {@code RevenueField} rather than against a list kept here: the pipeline
     * names the metrics it reads, and a declaration for a metric nothing reads would be an inert
     * row nobody would ever find out about.
     */
    @NotBlank(message = "metric is required.")
    @Size(max = 50, message = "metric must be at most 50 characters.")
    private String metric;

    /** The source object the value is read from, e.g. a table or a view name. */
    @NotBlank(message = "sourceObject is required — a declaration that names no object declares nothing.")
    @Size(max = 200, message = "sourceObject must be at most 200 characters.")
    private String sourceObject;

    /**
     * The field carrying the value.
     *
     * <p>Also the key normalization reads from a staged payload, which is why it is the same
     * vocabulary a mapping rule's namespace uses (FIN-D-028): a connector that renames a field on
     * the way out must declare the name it emits, not the one it read.
     */
    @NotBlank(message = "sourceField is required.")
    @Size(max = 100, message = "sourceField must be at most 100 characters.")
    private String sourceField;

    /** Which source rows count — cancellations and soft deletes, typically. Free text. */
    private String filterPredicate;

    /**
     * The candidates considered and why each was rejected.
     *
     * <p>Optional in the schema and strongly encouraged in practice: for the first onboarded
     * source, three columns plausibly represented revenue and disagreed by 41%, and the
     * <em>more granular</em> one was the wrong answer. Recording the measurement beside the
     * rejection is what stops a later engineer "improving" the query.
     */
    @Valid
    private List<RejectedAlternative> rejectedAlternatives;

    /** Why this field, in prose. */
    private String rationale;

    /**
     * Sign-off, recorded on the two columns ADR-008 put there for it.
     *
     * <p>Absent or false means the declaration is in force and awaiting sign-off, which is the
     * state the first onboarded source's own declarations are in: their evidence is strong but it
     * is inference, and the source has never been reachable to confirm it. Conflating "declared"
     * with "confirmed" is what this flag exists to keep apart.
     */
    private Boolean approved;

    /**
     * The business date this version starts applying from.
     *
     * <p>Required, because it is what closes the previous version: the prior declaration's
     * {@code effective_to} is set to this date, so an absent one would leave two rows in force
     * with nothing deciding between them.
     */
    @NotNull(message = "effectiveFrom is required — it is the date that closes the previous version.")
    private LocalDate effectiveFrom;

    /**
     * The version the caller believes is currently in force, or null when they believe none is.
     *
     * <p>Not an optimistic-lock column and deliberately not one: nothing about the row they read
     * is being overwritten. What this catches is two administrators who each read version 1 and
     * each write "the next one" — without it the second silently supersedes a version they never
     * saw. The unique key on (source, metric, version) catches the same race at commit; this
     * catches it with a sentence a person can act on.
     */
    private Integer supersedesVersion;

    /** One considered-and-rejected candidate. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class RejectedAlternative {

        @NotBlank(message = "each rejected alternative must name the object it was read from.")
        @Size(max = 200, message = "object must be at most 200 characters.")
        private String object;

        @Size(max = 100, message = "field must be at most 100 characters.")
        private String field;

        /** What it measured, as free text — a total, a row count, a financial year. */
        @Size(max = 200, message = "measured must be at most 200 characters.")
        private String measured;

        @NotBlank(message = "each rejected alternative must say why it was rejected — that is the "
                + "part which stops it being tried again.")
        @Size(max = 2000, message = "reason must be at most 2000 characters.")
        private String reason;
    }
}
