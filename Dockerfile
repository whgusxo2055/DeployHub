FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre

# skopeo는 PackageDownloadService(FN-06-1)가 ProcessBuilder로 직접 부르는 런타임 하드
# 의존이다 — 없으면 StartupChecks가 E-0605로 기동을 막는다(NCR_CLI_PATH 기본값
# /usr/bin/skopeo).
# skopeo 버전은 베이스 이미지의 Ubuntu 릴리즈를 따라간다 — 릴리즈가 바뀌면 아카이브가
# 의존하는 동작(--preserve-digests의 digest 보존, --multi-arch all의 인덱스 유지, 목적지
# 전체 참조로 index.json의 ref.name 채우기)이 조용히 갈릴 수 있어, 빌드 로그에 버전을
# 남겨 회귀를 알아챌 수 있게 한다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends skopeo \
    && rm -rf /var/lib/apt/lists/* \
    && skopeo --version

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
