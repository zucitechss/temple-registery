package com.templeregistry.service.finance.mapping;

import com.templeregistry.common.PaginatedResponse;
import com.templeregistry.dto.request.finance.CreateMappingRuleRequest;
import com.templeregistry.dto.request.finance.MappingRuleStatusRequest;
import com.templeregistry.dto.request.finance.UpdateMappingRuleRequest;
import com.templeregistry.dto.response.finance.CanonicalValueResponse;
import com.templeregistry.dto.response.finance.MappingRuleMutationResponse;
import com.templeregistry.dto.response.finance.MappingRuleResponse;
import com.templeregistry.dto.response.finance.NamespaceCatalogueResponse;
import com.templeregistry.dto.response.finance.SourceSystemSummaryResponse;
import com.templeregistry.dto.response.finance.UnresolvedValueResponse;
import com.templeregistry.entity.finance.enums.MappingOutcome;
import com.templeregistry.entity.finance.enums.MappingType;

import java.util.List;

/**
 * Administration of source-value translations — the Source Mapper's backend (FIN-054A).
 *
 * <p>This is the first entry point the finance pipeline has ever had. Everything from FIN-050 to
 * FIN-061 is invoked by a worker or by a test, and so the pipeline has needed no authorization,
 * no scope resolution and no audit. Adding a screen adds all three at once, which is why they are
 * here on the service rather than only on the controller: a guard on an HTTP annotation protects
 * one route, and a guard here protects the operation however it is later reached.
 *
 * <h2>What this service will not do</h2>
 *
 * <p><b>It never writes a canonical fact.</b> Not one method touches {@code fin_revenue_fact},
 * {@code fin_stg_revenue} or any pipeline state, and none triggers a run. Correcting a rule
 * changes how the next run classifies a value; the figures already loaded keep the classification
 * they were loaded with, and every write path says so in its response. Silently re-processing
 * historical financial data on the strength of a dropdown change would be a far worse failure than
 * leaving a figure visibly wrong.
 *
 * <p><b>It exposes no SQL and no schema.</b> Filters are typed, sorting is an allow-list of
 * property names, and no endpoint accepts a table, a column or an order-by fragment.
 */
public interface MappingAdminService {

    /** Source systems the caller is entitled to see, with their active rule counts. */
    List<SourceSystemSummaryResponse> listSourceSystems();

    /**
     * One source system's rules, paged.
     *
     * @param sort an allow-listed property name, optionally suffixed {@code ,asc} or {@code ,desc}
     * @throws IllegalArgumentException if {@code sort} is not allow-listed
     */
    PaginatedResponse<MappingRuleResponse> listRules(Long sourceSystemId,
                                                     MappingType mappingType,
                                                     Boolean active,
                                                     String canonicalValue,
                                                     String search,
                                                     int page,
                                                     int size,
                                                     String sort);

    MappingRuleResponse getRule(Long id);

    MappingRuleMutationResponse create(CreateMappingRuleRequest request);

    MappingRuleMutationResponse update(Long id, UpdateMappingRuleRequest request);

    MappingRuleMutationResponse setActive(Long id, MappingRuleStatusRequest request);

    /** What the most recent batch could not classify, worst first. */
    UnresolvedValueResponse listUnresolved(Long sourceSystemId, MappingOutcome outcome);

    /** The staged field names this source has been observed to emit. */
    NamespaceCatalogueResponse namespaces(Long sourceSystemId);

    /** The canonical vocabulary a rule may name. */
    List<CanonicalValueResponse> canonicalValues();
}
