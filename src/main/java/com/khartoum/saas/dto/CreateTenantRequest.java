package com.khartoum.saas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class CreateTenantRequest {
    @NotBlank(message = "Subdomain is required")
    @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
             message = "Invalid subdomain format")
    public String subdomain;
    
    public String getSubdomain() { return subdomain; }
    public void setSubdomain(String subdomain) { this.subdomain = subdomain; }
}
