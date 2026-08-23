package com.tracecare.backend.domain.place.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageImpl;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tracecare.backend.common.response.ApiResponse;
import com.tracecare.backend.common.response.PageResponse;
import com.tracecare.backend.common.response.SuccessCode;
import com.tracecare.backend.common.security.SecurityUtils;
import com.tracecare.backend.domain.place.dto.request.PlaceCreateRequest;
import com.tracecare.backend.domain.place.dto.request.PlaceUpdateRequest;
import com.tracecare.backend.domain.place.dto.response.PlaceResponse;
import com.tracecare.backend.domain.place.service.PlaceService;

/** API_Specification.md §3.2. */
@RestController
@RequestMapping("/api/guardian/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping
    public ApiResponse<PageResponse<PlaceResponse>> getPlaces(@RequestParam UUID careTargetId) {
        List<PlaceResponse> places =
                placeService.getPlaces(SecurityUtils.getCurrentUserId(), careTargetId);
        return ApiResponse.success(SuccessCode.PLACE_001, PageResponse.of(new PageImpl<>(places)));
    }

    @PostMapping
    public ApiResponse<PlaceResponse> createPlace(@Valid @RequestBody PlaceCreateRequest request) {
        return ApiResponse.success(
                SuccessCode.PLACE_001,
                placeService.createPlace(SecurityUtils.getCurrentUserId(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PlaceResponse> updatePlace(
            @PathVariable UUID id, @Valid @RequestBody PlaceUpdateRequest request) {
        return ApiResponse.success(
                SuccessCode.PLACE_002,
                placeService.updatePlace(SecurityUtils.getCurrentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePlace(@PathVariable UUID id) {
        placeService.deletePlace(SecurityUtils.getCurrentUserId(), id);
        return ApiResponse.success(SuccessCode.PLACE_003);
    }
}
