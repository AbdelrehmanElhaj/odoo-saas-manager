package com.khartoum.saas.dto;

import com.khartoum.saas.model.TenantStatus;
import java.time.LocalDateTime;

public class TenantResponse {
    public Long id;
    public String subdomain;
    public String url;
    public TenantStatus status;
    public LocalDateTime createdAt;
    
    public TenantResponse(Long id, String subdomain, String url, 
                          TenantStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.subdomain = subdomain;
        this.url = url;
        this.status = status;
        this.createdAt = createdAt;
    }
}
