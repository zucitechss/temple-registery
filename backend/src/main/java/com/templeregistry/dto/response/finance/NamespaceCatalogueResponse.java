package com.templeregistry.dto.response.finance;

import java.util.List;

/**
 * The staged field names a source system has actually been observed to emit (FIN-054A).
 *
 * <p>This is evidence, not a schema. No registry of a source's field names exists anywhere: the
 * connector chooses them, and the resolver derives what it reads from the rules themselves. The
 * only thing that can be said with certainty is what has turned up in staged payloads, which is
 * what this reports — together with how many payloads were looked at, so a caller can tell an
 * empty answer from a confident one.
 *
 * @param observed     field names found in the sample, alphabetically
 * @param inUseByRules namespaces this source's existing rules already use. A name here but not in
 *                     {@code observed} is a rule that cannot currently match anything
 * @param sampledRows  how many staged rows were examined. Zero means nothing has been staged for
 *                     this source yet, so an unrecognised namespace cannot be distinguished from a
 *                     correct one and no warning is possible
 */
public record NamespaceCatalogueResponse(Long sourceSystemId,
                                         List<String> observed,
                                         List<String> inUseByRules,
                                         int sampledRows) {
}
