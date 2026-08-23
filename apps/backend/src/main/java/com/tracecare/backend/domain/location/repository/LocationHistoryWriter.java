package com.tracecare.backend.domain.location.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link LocationHistoryRepository}(JPA)와 별도로 INSERT 전용 경로를 둔다 — Hibernate 6은 "Identity generation
 * isn't supported for composite ids"로 IDENTITY 전략과 복합 PK({@code @IdClass}) 조합의 {@code
 * EntityManager.persist()/repository.save()}를 지원하지 않는다(직접 재현해 확인). id 컬럼 자체는 DB의 {@code GENERATED
 * ALWAYS AS IDENTITY}가 그대로 생성하므로, JPA를 거치지 않고 {@code INSERT ... RETURNING id}로 직접 넣고 생성된 id만 돌려받는다.
 * 조회(SELECT)는 복합키 제약과 무관해 {@link LocationHistoryRepository}를 그대로 쓴다.
 */
@Repository
public class LocationHistoryWriter {

    private static final String INSERT_SQL =
            "INSERT INTO \"LocationHistory\" (user_id, latitude, longitude, recorded_at) "
                    + "VALUES (?, ?, ?, ?) RETURNING id";

    private final JdbcTemplate jdbcTemplate;

    public LocationHistoryWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(Long userId, BigDecimal latitude, BigDecimal longitude, Instant recordedAt) {
        // pgjdbc가 java.time.Instant를 PreparedStatement 파라미터로 직접 바인딩할 SQL 타입을 추론하지 못해
        // (JdbcTemplate의 가변 인자 경로는 setObject(index, value)로만 호출) java.sql.Timestamp로 변환한다.
        return jdbcTemplate.queryForObject(
                INSERT_SQL, Long.class, userId, latitude, longitude, Timestamp.from(recordedAt));
    }
}
