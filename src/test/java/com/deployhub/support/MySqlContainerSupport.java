package com.deployhub.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트 공통 베이스 (구현계획서 Phase 3-E1). 컨테이너를 JVM 종료까지 살아있는
 * 싱글턴으로 띄운다 — {@code @Testcontainers}/{@code @Container}를 쓰면 각 테스트
 * 클래스의 afterAll에서 컨테이너를 멈추는데, Spring 테스트 컨텍스트 캐시는 JVM 종료까지
 * 살아남는다. 같은 설정(@ServiceConnection 빈 구성)을 쓰는 두 번째 통합 테스트 클래스가
 * 생기면 죽은 컨테이너를 가리키는 HikariPool을 재사용하려다 커넥션 실패로 터진다.
 *
 * <p>H2가 아닌 이유는 스키마가 {@code utf8mb4_0900_ai_ci}·{@code ON UPDATE CURRENT_TIMESTAMP}
 * 등 MySQL 전용 기능을 쓰기 때문이다(V1__init_schema.sql). {@code dev} 프로필로
 * StartupChecks·NCR/Graph 호출을 끈다 — 이 프로필의 계약이 바뀌면(ddl-auto, flyway 설정 등)
 * 이 클래스를 상속하는 모든 테스트가 영향을 받는다.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class MySqlContainerSupport {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            // 운영 JDBC URL(application.yml)과 동일한 파라미터라야 utf8mb4 왕복을 검증하는
            // 의미가 있다.
            .withUrlParam("characterEncoding", "UTF-8")
            .withUrlParam("serverTimezone", "Asia/Seoul");

    static {
        MYSQL.start();
    }
}
