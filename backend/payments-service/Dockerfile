FROM --platform=$BUILDPLATFORM maven:3.10.0-amazoncorretto-25 AS build
WORKDIR /src/backend/payments-service
COPY proto/fraud/fraud.proto /src/proto/fraud/fraud.proto
COPY backend/payments-service/pom.xml ./
COPY backend/payments-service/src ./src
RUN mvn -B -DskipTests package

FROM amazoncorretto:25-alpine@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc
WORKDIR /app
COPY --from=build /src/backend/payments-service/target/payments-service-*.jar /app/application.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/application.jar"]
