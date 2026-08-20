import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.tracecare"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val jjwtVersion = "0.12.6"
val googleApiClientVersion = "2.9.0"
val googleHttpClientGsonVersion = "2.1.0"
// Spring Boot 3.3.4 기준 안정 검증된 1.x 라인 고정(2.0.x는 org.testcontainers.containers.PostgreSQLContainer
// 패키지 이동 등 breaking change가 있고 Spring Boot 쪽 대응이 아직 진행 중이라 3.3.4엔 맞지 않는다고 판단).
val testcontainersVersion = "1.20.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    runtimeOnly("org.postgresql:postgresql")

    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    implementation("com.google.api-client:google-api-client:$googleApiClientVersion")
    implementation("com.google.http-client:google-http-client-gson:$googleHttpClientGsonVersion")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    minHeapSize = "128m"
    maxHeapSize = "256m"
    jvmArgs("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1", "-XX:ReservedCodeCacheSize=32m", "-Xshare:off")
}

tasks.withType<BootJar> {
    archiveFileName.set("tracecare-backend.jar")
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.23.0").aosp()
        removeUnusedImports()
        importOrder("java", "javax|jakarta", "", "com.tracecare.backend")
        indentWithSpaces(4)
    }
}
