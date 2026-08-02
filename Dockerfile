# syntax=docker/dockerfile:1.6

# ---- Build stage ----------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version

COPY src ./src
RUN ./gradlew clean bootJar -x test --no-daemon

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# curl for the Docker healthcheck; JRE images strip it, so install explicitly.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home /app app

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -Dspring.threads.virtual.enabled=true"

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:8080/healthz > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
