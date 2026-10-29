package com.philips.ecommerce.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressDto {
    @NotBlank
    @Size(max = 200)
    private String fullName;
    @NotBlank
    @Size(max = 255)
    private String line1;
    @NotBlank
    @Size(max = 100)
    private String city;
    @NotBlank
    @Size(max = 20)
    private String postalCode;
    @NotBlank
    @Size(max = 100)
    private String country;
}
