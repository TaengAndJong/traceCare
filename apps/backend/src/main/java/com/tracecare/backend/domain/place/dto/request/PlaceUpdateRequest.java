package com.tracecare.backend.domain.place.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import com.tracecare.backend.common.validation.ValidLatitude;
import com.tracecare.backend.common.validation.ValidLongitude;
import com.tracecare.backend.common.validation.ValidRadius;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class PlaceUpdateRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    private String address;

    @NotNull @ValidLatitude private Double latitude;

    @NotNull @ValidLongitude private Double longitude;

    @NotNull @ValidRadius private Integer radius;
}
