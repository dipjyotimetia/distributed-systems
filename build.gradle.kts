buildscript {
    repositories {
        mavenCentral()
        maven {
            name = "google-maven-central"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:12.4.0")
        classpath("org.postgresql:postgresql:42.7.14")
    }
}

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.6" apply false
    id("org.openapi.generator") version "7.14.0" apply false
    id("org.flywaydb.flyway") version "12.4.0" apply false
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            System.getProperty("spring.profiles.active")?.let {
                systemProperty("spring.profiles.active", it)
            }
        }
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
