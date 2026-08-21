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
// 이 개발 환경(Windows, 최신 Docker Desktop/Engine)에서 1.20.4에 내장된 구버전 docker-java 클라이언트가
// Docker API 버전 협상에 실패해 Testcontainers가 데몬을 못 찾는 문제가 있어 2.x로 올렸다(사용자 확인 완료).
val testcontainersVersion = "2.0.5"

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
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
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
