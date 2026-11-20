import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
    id("org.flywaydb.flyway")
}

group = "com.banking"
version = findProperty("serviceVersion") as String

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry:opentelemetry-bom:1.68.0")
    }
    dependencies {
        dependency("com.google.guava:guava:33.8.0-android")
        dependency("at.yawk.lz4:lz4-java:1.14.1")
        dependency("org.xerial.snappy:snappy-java:1.1.10.12")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    implementation("io.opentelemetry:opentelemetry-api:1.68.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.34.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.68.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-jdbc:2.34.0-alpha")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-grpc-client")
    implementation("io.grpc:grpc-protobuf:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

tasks.jar { enabled = false }

tasks.bootJar { archiveFileName.set("application.jar") }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.85.1" }
    }
    generateProtoTasks {
        all().configureEach { plugins { maybeCreate("grpc") } }
    }
}

flyway {
    url = providers.environmentVariable("FLYWAY_URL").getOrElse("jdbc:postgresql://localhost:5432/banking_app")
    user = providers.environmentVariable("FLYWAY_USER").getOrElse("transactions_service_user")
    password = providers.environmentVariable("FLYWAY_PASSWORD").orNull
    schemas = arrayOf("transactions_service")
    locations = arrayOf("filesystem:${projectDir}/src/main/resources/db/migration")
}
