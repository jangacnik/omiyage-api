plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.jpa") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.omiyage"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

val awsSdkVersion = "2.47.3"
val testcontainersVersion = "1.21.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.apache.commons:commons-lang3:2.6.0")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("software.amazon.awssdk:s3")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:minio")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    // CI runs on a Kubernetes-executor GitLab runner with no Docker socket, so
    // Testcontainers-backed integration tests can't start there. They still run
    // locally (and anywhere Docker is available) - CI is set by GitLab in every job.
    if (System.getenv("CI") != null) {
        exclude("**/*IntegrationTest.class")
    }
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // Nothing meaningful to cover: Spring Boot entrypoint, JPA entities (getters/
                    // setters/constructors only), and plain data-carrying DTOs/config classes.
                    exclude(
                        "**/OmiyageApplication*",
                        "**/*Entity*",
                        "**/catalog/Category.class",
                        "**/catalog/InventoryItem*.class",
                        "**/order/Order.class",
                        "**/order/OrderItem.class",
                        "**/user/UserProfile.class",
                        "**/*Dto*.class",
                        "**/config/*Properties.class",
                    )
                }
            },
        ),
    )
}
