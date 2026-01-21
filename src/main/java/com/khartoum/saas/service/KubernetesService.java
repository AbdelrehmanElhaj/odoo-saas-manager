package com.khartoum.saas.service;

import com.khartoum.saas.model.Tenant;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesService {
    
    private final ApiClient apiClient;
    
    @Value("${kubernetes.namespace}")
    private String namespace;
    
    @Value("${odoo.image}")
    private String odooImage;
    
    @Value("${odoo.postgres-host}")
    private String postgresHost;
    
    @Value("${odoo.postgres-port}")
    private int postgresPort;
    
    @Value("${odoo.base-domain}")
    private String baseDomain;
    
    // ==================== INGRESS ====================
    
    public void createIngress(Tenant tenant) throws ApiException {
        NetworkingV1Api api = new NetworkingV1Api(apiClient);
        String ingressName = "odoo-tenant-" + tenant.getSubdomain();
        String hostname = tenant.getSubdomain() + "." + baseDomain;
        
        V1Ingress ingress = new V1Ingress()
            .metadata(new V1ObjectMeta()
                .name(ingressName)
                .namespace(namespace)
                .annotations(Map.of(
                    "kubernetes.io/ingress.class", "nginx",
                    "cert-manager.io/cluster-issuer", "letsencrypt-prod"
                ))
            )
            .spec(new V1IngressSpec()
                .tls(List.of(new V1IngressTLS()
                    .hosts(List.of(hostname))
                    .secretName("odoo-tls-" + tenant.getSubdomain())
                ))
                .rules(List.of(new V1IngressRule()
                    .host(hostname)
                    .http(new V1HTTPIngressRuleValue()
                        .paths(List.of(new V1HTTPIngressPath()
                            .path("/")
                            .pathType("Prefix")
                            .backend(new V1IngressBackend()
                                .service(new V1IngressServiceBackend()
                                    .name("odoo")
                                    .port(new V1ServiceBackendPort().number(8069))
                                )
                            )
                        ))
                    )
                ))
            );
        
        try {
            api.createNamespacedIngress(namespace, ingress, null, null, null, null);
            log.info("Created ingress for tenant: {}", tenant.getSubdomain());
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                log.warn("Ingress already exists for tenant: {}", tenant.getSubdomain());
            } else {
                throw e;
            }
        }
    }
    
    public void deleteIngress(Tenant tenant) throws ApiException {
        NetworkingV1Api api = new NetworkingV1Api(apiClient);
        String ingressName = "odoo-tenant-" + tenant.getSubdomain();
        
        try {
            api.deleteNamespacedIngress(ingressName, namespace, null, null, null, null, null, null);
            log.info("Deleted ingress for tenant: {}", tenant.getSubdomain());
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Ingress not found for tenant: {}", tenant.getSubdomain());
            } else {
                throw e;
            }
        }
    }
    
    // ==================== CERTIFICATE ====================
    
    public void createCertificate(Tenant tenant) throws ApiException {
        CoreV1Api api = new CoreV1Api(apiClient);
        String certName = "odoo-cert-" + tenant.getSubdomain();
        String hostname = tenant.getSubdomain() + "." + baseDomain;
        
        // Certificate is a CRD - we'll create it via kubectl equivalent
        String certYaml = String.format("""
            apiVersion: cert-manager.io/v1
            kind: Certificate
            metadata:
              name: %s
              namespace: %s
            spec:
              secretName: odoo-tls-%s
              issuerRef:
                name: letsencrypt-prod
                kind: ClusterIssuer
              dnsNames:
                - %s
            """, certName, namespace, tenant.getSubdomain(), hostname);
        
        // Apply via ConfigMap (workaround for CRD)
        V1ConfigMap configMap = new V1ConfigMap()
            .metadata(new V1ObjectMeta()
                .name(certName + "-manifest")
                .namespace(namespace)
            )
            .data(Map.of("certificate.yaml", certYaml));
        
        try {
            api.createNamespacedConfigMap(namespace, configMap, null, null, null, null);
            log.info("Created certificate manifest for tenant: {}", tenant.getSubdomain());
        } catch (ApiException e) {
            if (e.getCode() != 409) throw e;
        }
    }
    
    public void deleteCertificate(Tenant tenant) throws ApiException {
        CoreV1Api api = new CoreV1Api(apiClient);
        String certName = "odoo-cert-" + tenant.getSubdomain();
        
        try {
            api.deleteNamespacedConfigMap(certName + "-manifest", namespace, null, null, null, null, null, null);
            log.info("Deleted certificate for tenant: {}", tenant.getSubdomain());
        } catch (ApiException e) {
            if (e.getCode() != 404) throw e;
        }
    }
    
    public void waitForCertificate(Tenant tenant, int timeoutSeconds) throws InterruptedException {
        log.info("Waiting for certificate to be ready for tenant: {}", tenant.getSubdomain());
        
        int elapsed = 0;
        while (elapsed < timeoutSeconds) {
            // Check if TLS secret exists
            try {
                CoreV1Api api = new CoreV1Api(apiClient);
                api.readNamespacedSecret("odoo-tls-" + tenant.getSubdomain(), namespace, null);
                log.info("Certificate ready for tenant: {}", tenant.getSubdomain());
                return;
            } catch (ApiException e) {
                if (e.getCode() != 404) {
                    log.error("Error checking certificate: {}", e.getMessage());
                }
            }
            
            Thread.sleep(5000);
            elapsed += 5;
        }
        
        throw new RuntimeException("Certificate not ready after " + timeoutSeconds + " seconds");
    }
    
    // ==================== DATABASE OPERATIONS ====================
    
    public void initializeDatabase(Tenant tenant) throws ApiException, InterruptedException {
        BatchV1Api api = new BatchV1Api(apiClient);
        String jobName = "odoo-init-db-" + tenant.getSubdomain();
        
        V1Job job = new V1Job()
            .metadata(new V1ObjectMeta()
                .name(jobName)
                .namespace(namespace)
            )
            .spec(new V1JobSpec()
                .ttlSecondsAfterFinished(3600)
                .template(new V1PodTemplateSpec()
                    .spec(new V1PodSpec()
                        .restartPolicy("Never")
                        .containers(List.of(new V1Container()
                            .name("odoo-init")
                            .image(odooImage)
                            .command(List.of(
                                "odoo",
                                "-d", tenant.getDatabaseName(),
                                "-i", "base",
                                "--stop-after-init",
                                "--without-demo=all",
                                "--db_host=" + postgresHost,
                                "--db_port=" + postgresPort
                            ))
                            .env(List.of(
                                new V1EnvVar().name("POSTGRES_PASSWORD").valueFrom(
                                    new V1EnvVarSource().secretKeyRef(
                                        new V1SecretKeySelector()
                                            .name("postgres-secret")
                                            .key("password")
                                    )
                                )
                            ))
                        ))
                    )
                )
            );
        
        try {
            api.createNamespacedJob(namespace, job, null, null, null, null);
            log.info("Created DB init job for tenant: {}", tenant.getSubdomain());
            waitForJob(jobName, 600);
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                log.warn("Job already exists: {}", jobName);
            } else {
                throw e;
            }
        }
    }
    
    public void setBaseUrl(Tenant tenant) throws ApiException, InterruptedException {
        BatchV1Api api = new BatchV1Api(apiClient);
        String jobName = "odoo-set-baseurl-" + tenant.getSubdomain();
        
        String pythonScript = String.format("""
            import odoo
            from odoo import api, SUPERUSER_ID
            
            odoo.tools.config['db_host'] = '%s'
            odoo.tools.config['db_port'] = %d
            
            with odoo.api.Environment.manage():
                with odoo.registry('%s').cursor() as cr:
                    env = api.Environment(cr, SUPERUSER_ID, {})
                    param = env['ir.config_parameter']
                    param.set_param('web.base.url', '%s')
                    param.set_param('web.base.url.freeze', True)
                    cr.commit()
            """, postgresHost, postgresPort, tenant.getDatabaseName(), tenant.getUrl());
        
        V1Job job = new V1Job()
            .metadata(new V1ObjectMeta()
                .name(jobName)
                .namespace(namespace)
            )
            .spec(new V1JobSpec()
                .ttlSecondsAfterFinished(3600)
                .template(new V1PodTemplateSpec()
                    .spec(new V1PodSpec()
                        .restartPolicy("Never")
                        .containers(List.of(new V1Container()
                            .name("set-baseurl")
                            .image(odooImage)
                            .command(List.of("python3", "-c", pythonScript))
                        ))
                    )
                )
            );
        
        try {
            api.createNamespacedJob(namespace, job, null, null, null, null);
            log.info("Created base URL job for tenant: {}", tenant.getSubdomain());
            waitForJob(jobName, 300);
        } catch (ApiException e) {
            if (e.getCode() != 409) throw e;
        }
    }
    
    public void dropDatabase(Tenant tenant) {
        try {
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/postgres", postgresHost, postgresPort);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "odoo", System.getenv("POSTGRES_PASSWORD"));
                 Statement stmt = conn.createStatement()) {
                
                stmt.execute("DROP DATABASE IF EXISTS \"" + tenant.getDatabaseName() + "\"");
                log.info("Dropped database for tenant: {}", tenant.getSubdomain());
            }
        } catch (Exception e) {
            log.error("Failed to drop database for tenant: {}", tenant.getSubdomain(), e);
        }
    }
    
    public void cleanupFilestore(Tenant tenant) throws ApiException, InterruptedException {
        BatchV1Api api = new BatchV1Api(apiClient);
        String jobName = "cleanup-filestore-" + tenant.getSubdomain();
        
        V1Job job = new V1Job()
            .metadata(new V1ObjectMeta()
                .name(jobName)
                .namespace(namespace)
            )
            .spec(new V1JobSpec()
                .ttlSecondsAfterFinished(600)
                .template(new V1PodTemplateSpec()
                    .spec(new V1PodSpec()
                        .restartPolicy("Never")
                        .containers(List.of(new V1Container()
                            .name("cleanup")
                            .image("busybox")
                            .command(List.of("sh", "-c", 
                                "rm -rf /var/lib/odoo/filestore/" + tenant.getDatabaseName()))
                            .volumeMounts(List.of(new V1VolumeMount()
                                .name("odoo-data")
                                .mountPath("/var/lib/odoo")
                            ))
                        ))
                        .volumes(List.of(new V1Volume()
                            .name("odoo-data")
                            .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                                .claimName("odoo-data")
                            )
                        ))
                    )
                )
            );
        
        try {
            api.createNamespacedJob(namespace, job, null, null, null, null);
            log.info("Created filestore cleanup job for tenant: {}", tenant.getSubdomain());
            waitForJob(jobName, 60);
        } catch (ApiException e) {
            if (e.getCode() != 409) throw e;
        }
    }
    
    private void waitForJob(String jobName, int timeoutSeconds) throws ApiException, InterruptedException {
        BatchV1Api api = new BatchV1Api(apiClient);
        int elapsed = 0;
        
        while (elapsed < timeoutSeconds) {
            V1Job job = api.readNamespacedJobStatus(jobName, namespace, null);
            V1JobStatus status = job.getStatus();
            
            if (status != null && status.getSucceeded() != null && status.getSucceeded() > 0) {
                log.info("Job completed successfully: {}", jobName);
                return;
            }
            
            if (status != null && status.getFailed() != null && status.getFailed() > 0) {
                throw new RuntimeException("Job failed: " + jobName);
            }
            
            Thread.sleep(5000);
            elapsed += 5;
        }
        
        throw new RuntimeException("Job timeout: " + jobName);
    }
}
