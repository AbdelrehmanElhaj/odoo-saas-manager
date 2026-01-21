package com.khartoum.saas.controller;

import com.khartoum.saas.dto.CreateTenantRequest;
import com.khartoum.saas.dto.TenantResponse;
import com.khartoum.saas.model.Tenant;
import com.khartoum.saas.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {
    private final TenantService tenantService;
    
    @PostMapping
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        log.info("Creating tenant: {}", request.getSubdomain());
        Tenant tenant = tenantService.createTenant(request.getSubdomain());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(tenant));
    }
    
    @GetMapping
    public ResponseEntity<List<TenantResponse>> getAllTenants() {
        return ResponseEntity.ok(tenantService.getAllTenants().stream()
            .map(this::toResponse).collect(Collectors.toList()));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getTenant(@PathVariable Long id) {
        return tenantService.getTenantById(id)
            .map(t -> ResponseEntity.ok(toResponse(t)))
            .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTenant(@PathVariable Long id) {
        tenantService.deleteTenant(id);
        return ResponseEntity.noContent().build();
    }
    
    private TenantResponse toResponse(Tenant t) {
        return new TenantResponse(t.getId(), t.getSubdomain(), 
            t.getUrl(), t.getStatus(), t.getCreatedAt());
    }
}
