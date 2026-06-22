# central-server (Kotlin/Spring Boot) — JVM 21.
# 사전 빌드 jar 복사 방식(CI: gradle build → docker build). bootJar 만 산출되도록 plain jar 비활성.
# (자체완결 멀티스테이지가 필요하면 build 스테이지에서 ./gradlew bootJar 를 돌리는 버전으로 교체)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/app.jar app.jar
# 비-root 실행(컨테이너 탈출 완화, Trivy DS-0002). UID 10001 의 시스템 계정으로 떨어뜨린다.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin nexa \
    && chown -R nexa:nexa /app
USER nexa
EXPOSE 8080
# 토큰/DB 비밀은 환경변수로 주입(DISCORD_BOT_TOKEN, DB_URL/DB_USER/DB_PASSWORD 등)
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
