package com.khartoum.saas.repository;

import com.khartoum.saas.model.Tenant;
import com.khartoum.saas.model.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {
    boolean existsBySubdomain(String subdomain);
    Optional<Tenant> findBySubdomain(String subdomain);
    List<Tenant> findByStatus(TenantStatus status);
}
