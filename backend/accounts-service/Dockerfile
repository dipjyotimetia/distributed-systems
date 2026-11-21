# Multi-stage build for Accounts Service
FROM --platform=$BUILDPLATFORM amazoncorretto:25 AS builder
RUN yum install -y findutils && yum clean all
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY scripts/docker-bootjar.sh scripts/docker-bootjar.sh
COPY backend/accounts-service/build.gradle.kts backend/accounts-service/gradle.properties backend/accounts-service/
COPY backend/api-gateway/build.gradle.kts backend/api-gateway/gradle.properties backend/api-gateway/
COPY backend/payments-service/build.gradle.kts backend/payments-service/gradle.properties backend/payments-service/
COPY backend/transactions-service/build.gradle.kts backend/transactions-service/gradle.properties backend/transactions-service/
COPY backend/accounts-service/src backend/accounts-service/src
COPY contracts/openapi/accounts.yaml contracts/openapi/accounts.yaml
COPY contracts/proto/ledger/ contracts/proto/ledger/
ARG GRADLE_CACHE_SUFFIX=0
RUN --mount=type=cache,id=gradle-accounts-service-${GRADLE_CACHE_SUFFIX},target=/root/.gradle,sharing=locked \
    sh scripts/docker-bootjar.sh :accounts-service:bootJar
RUN java -Djarmode=tools -jar backend/accounts-service/build/libs/application.jar extract --layers --destination extracted

FROM --platform=$TARGETPLATFORM amazoncorretto:25-alpine@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc

WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 spring && \
    adduser -D -s /bin/sh -u 1001 -G spring spring

# Copy layers in order of likelihood to change (dependencies first, then application)
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./

# Change ownership to non-root user
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring

# Expose port
EXPOSE 8080

# Run the application with optimized JVM flags for faster startup
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=50.0", \
    "-XX:+UseG1GC", \
    "-XX:+UseStringDeduplication", \
    "-XX:+OptimizeStringConcat", \
    "-XX:+UseCompressedOops", \
    "-XX:+UseCompressedClassPointers", \
    "-XX:+TieredCompilation", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.main.lazy-initialization=true", \
    "-Dspring.main.banner-mode=off", \
    "-Dlogging.level.org.springframework.boot.autoconfigure=WARN", \
    "-Dlogging.level.io.opentelemetry=WARN", \
    "-jar", "application.jar"]