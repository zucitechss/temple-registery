package com.templeregistry.dto.response.dc;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter @Builder
public class DeclMovVehicleResponse {
    private Long id;
    private String registrationNumber;
    private String makeModel;
    private Integer year;
    private String purpose;
    private BigDecimal estimatedValueInr;
}
