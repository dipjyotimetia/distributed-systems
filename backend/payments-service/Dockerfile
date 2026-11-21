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
COPY backend/payments-service/src backend/payments-service/src
COPY contracts/openapi/payments.yaml contracts/openapi/payments.yaml
COPY contracts/proto/fraud/ contracts/proto/fraud/
COPY contracts/proto/ledger/ contracts/proto/ledger/
ARG GRADLE_CACHE_SUFFIX=0
RUN --mount=type=cache,id=gradle-payments-service-${GRADLE_CACHE_SUFFIX},target=/root/.gradle,sharing=locked \
    sh scripts/docker-bootjar.sh :payments-service:bootJar

FROM amazoncorretto:25-alpine@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc
WORKDIR /app
COPY --from=builder /app/backend/payments-service/build/libs/application.jar /app/application.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/application.jar"]
