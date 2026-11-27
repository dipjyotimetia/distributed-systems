import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.flywaydb.flyway")
    id("org.openapi.generator")
    id("com.google.protobuf")
}

group = "com.banking"
version = findProperty("serviceVersion") as String

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry:opentelemetry-bom:1.68.0")
        mavenBom("io.grpc:grpc-bom:1.86.0")
        mavenBom("software.amazon.awssdk:bom:2.59.8")
    }
    dependencies {
        dependency("com.google.guava:guava:33.8.0-android")
    }
}

dependencies {
    implementation("io.temporal:temporal-sdk:1.41.1")
    implementation("io.temporal:temporal-opentracing:1.41.1")
    implementation("io.opentelemetry:opentelemetry-opentracing-shim:1.68.0")
    testImplementation("io.temporal:temporal-testing:1.41.1")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("io.grpc:grpc-netty:1.86.0")
    implementation("io.grpc:grpc-protobuf:1.86.0")
    implementation("io.grpc:grpc-stub:1.86.0")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("software.amazon.awssdk:s3")
    implementation("org.springframework.boot:spring-boot-jackson2")
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
    implementation("io.opentelemetry.instrumentation:opentelemetry-grpc-1.6:2.34.0-alpha")
    implementation("io.micrometer:micrometer-registry-prometheus")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(rootProject.file("contracts/openapi/accounts.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.banking.accountsservice.contract")
    modelPackage.set("com.banking.accountsservice.contract.model")
    schemaMappings.set(mapOf("AccountListOrPage" to "java.lang.Object"))
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "true",
        "openApiNullable" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "useBeanValidation" to "false",
        "dateLibrary" to "java8",
        "hideGenerationTimestamp" to "true",
        "useResponseEntity" to "true",
        "requestMappingMode" to "api_interface",
    ))
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
    proto.srcDir(rootProject.file("contracts/proto/ledger"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.86.0" }
    }
    generateProtoTasks {
        all().configureEach { plugins { maybeCreate("grpc") } }
    }
}

tasks.compileJava {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks.jar { enabled = false }

tasks.bootJar { archiveFileName.set("application.jar") }

flyway {
    url = providers.environmentVariable("FLYWAY_URL").getOrElse("jdbc:postgresql://localhost:5432/banking_app")
    user = providers.environmentVariable("FLYWAY_USER").getOrElse("accounts_service_user")
    password = providers.environmentVariable("FLYWAY_PASSWORD").orNull
    schemas = arrayOf("accounts_service")
    locations = arrayOf("filesystem:${projectDir}/src/main/resources/db/migration")
}
