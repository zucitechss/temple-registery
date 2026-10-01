package com.templeregistry.dto.response.dc;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter @Builder
public class DeclImmovAgriLandResponse {
    private Long id;
    private String surveyNumber;
    private String village;
    private BigDecimal areaAcres;
    private String ownerOfRecord;
    private String pattaStatus;
}
