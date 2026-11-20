# Multi-stage build for Transactions Service.
# The build context is the repository root so the shared fraud protobuf is available.
FROM --platform=$BUILDPLATFORM maven:3.10.0-amazoncorretto-25 AS builder

WORKDIR /src/backend/transactions-service

COPY proto/fraud/fraud.proto /src/proto/fraud/fraud.proto
COPY backend/transactions-service/pom.xml ./
RUN mvn -B dependency:go-offline

COPY backend/transactions-service/src ./src
RUN mvn -B clean package -DskipTests

RUN cp target/transactions-service-*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime image - use Amazon Corretto Alpine for Colima compatibility
# Pin the Corretto 25 image index for reproducible multi-platform builds.
FROM --platform=$TARGETPLATFORM amazoncorretto:25-alpine@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc

WORKDIR /app

RUN addgroup -g 1001 spring && \
    adduser -D -s /bin/sh -u 1001 -G spring spring

COPY --from=builder /src/backend/transactions-service/extracted/dependencies/ ./
COPY --from=builder /src/backend/transactions-service/extracted/spring-boot-loader/ ./
COPY --from=builder /src/backend/transactions-service/extracted/snapshot-dependencies/ ./
COPY --from=builder /src/backend/transactions-service/extracted/application/ ./

RUN chown -R spring:spring /app

USER spring

EXPOSE 8080

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
