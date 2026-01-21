package com.khartoum.saas.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class KubernetesConfig {
    @Bean
    public ApiClient kubernetesApiClient() throws Exception {
        ApiClient client = Config.defaultClient();
        client.setConnectTimeout(10_000);
        client.setReadTimeout(30_000);
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        return client;
    }
}
