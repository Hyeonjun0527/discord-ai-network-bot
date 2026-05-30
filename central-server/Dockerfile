# central-server (Kotlin/Spring Boot) — JVM 21 멀티스테이지 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
# 토큰/DB 비밀은 환경변수로 주입(DISCORD_BOT_TOKEN, DB_URL/DB_USER/DB_PASSWORD 등)
ENTRYPOINT ["java", "-jar", "app.jar"]
