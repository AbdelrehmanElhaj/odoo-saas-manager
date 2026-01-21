package com.khartoum.saas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;

@Configuration
public class AwsConfig {
    @Bean
    public Route53Client route53Client() {
        return Route53Client.builder()
            .region(Region.AWS_GLOBAL)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
}
