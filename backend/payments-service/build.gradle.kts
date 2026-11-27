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

val avroTools by configurations.creating

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework:spring-test")
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
    implementation("org.apache.activemq:artemis-jakarta-client:2.57.0")
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.apache.avro:avro:1.12.2")
    implementation("io.temporal:temporal-sdk:1.41.1")
    implementation("io.temporal:temporal-opentracing:1.41.1")
    implementation("io.opentelemetry:opentelemetry-opentracing-shim:1.68.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.grpc:grpc-netty-shaded:1.86.0")
    implementation("io.grpc:grpc-protobuf:1.86.0")
    implementation("io.grpc:grpc-stub:1.86.0")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    testImplementation("org.mockito:mockito-core")
    implementation("software.amazon.awssdk:s3:2.59.8")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.34.0")
    avroTools("org.apache.avro:avro-tools:1.12.2") { isTransitive = false }
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

val generateAvroJava by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate Java SpecificRecords from contracts/avro"
    classpath = avroTools
    mainClass.set("org.apache.avro.tool.Main")
    val outDir = layout.buildDirectory.dir("generated/avro/java")
    outputs.dir(outDir)
    inputs.dir(rootProject.file("contracts/avro"))
    doFirst { outDir.get().asFile.mkdirs() }
    args("compile", "schema", rootProject.file("contracts/avro").absolutePath, outDir.get().asFile.absolutePath)
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
    java.srcDir(layout.buildDirectory.dir("generated/avro/java"))
    proto.srcDir(rootProject.file("contracts/proto/fraud"))
    proto.srcDir(rootProject.file("contracts/proto/ledger"))
}

tasks.compileJava {
    dependsOn(tasks.named("openApiGenerate"), generateAvroJava)
}

tasks.jar { enabled = false }

tasks.bootJar { archiveFileName.set("application.jar") }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.86.0" }
    }
    generateProtoTasks {
        all().configureEach { plugins { maybeCreate("grpc") } }
    }
}
