package com.deployhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling}의 유일한 사용처는 보존·정리 배치다. Boot가 별도 {@code TaskScheduler}를
 * 만들어 주므로 {@code AsyncConfig}의 Job 전용 풀과 섞이지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DeployHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeployHubApplication.class, args);
    }
}
