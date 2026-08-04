package com.deployhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeployHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeployHubApplication.class, args);
    }
}
