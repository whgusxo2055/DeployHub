# ponytail: skopeo 설치는 Phase 4(FN-06-1)에서 실제 사용 시점에 추가한다.
# 지금 넣어봐야 아무도 호출하지 않는 바이너리만 이미지에 얹는 셈이라 뒤로 미룬다.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
