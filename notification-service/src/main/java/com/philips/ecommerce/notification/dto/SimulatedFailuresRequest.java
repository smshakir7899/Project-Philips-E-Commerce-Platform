package com.philips.ecommerce.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.List;

/** PUT body: the complete new set of customers whose notifications must fail (empty list = disable). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulatedFailuresRequest {

    @NotNull
    @Size(max = 100)
    private List<@NotNull @Pattern(regexp = "^[A-Za-z0-9-]{3,64}$") String> customerIds;
}
