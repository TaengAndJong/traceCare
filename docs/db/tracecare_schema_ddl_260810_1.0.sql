-- =====================================================================
-- TraceCare 프로젝트 — PostgreSQL DDL
-- 기준 문서: TraceCare_DB_Design_Final.md v6.0 (최종 확정본)
-- 대상 버전: PostgreSQL 15 이상 (검증 환경: PostgreSQL 16.14 + pgvector 0.6.0)
-- 생성 원칙: 설계 가이드에 정의되지 않은 내용은 임의로 추가하지 않으며,
--            문법상 불가피한 구현 세부사항은 가장 보수적인 방식으로 적용하고
--            본문 하단 [DDL 실행 순서 설명] 및 채팅 응답의 [결정 필요] 절에서 별도 설명한다.
-- =====================================================================


-- =====================================================================
-- 01. EXTENSION
-- =====================================================================
-- pgvector: ChatEmbedding.embedding 컬럼 및 HNSW 인덱스용 (§11.1 확정)
CREATE EXTENSION IF NOT EXISTS vector;

-- gen_random_uuid()는 PostgreSQL 13+ core 내장 함수이므로
-- pgcrypto 확장은 별도로 설치하지 않는다 (§11.1 확정 — 대상 버전 15+).


-- =====================================================================
-- 02. TABLE CREATION
-- =====================================================================
-- 생성 순서: FK로 참조되는 부모 테이블을 먼저 생성한다.
-- FK 제약 자체는 03. Constraint 절에서 ALTER TABLE로 일괄 추가한다
-- (순환 참조는 없으나, 테이블 생성과 관계 제약을 분리해 가독성과 재실행 안전성을 확보).

-- ---------------------------------------------------------------------
-- 02-1. User (Master Data)
-- ---------------------------------------------------------------------
CREATE TABLE "User" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    public_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    oauth_provider  VARCHAR(20) NOT NULL DEFAULT 'GOOGLE',
    oauth_id        VARCHAR(255) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(20) NOT NULL,
    profile_image   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_user PRIMARY KEY (id),
    CONSTRAINT uk_user_public_id UNIQUE (public_id),
    CONSTRAINT uq_user_oauth UNIQUE (oauth_provider, oauth_id),
    CONSTRAINT ck_user_role CHECK (role IN ('ADMIN', 'GUARDIAN', 'CARE_TARGET'))
);

COMMENT ON TABLE "User" IS '전체 사용자(Guardian/CareTarget/Admin) 통합 관리 — Role 컬럼으로 구분하는 Single Table 설계(§3.1)';

-- ---------------------------------------------------------------------
-- 02-2. GuardianTarget (Master Data) — Guardian-CareTarget M:N 관계 해소
-- ---------------------------------------------------------------------
CREATE TABLE "GuardianTarget" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    guardian_id     BIGINT NOT NULL,
    target_id       BIGINT NOT NULL,
    guardian_role   VARCHAR(10) NOT NULL DEFAULT 'SUB',
    relation        VARCHAR(50),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminated_at   TIMESTAMPTZ,

    CONSTRAINT pk_gt PRIMARY KEY (id),
    CONSTRAINT ck_gt_no_self CHECK (guardian_id <> target_id),
    CONSTRAINT ck_gt_status CHECK (status IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT ck_gt_role CHECK (guardian_role IN ('PRIMARY', 'SUB'))
);

COMMENT ON TABLE "GuardianTarget" IS 'Guardian-CareTarget M:N 관계 해소 테이블. 관계는 초대(Invitation)+CareTarget 승인 절차로만 생성되며 Invitation 자체는 Redis TTL 토큰으로 관리(PostgreSQL 테이블 없음, §3.2)';

-- ---------------------------------------------------------------------
-- 02-3. Place (Master Data) — Guardian이 등록하는 GeoFence(안심구역)
-- ---------------------------------------------------------------------
CREATE TABLE "Place" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id         BIGINT NOT NULL,
    name            VARCHAR(150) NOT NULL,
    address         TEXT,
    latitude        NUMERIC(10, 7) NOT NULL,
    longitude       NUMERIC(11, 7) NOT NULL,
    radius          INT NOT NULL DEFAULT 100,
    version         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT pk_place PRIMARY KEY (id),
    CONSTRAINT ck_place_lat CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_place_lng CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_place_radius CHECK (radius > 0)
);

COMMENT ON TABLE "Place" IS 'Guardian이 등록하는 GeoFence(안심구역). 등록·수정·삭제는 ACTIVE PRIMARY Guardian만 가능(애플리케이션/Service 계층 검증, §3.3)';

-- ---------------------------------------------------------------------
-- 02-4. LocationHistory (Time-Series, 파티셔닝 필수) — CareTarget GPS 원본 위치
-- ---------------------------------------------------------------------
-- PK가 (id, recorded_at) 복합키인 이유: PostgreSQL 선언적 파티셔닝은
-- 파티션 키 컬럼이 PK/UNIQUE에 포함되어야 하는 제약이 있기 때문(§5).
CREATE TABLE "LocationHistory" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id         BIGINT NOT NULL,
    latitude        NUMERIC(10, 7) NOT NULL,
    longitude       NUMERIC(11, 7) NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_lh PRIMARY KEY (id, recorded_at),
    CONSTRAINT ck_lh_lat CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_lh_lng CHECK (longitude BETWEEN -180 AND 180)
) PARTITION BY RANGE (recorded_at);

COMMENT ON TABLE "LocationHistory" IS 'CareTarget GPS 원본 위치. 월 단위 Range Partitioning(recorded_at 기준), User FK는 RESTRICT+비동기 파기 배치(§3.4, §11.2)';

-- 파티션 부트스트랩: 최초 배포 시점 기준 당월 + 향후 2개월 파티션을 예시로 생성한다.
-- 운영 환경에서는 매월 사전 생성 배치(pg_partman 또는 스케줄러)로 자동화해야 하며,
-- 아래 날짜는 배포 시점에 맞게 조정이 필요하다(§11.2 "매월 사전 생성 배치" 원칙 반영).
CREATE TABLE "LocationHistory_2026_08" PARTITION OF "LocationHistory"
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE "LocationHistory_2026_09" PARTITION OF "LocationHistory"
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE "LocationHistory_2026_10" PARTITION OF "LocationHistory"
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

-- DEFAULT 파티션: 위 범위를 벗어난 recorded_at 값이 INSERT될 경우를 대비한 안전망.
-- 정상 운영 시에는 자동 파티션 생성 배치가 이 파티션에 데이터가 쌓이지 않도록 보장해야 한다.
CREATE TABLE "LocationHistory_default" PARTITION OF "LocationHistory" DEFAULT;

-- ---------------------------------------------------------------------
-- 02-5. VisitHistory (Time-Series, 파티셔닝 조건부 권장 — 현 단계 미적용)
-- ---------------------------------------------------------------------
CREATE TABLE "VisitHistory" (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id                 BIGINT NOT NULL,
    place_id                BIGINT,
    place_name              VARCHAR(150),
    latitude                NUMERIC(10, 7) NOT NULL,
    longitude               NUMERIC(11, 7) NOT NULL,
    arrival_time            TIMESTAMPTZ NOT NULL,
    departure_time          TIMESTAMPTZ,
    stay_minutes            INT,
    is_registered_place     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_vh PRIMARY KEY (id),
    CONSTRAINT ck_vh_departure CHECK (departure_time IS NULL OR departure_time >= arrival_time),
    CONSTRAINT ck_vh_stay CHECK (stay_minutes IS NULL OR stay_minutes >= 0)
);

COMMENT ON TABLE "VisitHistory" IS 'GPS 원본을 분석한 방문 단위 가공 이력. place_name은 방문 당시 스냅샷(§3.5). 데이터량이 임계치를 넘으면 LocationHistory와 동일한 방식으로 파티셔닝 전환 검토(§11.2)';

-- ---------------------------------------------------------------------
-- 02-6. PredictionHistory (Derived Data) — AI 방문 예측 결과
-- ---------------------------------------------------------------------
CREATE TABLE "PredictionHistory" (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id             BIGINT NOT NULL,
    predicted_place     VARCHAR(150) NOT NULL,
    probability         NUMERIC(4, 3) NOT NULL,
    prediction_date     DATE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ph PRIMARY KEY (id),
    CONSTRAINT ck_ph_probability CHECK (probability BETWEEN 0 AND 1),
    CONSTRAINT uq_ph_user_date_place UNIQUE (user_id, prediction_date, predicted_place)
);

COMMENT ON TABLE "PredictionHistory" IS 'AI 서버(FastAPI)가 생성한 방문 예측 결과. Derived Data — 원본(VisitHistory/LocationHistory) 보존 시 재계산 가능(§3.6)';

-- ---------------------------------------------------------------------
-- 02-7. NotificationHistory (Time-Series, 파티셔닝 조건부 권장 — 현 단계 미적용)
-- ---------------------------------------------------------------------
CREATE TABLE "NotificationHistory" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id         BIGINT NOT NULL,
    target_id       BIGINT NOT NULL,
    type            VARCHAR(50) NOT NULL,
    event_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    is_retry        BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at         TIMESTAMPTZ,
    response_at     TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'SENT',

    CONSTRAINT pk_nh PRIMARY KEY (id),
    CONSTRAINT ck_nh_status CHECK (status IN ('SENT', 'READ', 'RESPONDED', 'FAILED')),
    CONSTRAINT ck_nh_type CHECK (
        type IN (
            'ARRIVAL',
            'ARRIVAL_CONFIRM_REQUEST',
            'ARRIVAL_CONFIRMED',
            'EMERGENCY',
            'AI_ANOMALY',
            'AI_PREDICTION',
            'AI_WEEKLY_REPORT'
        )
    )
);

COMMENT ON TABLE "NotificationHistory" IS '알림 발송/읽음/응답 이력. user_id=수신자, target_id=알림 대상 CareTarget. type 7종 최종 확정, event_id로 다인 발송 그룹핑, is_retry로 재발송 여부 표시(§3.7, §13)';

-- ---------------------------------------------------------------------
-- 02-8. ChatHistory (Derived Data) — AI Care Assistant 대화 이력
-- ---------------------------------------------------------------------
CREATE TABLE "ChatHistory" (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id         BIGINT NOT NULL,
    question        TEXT NOT NULL,
    answer          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ch PRIMARY KEY (id)
);

COMMENT ON TABLE "ChatHistory" IS 'AI Care Assistant(Gemini LLM) 대화 원문. 개인정보 삭제 요청 시 보관주기와 무관하게 즉시 삭제(§3.8, §10)';

-- ---------------------------------------------------------------------
-- 02-9. ChatEmbedding (Derived Data, 신규) — Gemini Embedding 벡터 저장
-- ---------------------------------------------------------------------
CREATE TABLE "ChatEmbedding" (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    chat_history_id     BIGINT NOT NULL,
    embedding           VECTOR(768) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ce PRIMARY KEY (id),
    CONSTRAINT uq_ce_chat_history_id UNIQUE (chat_history_id)
);

COMMENT ON TABLE "ChatEmbedding" IS 'ChatHistory 1:1 파생 벡터. Google Gemini Embedding(gemini-embedding-001, output_dimensionality=768) 기준 VECTOR(768) 최종 확정(§3.9, §11.3). RAG 검색용 인덱스이며 원문(ChatHistory)은 별도 보존됨';

-- ---------------------------------------------------------------------
-- 02-10. updated_at 자동 갱신 트리거 (User, Place — 설계 가이드 §4.1/§4.3 "트리거 자동 갱신" 반영)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_set_updated_at
    BEFORE UPDATE ON "User"
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_place_set_updated_at
    BEFORE UPDATE ON "Place"
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();


-- =====================================================================
-- 03. CONSTRAINT (FK 및 파티셔닝 테이블 특성상 CREATE TABLE 내부에서
--     정의하기 어려운 관계 제약을 일괄 추가)
-- =====================================================================
-- 모든 FK의 ON UPDATE는 NO ACTION(기본값)을 명시적으로 선언한다 —
-- PK 값은 애플리케이션에서 변경되지 않는 값이므로 CASCADE가 불필요하다(§7).

-- ---------------------------------------------------------------------
-- GuardianTarget → User (guardian_id, target_id)
-- ---------------------------------------------------------------------
ALTER TABLE "GuardianTarget"
    ADD CONSTRAINT fk_gt_guardian FOREIGN KEY (guardian_id)
        REFERENCES "User" (id) ON DELETE CASCADE ON UPDATE NO ACTION;

ALTER TABLE "GuardianTarget"
    ADD CONSTRAINT fk_gt_target FOREIGN KEY (target_id)
        REFERENCES "User" (id) ON DELETE CASCADE ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- Place → User (user_id)
-- ---------------------------------------------------------------------
ALTER TABLE "Place"
    ADD CONSTRAINT fk_place_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE CASCADE ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- LocationHistory → User (user_id)
-- ---------------------------------------------------------------------
-- 주의: 파티션 부모 테이블에 FK를 선언하면 모든 파티션에 동일하게 적용된다(PG 12+).
ALTER TABLE "LocationHistory"
    ADD CONSTRAINT fk_lh_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- VisitHistory → User (user_id), Place (place_id)
-- ---------------------------------------------------------------------
ALTER TABLE "VisitHistory"
    ADD CONSTRAINT fk_vh_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

ALTER TABLE "VisitHistory"
    ADD CONSTRAINT fk_vh_place FOREIGN KEY (place_id)
        REFERENCES "Place" (id) ON DELETE SET NULL ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- PredictionHistory → User (user_id)
-- ---------------------------------------------------------------------
ALTER TABLE "PredictionHistory"
    ADD CONSTRAINT fk_ph_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- NotificationHistory → User (user_id: 수신자, target_id: 보호대상자)
-- ---------------------------------------------------------------------
ALTER TABLE "NotificationHistory"
    ADD CONSTRAINT fk_nh_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

ALTER TABLE "NotificationHistory"
    ADD CONSTRAINT fk_nh_target FOREIGN KEY (target_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- ChatHistory → User (user_id)
-- ---------------------------------------------------------------------
ALTER TABLE "ChatHistory"
    ADD CONSTRAINT fk_ch_user FOREIGN KEY (user_id)
        REFERENCES "User" (id) ON DELETE RESTRICT ON UPDATE NO ACTION;

-- ---------------------------------------------------------------------
-- ChatEmbedding → ChatHistory (chat_history_id)
-- ---------------------------------------------------------------------
ALTER TABLE "ChatEmbedding"
    ADD CONSTRAINT fk_ce_chat FOREIGN KEY (chat_history_id)
        REFERENCES "ChatHistory" (id) ON DELETE CASCADE ON UPDATE NO ACTION;


-- =====================================================================
-- 04. INDEX (일반 Index, Composite Index, Partial Unique Index)
-- =====================================================================
-- 명명 규칙: 설계 가이드(§9)에서 이미 이름이 지정된 인덱스는 그 이름을 그대로 사용하고,
-- 그 외에는 idx_{table}_{column}, uq_{table}_{column} 규칙을 따른다.
-- Partial UNIQUE 제약(uq_user_email_active, uq_gt_active_pair, uq_gt_primary_per_target)은
-- PostgreSQL 문법상 테이블 레벨 CONSTRAINT로 표현할 수 없어 CREATE UNIQUE INDEX ... WHERE로
-- 구현한다 — 기능적으로는 03. Constraint 절과 동일한 무결성 제약 역할을 겸한다.

-- ---------------------------------------------------------------------
-- User
-- ---------------------------------------------------------------------
-- uq_user_oauth, uk_user_public_id는 02절 UNIQUE 제약 선언 시 PostgreSQL이 자동으로
-- 동일 이름의 인덱스를 생성하므로 별도 CREATE INDEX 불필요.
CREATE UNIQUE INDEX uq_user_email_active
    ON "User" (email)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- GuardianTarget
-- ---------------------------------------------------------------------
CREATE INDEX idx_gt_guardian
    ON "GuardianTarget" (guardian_id);

CREATE INDEX idx_gt_target_role
    ON "GuardianTarget" (target_id, guardian_role);

CREATE UNIQUE INDEX uq_gt_active_pair
    ON "GuardianTarget" (guardian_id, target_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_gt_primary_per_target
    ON "GuardianTarget" (target_id)
    WHERE guardian_role = 'PRIMARY' AND status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- Place
-- ---------------------------------------------------------------------
CREATE INDEX idx_place_user
    ON "Place" (user_id)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- LocationHistory
-- ---------------------------------------------------------------------
-- 파티션 부모 테이블에 생성하면 모든 파티션에 로컬 인덱스가 자동 생성된다(PG 11+).
CREATE INDEX idx_lh_user_recorded
    ON "LocationHistory" (user_id, recorded_at DESC);

-- ---------------------------------------------------------------------
-- VisitHistory
-- ---------------------------------------------------------------------
CREATE INDEX idx_vh_user_arrival
    ON "VisitHistory" (user_id, arrival_time DESC);

CREATE INDEX idx_vh_registered
    ON "VisitHistory" (user_id, is_registered_place)
    WHERE is_registered_place = FALSE;

-- [보완 추가] idx_vh_place: place_id는 FK 대상 컬럼이나 설계 가이드 §9 목록에
-- 누락되어 있었음. "PostgreSQL은 FK에 자동으로 인덱스를 생성하지 않으므로 모든 FK
-- 대상 컬럼에 명시적 인덱스 필요"라는 §9 공통 원칙을 그대로 적용해 보완한다.
-- place_id는 nullable(미등록 장소 방문 시 NULL)이므로 Partial Index로 만들어
-- 불필요한 NULL 항목을 인덱스에서 제외한다(idx_place_user, idx_vh_registered와 동일 패턴, v6.1).
CREATE INDEX idx_vh_place
    ON "VisitHistory" (place_id)
    WHERE place_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- PredictionHistory
-- ---------------------------------------------------------------------
-- uq_ph_user_date_place는 02절 UNIQUE 제약 선언 시 자동 생성되므로 별도 CREATE INDEX 불필요.
CREATE INDEX idx_ph_user_date
    ON "PredictionHistory" (user_id, prediction_date DESC);

-- ---------------------------------------------------------------------
-- NotificationHistory
-- ---------------------------------------------------------------------
CREATE INDEX idx_nh_user_status
    ON "NotificationHistory" (user_id, status)
    WHERE status <> 'READ';

CREATE INDEX idx_nh_user_sent
    ON "NotificationHistory" (user_id, sent_at DESC);

CREATE INDEX idx_nh_target_sent
    ON "NotificationHistory" (target_id, sent_at DESC);

CREATE INDEX idx_nh_event
    ON "NotificationHistory" (event_id);

-- ---------------------------------------------------------------------
-- ChatHistory
-- ---------------------------------------------------------------------
CREATE INDEX idx_ch_user_created
    ON "ChatHistory" (user_id, created_at DESC);

-- ChatEmbedding.chat_history_id는 uq_ce_chat_history_id UNIQUE 제약으로 이미
-- 인덱스가 자동 생성되므로 별도 FK 인덱스 불필요.


-- =====================================================================
-- 05. VECTOR INDEX (pgvector HNSW)
-- =====================================================================
-- 거리 연산자: vector_cosine_ops (코사인 유사도, §11.3 확정)
-- 인덱스 종류: HNSW (IVFFlat 대비 정확도-속도 균형 우수, 사전 튜닝 부담 낮음)
CREATE INDEX idx_chat_embedding_hnsw
    ON "ChatEmbedding"
    USING hnsw (embedding vector_cosine_ops);


-- =====================================================================
-- 06. VERIFICATION QUERY
-- =====================================================================

-- 6-1. 전체 테이블 목록 (파티션 제외, BASE TABLE만)
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- 6-2. 테이블별 컬럼 목록 (타입, NULL 허용 여부, DEFAULT)
SELECT table_name, ordinal_position, column_name, data_type,
       is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;

-- 6-3. PK 제약조건
SELECT tc.table_name, tc.constraint_name, kcu.column_name, kcu.ordinal_position
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = 'public'
ORDER BY tc.table_name, kcu.ordinal_position;

-- 6-4. FK 제약조건 (참조 대상 및 ON DELETE/ON UPDATE 정책 포함)
SELECT
    con.conname                                   AS fk_name,
    src_cls.relname                                AS table_name,
    src_att.attname                                AS column_name,
    dst_cls.relname                                AS referenced_table,
    dst_att.attname                                AS referenced_column,
    CASE con.confupdtype
        WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
        WHEN 'c' THEN 'CASCADE'   WHEN 'n' THEN 'SET NULL'
        WHEN 'd' THEN 'SET DEFAULT'
    END                                             AS on_update,
    CASE con.confdeltype
        WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
        WHEN 'c' THEN 'CASCADE'   WHEN 'n' THEN 'SET NULL'
        WHEN 'd' THEN 'SET DEFAULT'
    END                                             AS on_delete
FROM pg_constraint con
JOIN pg_class src_cls ON src_cls.oid = con.conrelid
JOIN pg_class dst_cls ON dst_cls.oid = con.confrelid
JOIN pg_attribute src_att ON src_att.attrelid = con.conrelid AND src_att.attnum = ANY(con.conkey)
JOIN pg_attribute dst_att ON dst_att.attrelid = con.confrelid AND dst_att.attnum = ANY(con.confkey)
WHERE con.contype = 'f'
ORDER BY table_name, fk_name;

-- 6-5. UNIQUE 제약조건 (테이블 레벨, Partial 제외)
SELECT tc.table_name, tc.constraint_name, kcu.column_name
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
WHERE tc.constraint_type = 'UNIQUE' AND tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_name;

-- 6-6. Partial UNIQUE Index (uq_user_email_active, uq_gt_active_pair, uq_gt_primary_per_target 등)
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexdef ILIKE '%UNIQUE%'
  AND indexdef ILIKE '%WHERE%'
ORDER BY tablename, indexname;

-- 6-7. CHECK 제약조건
SELECT tc.table_name, tc.constraint_name, cc.check_clause
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
  ON tc.constraint_name = cc.constraint_name AND tc.table_schema = cc.constraint_schema
WHERE tc.constraint_type = 'CHECK' AND tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_name;

-- 6-8. 전체 Index 목록 (일반 + Composite + Partial + Vector)
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

-- 6-9. Vector(HNSW) 인덱스만 별도 확인
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexdef ILIKE '%hnsw%'
ORDER BY tablename, indexname;

-- 6-10. 파티션 구조 확인 (LocationHistory)
SELECT
    parent.relname  AS partitioned_table,
    child.relname   AS partition_name,
    pg_get_expr(child.relpartbound, child.oid) AS partition_range
FROM pg_inherits
JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class child  ON pg_inherits.inhrelid  = child.oid
WHERE parent.relname = 'LocationHistory'
ORDER BY partition_name;

-- 6-11. 트리거 확인 (updated_at 자동 갱신)
SELECT event_object_table, trigger_name, action_timing, event_manipulation
FROM information_schema.triggers
WHERE trigger_schema = 'public'
ORDER BY event_object_table, trigger_name;

-- 6-12. pgvector 확장 설치 확인
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
