package com.templeregistry.dto.response.dc;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter @Builder
public class DeclImmovOtherResponse {
    private Long id;
    private String location;
    private BigDecimal area;
    private String usageType;
    private String revenueDepartmentReference;
}
