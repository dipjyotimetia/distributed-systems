import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
    id("org.openapi.generator")
}

group = "com.banking"
version = findProperty("serviceVersion") as String

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry:opentelemetry-bom:1.68.0")
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    implementation("org.apache.activemq:artemis-jakarta-client:2.44.0")
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.temporal:temporal-sdk:1.41.1")
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    implementation("io.grpc:grpc-protobuf:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    testImplementation("org.mockito:mockito-core")
    implementation("software.amazon.awssdk:s3:2.59.4")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.34.0")
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(rootProject.file("contracts/openapi/payments.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.banking.payments.contract")
    modelPackage.set("com.banking.payments.contract.model")
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
}

tasks.compileJava {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks.jar { enabled = false }

tasks.bootJar { archiveFileName.set("application.jar") }

sourceSets.main {
    proto.srcDir(rootProject.file("proto/fraud"))
    proto.srcDir(rootProject.file("proto/ledger"))
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.85.1" }
    }
    generateProtoTasks {
        all().configureEach { plugins { maybeCreate("grpc") } }
    }
}
