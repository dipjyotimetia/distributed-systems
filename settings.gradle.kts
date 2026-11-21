pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "google-maven-central"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "google-maven-central"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}

rootProject.name = "banking"

include(":accounts-service")
project(":accounts-service").projectDir = file("backend/accounts-service")
include(":api-gateway")
project(":api-gateway").projectDir = file("backend/api-gateway")
include(":payments-service")
project(":payments-service").projectDir = file("backend/payments-service")
include(":transactions-service")
project(":transactions-service").projectDir = file("backend/transactions-service")
