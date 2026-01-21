package com.khartoum.saas.service;

import com.khartoum.saas.model.Tenant;
import com.khartoum.saas.model.TenantStatus;
import com.khartoum.saas.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository tenantRepository;
    private final KubernetesService kubernetesService;
    private final Route53Service route53Service;
    
    @Transactional
    public Tenant createTenant(String subdomain) {
        if (tenantRepository.existsBySubdomain(subdomain)) {
            throw new IllegalArgumentException("Tenant already exists");
        }
        
        Tenant tenant = new Tenant();
        tenant.setSubdomain(subdomain);
        tenant.setDomain("42khartoum.com");
        tenant.setDatabaseName(subdomain + ".42khartoum.com");
        tenant.setUrl("https://" + subdomain + ".42khartoum.com");
        tenant.setStatus(TenantStatus.REQUESTED);
        tenant = tenantRepository.save(tenant);
        
        final Tenant finalTenant = tenant;
        new Thread(() -> provisionTenant(finalTenant)).start();
        
        return tenant;
    }
    
    private void provisionTenant(Tenant tenant) {
        try {
            updateStatus(tenant.getId(), TenantStatus.DNS_CREATING);
            route53Service.createDnsRecord(tenant.getSubdomain(), tenant.getDomain());
            
            updateStatus(tenant.getId(), TenantStatus.K8S_CREATING);
            kubernetesService.createIngress(tenant);
            kubernetesService.createCertificate(tenant);
            
            updateStatus(tenant.getId(), TenantStatus.CERT_PENDING);
            kubernetesService.waitForCertificate(tenant, 300);
            
            updateStatus(tenant.getId(), TenantStatus.DB_INITIALIZING);
            kubernetesService.initializeDatabase(tenant);
            kubernetesService.setBaseUrl(tenant);
            
            updateStatus(tenant.getId(), TenantStatus.ACTIVE);
        } catch (Exception e) {
            log.error("Failed to provision tenant", e);
            updateStatus(tenant.getId(), TenantStatus.FAILED);
        }
    }
    
    @Transactional
    public void deleteTenant(Long id) {
        Tenant tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        
        tenant.setStatus(TenantStatus.DELETING);
        tenantRepository.save(tenant);
        
        try {
            kubernetesService.deleteIngress(tenant);
            kubernetesService.deleteCertificate(tenant);
            kubernetesService.dropDatabase(tenant);
            kubernetesService.cleanupFilestore(tenant);
            route53Service.deleteDnsRecord(tenant.getSubdomain(), tenant.getDomain());
            tenant.setStatus(TenantStatus.DELETED);
            tenantRepository.save(tenant);
        } catch (Exception e) {
            log.error("Failed to delete tenant", e);
            throw new RuntimeException("Delete failed", e);
        }
    }
    
    public List<Tenant> getAllTenants() {
        return tenantRepository.findAll();
    }
    
    public Optional<Tenant> getTenantById(Long id) {
        return tenantRepository.findById(id);
    }
    
    private void updateStatus(Long id, TenantStatus status) {
        tenantRepository.findById(id).ifPresent(t -> {
            t.setStatus(status);
            tenantRepository.save(t);
        });
    }
}
