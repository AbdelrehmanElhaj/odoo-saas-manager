package com.khartoum.saas.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String subdomain;
    
    @Column(nullable = false)
    private String domain;
    
    @Column(name = "database_name", unique = true, nullable = false)
    private String databaseName;
    
    @Column(nullable = false)
    private String url;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status = TenantStatus.REQUESTED;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.status == TenantStatus.ACTIVE && this.activatedAt == null) {
            this.activatedAt = LocalDateTime.now();
        }
    }
}
