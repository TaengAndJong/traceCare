package com.tracecare.backend.domain.chat.repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import com.pgvector.PGvector;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code ChatEmbedding.embedding}(VECTOR(768))은 JPQL로 다룰 수 없어(코사인 유사도 연산자 {@code <=>}가 표준 JPA에 없음)
 * {@link com.tracecare.backend.domain.location.repository.LocationHistoryWriter}와 동일하게 raw JDBC로
 * 분리한다. {@code PGvector.registerTypes(Connection)}은 커넥션 단위로 벡터 타입을 등록해야 하는 pgvector-java의 요구사항이라,
 * HikariCP가 커넥션을 재사용하더라도 안전하게 매번 호출한다(멱등, 이 도메인은 호출 빈도가 낮아 비용 무시 가능).
 *
 * <p><b>target_id 필터링(2026-08)</b>: 검색은 항상 {@code user_id}(호출자 Guardian 본인) + {@code target_id}(같은
 * CareTarget 여부) 조합으로 범위를 좁힌다. {@code target_id}가 NULL인지 값이 있는지에 따라 SQL 파라미터 바인딩 방식이 달라져야 해서
 * (PostgreSQL은 {@code = ?}로 NULL을 매칭할 수 없다) 쿼리 문자열을 두 가지로 분리해뒀다 — {@code IS NOT DISTINCT FROM} 연산자로
 * 하나의 SQL로 합치는 대안도 있으나, 인덱스({@code idx_ch_user_target_created}) 활용 여부가 PostgreSQL 버전별로 불확실해 더 명시적인
 * 이 방식을 택했다.
 *
 * @see com.tracecare.backend.domain.location.repository.LocationHistoryWriter
 */
@Repository
public class ChatEmbeddingStore {

    private static final String INSERT_SQL =
            "INSERT INTO \"ChatEmbedding\" (chat_history_id, embedding) VALUES (?, ?)";

    /**
     * {@code idx_chat_embedding_hnsw}(vector_cosine_ops)를 그대로 태우도록 코사인 거리 연산자({@code <=>})로 정렬한다.
     */
    private static final String SEARCH_SQL_WITH_TARGET =
            "SELECT ch.question, ch.answer FROM \"ChatEmbedding\" ce "
                    + "JOIN \"ChatHistory\" ch ON ch.id = ce.chat_history_id "
                    + "WHERE ch.user_id = ? AND ch.target_id = ? "
                    + "ORDER BY ce.embedding <=> ? LIMIT ?";

    private static final String SEARCH_SQL_WITHOUT_TARGET =
            "SELECT ch.question, ch.answer FROM \"ChatEmbedding\" ce "
                    + "JOIN \"ChatHistory\" ch ON ch.id = ce.chat_history_id "
                    + "WHERE ch.user_id = ? AND ch.target_id IS NULL "
                    + "ORDER BY ce.embedding <=> ? LIMIT ?";

    private final JdbcTemplate jdbcTemplate;

    public ChatEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Long chatHistoryId, float[] embedding) {
        jdbcTemplate.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            PGvector.registerTypes(connection);
                            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                                ps.setLong(1, chatHistoryId);
                                ps.setObject(2, new PGvector(embedding));
                                ps.executeUpdate();
                            }
                            return null;
                        });
    }

    /**
     * 유사도 상위 {@code topN}개의 (question, answer) 쌍을 유사도 순으로 반환한다. {@code targetId}가 있으면 같은
     * CareTarget에 대한 대화만, 없으면(null) 일반 대화({@code target_id IS NULL})만 검색 대상이 된다 — 서로 다른 CareTarget의
     * 대화나 일반 대화가 섞이지 않는다.
     */
    public List<PastExchange> findSimilar(
            Long userId, Long targetId, float[] queryEmbedding, int topN) {
        String sql = (targetId != null) ? SEARCH_SQL_WITH_TARGET : SEARCH_SQL_WITHOUT_TARGET;
        return jdbcTemplate.execute(
                (ConnectionCallback<List<PastExchange>>)
                        connection -> {
                            PGvector.registerTypes(connection);
                            List<PastExchange> results = new ArrayList<>();
                            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                                int idx = 1;
                                ps.setLong(idx++, userId);
                                if (targetId != null) {
                                    ps.setLong(idx++, targetId);
                                }
                                ps.setObject(idx++, new PGvector(queryEmbedding));
                                ps.setInt(idx, topN);
                                var rs = ps.executeQuery();
                                while (rs.next()) {
                                    results.add(
                                            new PastExchange(
                                                    rs.getString("question"),
                                                    rs.getString("answer")));
                                }
                            }
                            return results;
                        });
    }

    public record PastExchange(String question, String answer) {}
}
