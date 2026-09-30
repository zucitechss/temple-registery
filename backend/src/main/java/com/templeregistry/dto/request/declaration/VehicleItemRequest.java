package com.templeregistry.dto.request.declaration;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for Vehicle asset items.
 * Maps to decl_mov_vehicle table.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleItemRequest extends AssetItemRequest {
    
    @NotBlank(message = "Registration number is required")
    @Size(max = 50, message = "Registration number must not exceed 50 characters")
    private String registrationNumber;
    
    @NotBlank(message = "Make / model is required")
    @Size(max = 200, message = "Make / model must not exceed 200 characters")
    private String makeModel;
    
    @NotNull(message = "Year is required")
    @Min(value = 1950, message = "Year must be after 1950")
    @Max(value = 2100, message = "Year must be before 2100")
    private Integer year;
    
    @NotBlank(message = "Purpose is required")
    @Size(max = 200, message = "Purpose must not exceed 200 characters")
    private String purpose;

    @DecimalMin(value = "0", message = "Approximate value must not be negative")
    private BigDecimal approximateValueInr;
}
