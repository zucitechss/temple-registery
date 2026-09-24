package com.templeregistry.controller.admin;

import com.templeregistry.common.ApiResponse;
import com.templeregistry.dto.request.admin.UpdateSystemConfigRequest;
import com.templeregistry.dto.response.admin.SystemConfigResponse;
import com.templeregistry.security.RoleConstants;
import com.templeregistry.security.ScopeHelper;
import com.templeregistry.service.admin.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/config")
@RequiredArgsConstructor
@Tag(name = "System Config", description = "SUPER_ADMIN: SLA, notification, and feature flag configuration")
@PreAuthorize(RoleConstants.ADMIN_ONLY)
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping
    @Operation(summary = "List all system configuration entries")
    public ResponseEntity<ApiResponse<List<SystemConfigResponse>>> listAll(
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(ApiResponse.success("System config retrieved.",
                systemConfigService.listAll(category)));
    }

    @GetMapping("/{key}")
    @Operation(summary = "Get a single config entry by key")
    public ResponseEntity<ApiResponse<SystemConfigResponse>> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.success("Config retrieved.",
                systemConfigService.getByKey(key)));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Update a config entry value")
    public ResponseEntity<ApiResponse<SystemConfigResponse>> update(
            @PathVariable String key,
            @Valid @RequestBody UpdateSystemConfigRequest rq,
            @AuthenticationPrincipal ScopeHelper.Claims claims) {
        if (claims == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Unauthorized: Invalid or missing authentication token.", "UNAUTHORIZED"));
        }
        return ResponseEntity.ok(ApiResponse.success("Config updated.",
                systemConfigService.update(key, rq, claims.userId())));
    }
}

