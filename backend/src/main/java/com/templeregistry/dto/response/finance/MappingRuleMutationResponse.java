package com.templeregistry.dto.response.finance;

import java.util.List;

/**
 * The result of saving a rule, and what saving it did not do (FIN-054A).
 *
 * @param rule             the rule as stored
 * @param historicalEffect the sentence the caller must show the user. A saved rule changes how
 *                         future runs classify a value; every figure already in
 *                         {@code fin_revenue_fact} keeps the classification it was loaded with.
 *                         Nothing in this API re-processes anything, so a user who corrects a
 *                         misclassification and sees "Saved" would otherwise reasonably conclude
 *                         the published figures had been corrected
 * @param warnings         things that are legal but probably wrong — chiefly a namespace no
 *                         observed staged payload contains, which is accepted, shows as active,
 *                         and will never match a record
 */
public record MappingRuleMutationResponse(MappingRuleResponse rule,
                                          String historicalEffect,
                                          List<String> warnings) {

    /** The one sentence every write path returns, worded the same way each time. */
    public static final String HISTORICAL_EFFECT =
            "This changes how future pipeline runs classify this value. Figures already published "
                    + "keep their current classification until the batch that produced them is "
                    + "re-run. Saving a rule does not re-process or correct historical data.";

    public static MappingRuleMutationResponse of(MappingRuleResponse rule, List<String> warnings) {
        return new MappingRuleMutationResponse(rule, HISTORICAL_EFFECT, List.copyOf(warnings));
    }
}
