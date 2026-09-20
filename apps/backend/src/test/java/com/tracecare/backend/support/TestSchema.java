package com.tracecare.backend.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Testcontainers 통합 테스트가 공통으로 적용하는 최신 DDL의 단일 참조점. 스키마 버전이 올라가면 {@link #LATEST_DDL}만 바꾸면 되므로 테스트
 * 클래스마다 파일 경로를 하드코딩하지 않는다(엔티티가 최신 스키마를 전제로 {@code ddl-auto: validate}를 쓰므로 DDL이 뒤처지면
 * 컨텍스트 기동이 실패한다).
 *
 * <p>Gradle 테스트의 작업 디렉터리는 {@code apps/backend}이므로 레포 루트의 {@code docs/db}는 {@code ../../}로 접근한다.
 */
public final class TestSchema {

    public static final Path LATEST_DDL =
            Paths.get("../../docs/db/tracecare_schema_ddl_2026-09-20_1.3.sql");

    private TestSchema() {}

    public static String readLatestDdl() throws IOException {
        return Files.readString(LATEST_DDL);
    }
}
