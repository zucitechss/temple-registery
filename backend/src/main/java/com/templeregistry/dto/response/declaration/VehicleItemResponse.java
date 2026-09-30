package com.templeregistry.dto.response.declaration;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleItemResponse extends AssetItemResponse {
    private String registrationNumber;
    private String makeModel;
    private Integer year;
    private String purpose;
    private BigDecimal approximateValueInr;
}
