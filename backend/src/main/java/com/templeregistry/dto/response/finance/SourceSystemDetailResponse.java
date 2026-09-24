package com.templeregistry.dto.response.finance;

import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;

import java.time.LocalDateTime;

/**
 * One source system's administrative detail (FIN-140 slice 140-A).
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>There is <b>no {@code credentialRef} field</b>. Not masked, not truncated — absent, so no
 * future change to a serialiser, a logger or a {@code toString} can leak it. The alias is not the
 * secret, but it names the environment variable holding one, which is the first thing worth
 * knowing to go looking for it. {@link #credentialRefSet()} answers the only question an
 * administrator needs from this screen: has one been configured.
 *
 * <p>{@code connectorBean} and {@code sourceDatabaseName} <i>are</i> here, because configuring
 * them is what this endpoint exists for. They describe how a temple's live system is reached, so
 * this response is served only from the platform-administrator endpoint and never appears in any
 * reporting response — {@code SourceSystemSummaryResponse}, which four roles can read, excludes
 * all three for exactly this reason.
 */
public record SourceSystemDetailResponse(Long id,
                                         Long templeId,
                                         String templeName,
                                         String systemCode,
                                         String systemName,
                                         SourceTechnology sourceTechnology,
                                         ConnectorType connectorType,
                                         String connectorBean,
                                         String sourceTempleCode,
                                         String sourceDatabaseName,
                                         boolean credentialRefSet,
                                         String syncScheduleCron,
                                         boolean syncEnabled,
                                         Integer stalenessThresholdHours,
                                         String sourceTimezone,
                                         String notes,
                                         Integer version,
                                         LocalDateTime createdAt,
                                         LocalDateTime updatedAt) {
}
