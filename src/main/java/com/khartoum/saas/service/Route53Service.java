package com.khartoum.saas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class Route53Service {

    private final Route53Client route53Client;

    @Value("${aws.route53.hosted-zone-id}")
    private String hostedZoneId;

    /**
     * Creates/Updates a DNS CNAME record pointing to the LoadBalancer DNS name.
     *
     * @param subdomain  e.g., "alice"
     * @param baseDomain e.g., "42khartoum.com"
     */
    public void createDnsRecord(String subdomain, String baseDomain) {
        String fqdn = normalizeFqdn(subdomain + "." + baseDomain);
        String lbDnsName = getLoadBalancerDnsName();

        try {
            ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                    .changes(Change.builder()
                        .action(ChangeAction.UPSERT) // ✅ idempotent
                        .resourceRecordSet(ResourceRecordSet.builder()
                            .name(fqdn)
                            .type(RRType.CNAME)
                            .ttl(300L)
                            .resourceRecords(ResourceRecord.builder()
                                .value(lbDnsName)
                                .build())
                            .build())
                        .build())
                    .build())
                .build();

            ChangeResourceRecordSetsResponse response = route53Client.changeResourceRecordSets(request);
            log.info("Upserted DNS record for {} -> {} : changeId={}", fqdn, lbDnsName, response.changeInfo().id());

            waitForDnsChange(response.changeInfo().id());

        } catch (InvalidChangeBatchException e) {
            // Common when record set is malformed or violates R53 constraints
            log.error("Invalid change batch while upserting DNS record for {} -> {}", fqdn, lbDnsName, e);
            throw new RuntimeException("Invalid Route53 change batch", e);
        } catch (Route53Exception e) {
            log.error("Route53 error while upserting DNS record for {} -> {}", fqdn, lbDnsName, e);
            throw new RuntimeException("Route53 error while creating/updating DNS record", e);
        } catch (Exception e) {
            log.error("Failed to upsert DNS record for {} -> {}", fqdn, lbDnsName, e);
            throw new RuntimeException("Failed to create/update DNS record", e);
        }
    }

    /**
     * Deletes the DNS record for a tenant (if exists).
     * Important: We delete using the *exact current record set* from Route53 to avoid InvalidChangeBatch.
     */
    public void deleteDnsRecord(String subdomain, String baseDomain) {
        String fqdn = normalizeFqdn(subdomain + "." + baseDomain);

        try {
            Optional<ResourceRecordSet> existing = findCnameRecord(fqdn);

            if (existing.isEmpty()) {
                log.info("No DNS record found for {}, nothing to delete.", fqdn);
                return;
            }

            ResourceRecordSet recordToDelete = existing.get();

            ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                    .changes(Change.builder()
                        .action(ChangeAction.DELETE)
                        .resourceRecordSet(recordToDelete)
                        .build())
                    .build())
                .build();

            ChangeResourceRecordSetsResponse response = route53Client.changeResourceRecordSets(request);
            log.info("Deleted DNS record for {} : changeId={}", fqdn, response.changeInfo().id());

            waitForDnsChange(response.changeInfo().id());

        } catch (NoSuchHostedZoneException e) {
            log.warn("Hosted zone not found: {}", hostedZoneId, e);
        } catch (InvalidChangeBatchException e) {
            // This can happen if record disappeared between list+delete (race)
            log.warn("DNS record for {} could not be deleted (possibly already removed).", fqdn, e);
        } catch (Route53Exception e) {
            log.error("Route53 error while deleting DNS record for {}", fqdn, e);
            throw new RuntimeException("Route53 error while deleting DNS record", e);
        } catch (Exception e) {
            log.error("Failed to delete DNS record for {}", fqdn, e);
            throw new RuntimeException("Failed to delete DNS record", e);
        }
    }

    /**
     * Checks if a DNS CNAME record exists.
     */
    public boolean recordExists(String subdomain, String baseDomain) {
        String fqdn = normalizeFqdn(subdomain + "." + baseDomain);

        try {
            return findCnameRecord(fqdn).isPresent();
        } catch (Exception e) {
            log.error("Failed to check DNS record existence for {}", fqdn, e);
            return false;
        }
    }

    /**
     * Finds a CNAME record for the given fully qualified domain name.
     */
    private Optional<ResourceRecordSet> findCnameRecord(String fqdn) {
        String normalized = normalizeFqdn(fqdn);

        ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
            .hostedZoneId(hostedZoneId)
            .startRecordName(normalized)
            .startRecordType(RRType.CNAME)
            .maxItems("1")
            .build();

        ListResourceRecordSetsResponse response = route53Client.listResourceRecordSets(request);

        return response.resourceRecordSets().stream()
            .filter(rrs -> normalizeFqdn(rrs.name()).equals(normalized) && rrs.type() == RRType.CNAME)
            .findFirst();
    }

    /**
     * Gets the LoadBalancer DNS name.
     * Option 1: From environment variable (recommended for now)
     */
    private String getLoadBalancerDnsName() {
        String lbDns = System.getenv("INGRESS_LB_DNS");
        if (lbDns != null && !lbDns.isBlank()) {
            return lbDns.trim();
        }
        throw new IllegalStateException(
            "LoadBalancer DNS name not configured. " +
            "Set INGRESS_LB_DNS environment variable or implement Kubernetes lookup"
        );
    }

    /**
     * Waits for a Route53 change to complete.
     */
    private void waitForDnsChange(String changeId) {
        try {
            int maxAttempts = 30;
            int attempts = 0;

            while (attempts < maxAttempts) {
                GetChangeResponse response = route53Client.getChange(GetChangeRequest.builder().id(changeId).build());

                if (response.changeInfo().status() == ChangeStatus.INSYNC) {
                    log.info("DNS change INSYNC: {}", changeId);
                    return;
                }

                log.debug("Waiting for DNS change {} (attempt {}/{})",
                    changeId, attempts + 1, maxAttempts);

                Thread.sleep(10_000);
                attempts++;
            }

            log.warn("DNS change {} did not complete within timeout", changeId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for DNS change", e);
        }
    }

    /**
     * Normalize FQDN for Route53 comparisons (ensure trailing dot).
     */
    private String normalizeFqdn(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return n;
        return n.endsWith(".") ? n : n + ".";
    }
}

