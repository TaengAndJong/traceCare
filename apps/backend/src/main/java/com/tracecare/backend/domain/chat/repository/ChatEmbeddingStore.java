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
 * @see com.tracecare.backend.domain.location.repository.LocationHistoryWriter
 */
@Repository
public class ChatEmbeddingStore {

    private static final String INSERT_SQL =
            "INSERT INTO \"ChatEmbedding\" (chat_history_id, embedding) VALUES (?, ?)";

    /**
     * {@code idx_chat_embedding_hnsw}(vector_cosine_ops)를 그대로 태우도록 코사인 거리 연산자({@code <=>})로 정렬한다.
     * {@code ch.user_id = ?}로 호출자(Guardian) 본인의 과거 대화로만 검색 범위를 제한한다(ChatHistory가 Guardian 단위 소유이므로
     * — 클래스 Javadoc 참고).
     */
    private static final String SEARCH_SQL =
            "SELECT ch.question, ch.answer FROM \"ChatEmbedding\" ce "
                    + "JOIN \"ChatHistory\" ch ON ch.id = ce.chat_history_id "
                    + "WHERE ch.user_id = ? "
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

    /** 유사도 상위 {@code topN}개의 (question, answer) 쌍을 유사도 순으로 반환한다. */
    public List<PastExchange> findSimilar(Long userId, float[] queryEmbedding, int topN) {
        return jdbcTemplate.execute(
                (ConnectionCallback<List<PastExchange>>)
                        connection -> {
                            PGvector.registerTypes(connection);
                            List<PastExchange> results = new ArrayList<>();
                            try (PreparedStatement ps = connection.prepareStatement(SEARCH_SQL)) {
                                ps.setLong(1, userId);
                                ps.setObject(2, new PGvector(queryEmbedding));
                                ps.setInt(3, topN);
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
