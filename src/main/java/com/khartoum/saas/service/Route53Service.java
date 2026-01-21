package com.khartoum.saas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class Route53Service {
    
    private final Route53Client route53Client;
    
    @Value("${aws.route53.hosted-zone-id}")
    private String hostedZoneId;
    
    /**
     * Creates a DNS A record (ALIAS) pointing to the LoadBalancer
     * 
     * @param subdomain e.g., "alice"
     * @param baseDomain e.g., "42khartoum.com"
     */
    public void createDnsRecord(String subdomain, String baseDomain) {
        String fqdn = subdomain + "." + baseDomain;
        
        try {
            // Get the LoadBalancer DNS name from ingress-nginx service
            String lbDnsName = getLoadBalancerDnsName();
            
            ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                    .changes(Change.builder()
                        .action(ChangeAction.CREATE)
                        .resourceRecordSet(ResourceRecordSet.builder()
                            .name(fqdn)
                            .type(RRType.CNAME)
                            .ttl(300L)
                            .resourceRecords(ResourceRecord.builder()
                                .value(lbDnsName)
                                .build()
                            )
                            .build()
                        )
                        .build()
                    )
                    .build()
                )
                .build();
            
            ChangeResourceRecordSetsResponse response = route53Client.changeResourceRecordSets(request);
            log.info("Created DNS record for {}: changeId={}", fqdn, response.changeInfo().id());
            
            // Wait for change to propagate
            waitForDnsChange(response.changeInfo().id());
            
        } catch (ResourceRecordSetAlreadyExistsException e) {
            log.warn("DNS record already exists for {}", fqdn);
        } catch (Exception e) {
            log.error("Failed to create DNS record for {}", fqdn, e);
            throw new RuntimeException("Failed to create DNS record", e);
        }
    }
    
    /**
     * Deletes the DNS record for a tenant
     */
    public void deleteDnsRecord(String subdomain, String baseDomain) {
        String fqdn = subdomain + "." + baseDomain;
        
        try {
            // First, get the existing record to know what to delete
            String lbDnsName = getLoadBalancerDnsName();
            
            ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                    .changes(Change.builder()
                        .action(ChangeAction.DELETE)
                        .resourceRecordSet(ResourceRecordSet.builder()
                            .name(fqdn)
                            .type(RRType.CNAME)
                            .ttl(300L)
                            .resourceRecords(ResourceRecord.builder()
                                .value(lbDnsName)
                                .build()
                            )
                            .build()
                        )
                        .build()
                    )
                    .build()
                )
                .build();
            
            route53Client.changeResourceRecordSets(request);
            log.info("Deleted DNS record for {}", fqdn);
            
        } catch (NoSuchHostedZoneException | InvalidChangeBatchException e) {
            log.warn("DNS record not found for {}", fqdn);
        } catch (Exception e) {
            log.error("Failed to delete DNS record for {}", fqdn, e);
            throw new RuntimeException("Failed to delete DNS record", e);
        }
    }
    
    /**
     * Gets the LoadBalancer DNS name from the ingress-nginx service
     * In a real implementation, you'd query Kubernetes for this
     * For now, it can be configured via environment variable
     */
    private String getLoadBalancerDnsName() {
        // Option 1: From environment variable
        String lbDns = System.getenv("INGRESS_LB_DNS");
        if (lbDns != null && !lbDns.isEmpty()) {
            return lbDns;
        }
        
        // Option 2: Query Kubernetes (requires additional implementation)
        // This would call the Kubernetes API to get the LoadBalancer service
        
        // Fallback: throw error
        throw new IllegalStateException(
            "LoadBalancer DNS name not configured. " +
            "Set INGRESS_LB_DNS environment variable or implement Kubernetes lookup"
        );
    }
    
    /**
     * Waits for a Route53 change to complete
     */
    private void waitForDnsChange(String changeId) {
        try {
            int maxAttempts = 30;
            int attempts = 0;
            
            while (attempts < maxAttempts) {
                GetChangeRequest request = GetChangeRequest.builder()
                    .id(changeId)
                    .build();
                
                GetChangeResponse response = route53Client.getChange(request);
                
                if (response.changeInfo().status() == ChangeStatus.INSYNC) {
                    log.info("DNS change completed: {}", changeId);
                    return;
                }
                
                log.debug("Waiting for DNS change {} (attempt {}/{})", 
                    changeId, attempts + 1, maxAttempts);
                
                Thread.sleep(10000); // Wait 10 seconds
                attempts++;
            }
            
            log.warn("DNS change {} did not complete within timeout", changeId);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for DNS change", e);
        }
    }
    
    /**
     * Checks if a DNS record exists
     */
    public boolean recordExists(String subdomain, String baseDomain) {
        String fqdn = subdomain + "." + baseDomain + ".";
        
        try {
            ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .startRecordName(fqdn)
                .startRecordType(RRType.CNAME)
                .maxItems("1")
                .build();
            
            ListResourceRecordSetsResponse response = route53Client.listResourceRecordSets(request);
            
            return response.resourceRecordSets().stream()
                .anyMatch(rrs -> rrs.name().equals(fqdn) && rrs.type() == RRType.CNAME);
                
        } catch (Exception e) {
            log.error("Failed to check DNS record for {}", fqdn, e);
            return false;
        }
    }
}
