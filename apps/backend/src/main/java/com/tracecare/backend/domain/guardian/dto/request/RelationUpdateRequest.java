package com.tracecare.backend.domain.guardian.dto.request;

import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class RelationUpdateRequest {

    @Size(max = 50)
    private String relation;

    @Size(max = 50)
    private String alias;
}
