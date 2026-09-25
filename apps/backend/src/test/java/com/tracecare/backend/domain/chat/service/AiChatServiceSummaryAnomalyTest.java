package com.tracecare.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.tracecare.backend.common.exception.business.VisitHistoryNotFoundException;
import com.tracecare.backend.domain.anomaly.dto.response.SummaryAnomalyResponse;
import com.tracecare.backend.domain.anomaly.service.AnomalySummaryQueryService;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.chat.client.EmbeddingClient;
import com.tracecare.backend.domain.chat.client.LlmClient;
import com.tracecare.backend.domain.chat.dto.request.SummaryRequest;
import com.tracecare.backend.domain.chat.dto.request.WeeklyReportRequest;
import com.tracecare.backend.domain.chat.dto.response.SummaryResponse;
import com.tracecare.backend.domain.chat.repository.ChatEmbeddingStore;
import com.tracecare.backend.domain.chat.repository.ChatHistoryRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.visit.entity.VisitHistory;
import com.tracecare.backend.domain.visit.repository.VisitHistoryRepository;

/**
 * §4.5 — {@code /summary}·{@code /report/weekly}의 이상행동 연동(API_Specification.md §3.6)을 검증한다: 404 규칙 매트릭스, 응답
 * 필드, LLM 프롬프트에 이상행동 원본 데이터가 들어가지 않는지, DB 조회와 LLM 호출의 트랜잭션 분리. 이상행동 조회 자체(겹침/notified/상한)는 {@code
 * AnomalySummaryQueryServiceIntegrationTest}가 실제 DB로 검증한다. LLM은 전부 Mock이다(무료 티어 소모 방지).
 */
@ExtendWith(MockitoExtension.class)
class AiChatServiceSummaryAnomalyTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long TARGET_ID = 2L;
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-31T00:00:00Z");
    private static final String SECRET_PLACE_NAME = "비밀장소XYZ";

    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatHistoryRepository chatHistoryRepository;
    @Mock private ChatEmbeddingStore chatEmbeddingStore;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private LlmClient llmClient;
    @Mock private VisitHistoryRepository visitHistoryRepository;
    @Mock private AnomalySummaryQueryService anomalySummaryQueryService;

    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    private UUID targetPublicId;

    /** begin~commit/rollback 사이인지를 기록한다 — "지금 트랜잭션 안인가"를 테스트가 물어볼 수 있게 한다. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicBoolean active = new AtomicBoolean(false);

        boolean isActive() {
            return active.get();
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active.set(false);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active.set(false);
        }
    }

    @BeforeEach
    void stubActiveTarget() {
        targetPublicId = UUID.randomUUID();
        User target = mock(User.class);
        when(target.getId()).thenReturn(TARGET_ID);
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.of(target));
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.of(mock(GuardianTarget.class)));
    }

    private AiChatService service() {
        return new AiChatService(
                guardianTargetRepository,
                userRepository,
                chatHistoryRepository,
                chatEmbeddingStore,
                embeddingClient,
                llmClient,
                visitHistoryRepository,
                anomalySummaryQueryService,
                transactionManager);
    }

    private SummaryRequest summaryRequest() {
        return SummaryRequest.builder()
                .careTargetId(targetPublicId.toString())
                .from(FROM)
                .to(TO)
                .build();
    }

    private WeeklyReportRequest weeklyRequest() {
        return WeeklyReportRequest.builder().careTargetId(targetPublicId.toString()).build();
    }

    private void stubVisits(int count) {
        List<VisitHistory> visits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            visits.add(
                    VisitHistory.arrive(
                            TARGET_ID, 10L, "놀이터", BigDecimal.ONE, BigDecimal.ONE, FROM.plusSeconds(60L * (i + 1))));
        }
        when(visitHistoryRepository.findByUserIdAndArrivalTimeBetweenOrderByArrivalTimeDesc(
                        eq(TARGET_ID), any(), any()))
                .thenReturn(visits);
    }

    private SummaryAnomalyResponse anomaly(long id, String placeName, boolean notified) {
        return SummaryAnomalyResponse.builder()
                .anomalyEventId(id)
                .type("ARRIVAL_DELAY")
                .status("ONGOING")
                .detectedAt(FROM.plusSeconds(100))
                .placeId(UUID.randomUUID().toString())
                .placeName(placeName)
                .notified(notified)
                .build();
    }

    private void stubAnomalies(long totalCount, List<SummaryAnomalyResponse> items) {
        when(anomalySummaryQueryService.find(eq(GUARDIAN_ID), eq(TARGET_ID), any(), any()))
                .thenReturn(new AnomalySummaryQueryService.Result(totalCount, items));
    }

    // ---------------------------------------------------------------------
    // 404 규칙 매트릭스 (API_Specification.md §3.6)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("summary — 방문 있음 + 이상행동 있음이면 LLM 요약과 이상행동 목록을 함께 반환한다")
    void summarize_visitsAndAnomalies_returnsLlmAnswerAndAnomalies() {
        stubVisits(2);
        stubAnomalies(1, List.of(anomaly(7L, "학교", false)));
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("요약 문장");

        SummaryResponse response = service().summarize(GUARDIAN_ID, summaryRequest());

        assertThat(response.getAnswer()).isEqualTo("요약 문장");
        assertThat(response.getVisitCount()).isEqualTo(2);
        assertThat(response.getAnomalyCount()).isEqualTo(1);
        assertThat(response.getAnomalies()).extracting(SummaryAnomalyResponse::getAnomalyEventId).containsExactly(7L);
    }

    @Test
    @DisplayName("summary — 방문 있음 + 이상행동 없음이면 LLM 요약과 함께 anomalyCount 0, 빈 배열(null 아님)을 반환한다")
    void summarize_visitsOnly_returnsZeroCountAndEmptyList() {
        stubVisits(1);
        stubAnomalies(0, List.of());
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("요약 문장");

        SummaryResponse response = service().summarize(GUARDIAN_ID, summaryRequest());

        assertThat(response.getAnswer()).isEqualTo("요약 문장");
        assertThat(response.getAnomalyCount()).isZero();
        assertThat(response.getAnomalies()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("summary — 방문 없음 + 이상행동 있음이면 404가 아니라 200이고 LLM을 호출하지 않고 고정 문장을 반환한다")
    void summarize_noVisitsButAnomalies_returnsFixedAnswerWithoutLlm() {
        stubVisits(0);
        stubAnomalies(2, List.of(anomaly(7L, "학교", false), anomaly(8L, "집", true)));

        SummaryResponse response = service().summarize(GUARDIAN_ID, summaryRequest());

        assertThat(response.getAnswer())
                .isEqualTo("해당 기간의 방문 기록은 없습니다. 아래 이상행동을 확인해 주세요.");
        assertThat(response.getVisitCount()).isZero();
        assertThat(response.getAnomalyCount()).isEqualTo(2);
        assertThat(response.getAnomalies()).hasSize(2);
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }

    @Test
    @DisplayName("summary — 방문 없음 + 이상행동 없음이면 VISIT_001을 던지고 LLM을 호출하지 않는다")
    void summarize_noVisitsNoAnomalies_throwsVisitHistoryNotFound() {
        stubVisits(0);
        stubAnomalies(0, List.of());

        assertThatThrownBy(() -> service().summarize(GUARDIAN_ID, summaryRequest()))
                .isInstanceOf(VisitHistoryNotFoundException.class);
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }

    @Test
    @DisplayName("report/weekly — 방문 없음 + 이상행동 있음이면 200이고 LLM을 호출하지 않는다(summary와 같은 규칙)")
    void weeklyReport_noVisitsButAnomalies_returnsFixedAnswerWithoutLlm() {
        stubVisits(0);
        stubAnomalies(1, List.of(anomaly(7L, "학교", false)));

        SummaryResponse response = service().weeklyReport(GUARDIAN_ID, weeklyRequest());

        assertThat(response.getAnswer())
                .isEqualTo("해당 기간의 방문 기록은 없습니다. 아래 이상행동을 확인해 주세요.");
        assertThat(response.getAnomalyCount()).isEqualTo(1);
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }

    @Test
    @DisplayName("report/weekly — 방문 없음 + 이상행동 없음이면 VISIT_001을 던진다")
    void weeklyReport_noVisitsNoAnomalies_throwsVisitHistoryNotFound() {
        stubVisits(0);
        stubAnomalies(0, List.of());

        assertThatThrownBy(() -> service().weeklyReport(GUARDIAN_ID, weeklyRequest()))
                .isInstanceOf(VisitHistoryNotFoundException.class);
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }

    @Test
    @DisplayName("report/weekly — 방문 있음이면 LLM 리포트와 이상행동 목록을 함께 반환한다")
    void weeklyReport_visitsAndAnomalies_returnsLlmAnswerAndAnomalies() {
        stubVisits(3);
        stubAnomalies(1, List.of(anomaly(7L, "학교", true)));
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("주간 리포트");

        SummaryResponse response = service().weeklyReport(GUARDIAN_ID, weeklyRequest());

        assertThat(response.getAnswer()).isEqualTo("주간 리포트");
        assertThat(response.getVisitCount()).isEqualTo(3);
        assertThat(response.getAnomalyCount()).isEqualTo(1);
        assertThat(response.getAnomalies().get(0).isNotified()).isTrue();
    }

    @Test
    @DisplayName("summary — 이상행동이 20건 상한을 넘어도 anomalyCount는 조회된 항목 수가 아니라 총 건수다")
    void summarize_moreAnomaliesThanCap_keepsTotalCount() {
        stubVisits(1);
        List<SummaryAnomalyResponse> capped = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            capped.add(anomaly(i, "학교", false));
        }
        stubAnomalies(25, capped);
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("요약 문장");

        SummaryResponse response = service().summarize(GUARDIAN_ID, summaryRequest());

        assertThat(response.getAnomalyCount()).isEqualTo(25);
        assertThat(response.getAnomalies()).hasSize(20);
    }

    // ---------------------------------------------------------------------
    // LLM 프롬프트에 이상행동 데이터가 들어가지 않는다
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("summary — LLM 호출 인자에 이상행동 원본 데이터(장소명/유형/ID)가 없고 단정 금지 지침 한 문장만 있다")
    void summarize_llmPrompt_excludesAnomalyDataAndCarriesNoticeSentence() {
        stubVisits(1);
        stubAnomalies(1, List.of(anomaly(987654L, SECRET_PLACE_NAME, false)));
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("요약 문장");

        service().summarize(GUARDIAN_ID, summaryRequest());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(llmClient).generateAnswer(system.capture(), turns.capture());
        String everythingSentToLlm =
                system.getValue() + "\n" + turns.getValue().stream().map(LlmClient.Turn::toString).reduce("", String::concat);
        assertThat(everythingSentToLlm)
                .doesNotContain(SECRET_PLACE_NAME)
                .doesNotContain("987654")
                .doesNotContain("ARRIVAL_DELAY");
        assertThat(system.getValue()).contains("이상행동은 별도로 표시되므로", "단정적인 표현을 쓰지 않습니다");
    }

    @Test
    @DisplayName("report/weekly — 주간 리포트 지침에도 단정 금지 문장이 있고 이상행동 데이터는 없다")
    void weeklyReport_llmPrompt_excludesAnomalyDataAndCarriesNoticeSentence() {
        stubVisits(1);
        stubAnomalies(1, List.of(anomaly(987654L, SECRET_PLACE_NAME, false)));
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("주간 리포트");

        service().weeklyReport(GUARDIAN_ID, weeklyRequest());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(llmClient).generateAnswer(system.capture(), turns.capture());
        String everythingSentToLlm =
                system.getValue() + "\n" + turns.getValue().stream().map(LlmClient.Turn::toString).reduce("", String::concat);
        assertThat(everythingSentToLlm).doesNotContain(SECRET_PLACE_NAME).doesNotContain("987654");
        assertThat(system.getValue()).contains("이상행동은 별도로 표시되므로");
    }

    // ---------------------------------------------------------------------
    // 트랜잭션 분리: DB 조회는 트랜잭션 안, LLM 호출은 밖
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("summary — 방문/이상행동 조회는 트랜잭션 안에서, LLM 호출은 트랜잭션이 끝난 뒤에 일어난다")
    void summarize_dbReadsInsideTransaction_llmCallOutsideTransaction() {
        AtomicBoolean visitReadInTx = new AtomicBoolean();
        AtomicBoolean anomalyReadInTx = new AtomicBoolean();
        AtomicBoolean llmCalledInTx = new AtomicBoolean(true);

        when(visitHistoryRepository.findByUserIdAndArrivalTimeBetweenOrderByArrivalTimeDesc(
                        eq(TARGET_ID), any(), any()))
                .thenAnswer(
                        inv -> {
                            visitReadInTx.set(transactionManager.isActive());
                            return List.of(
                                    VisitHistory.arrive(
                                            TARGET_ID, 10L, "놀이터", BigDecimal.ONE, BigDecimal.ONE, FROM.plusSeconds(60)));
                        });
        when(anomalySummaryQueryService.find(anyLong(), anyLong(), any(), any()))
                .thenAnswer(
                        inv -> {
                            anomalyReadInTx.set(transactionManager.isActive());
                            return new AnomalySummaryQueryService.Result(0, List.of());
                        });
        when(llmClient.generateAnswer(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            llmCalledInTx.set(transactionManager.isActive());
                            return "요약 문장";
                        });

        service().summarize(GUARDIAN_ID, summaryRequest());

        assertThat(visitReadInTx).as("방문 조회는 트랜잭션 안").isTrue();
        assertThat(anomalyReadInTx).as("이상행동 조회는 트랜잭션 안").isTrue();
        assertThat(llmCalledInTx).as("LLM 호출은 트랜잭션 밖").isFalse();
    }

    @Test
    @DisplayName("report/weekly — LLM 호출은 트랜잭션이 끝난 뒤에 일어난다")
    void weeklyReport_llmCallOutsideTransaction() {
        stubVisits(1);
        stubAnomalies(0, List.of());
        AtomicBoolean llmCalledInTx = new AtomicBoolean(true);
        when(llmClient.generateAnswer(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            llmCalledInTx.set(transactionManager.isActive());
                            return "주간 리포트";
                        });

        service().weeklyReport(GUARDIAN_ID, weeklyRequest());

        assertThat(llmCalledInTx).isFalse();
        assertThat(transactionManager.isActive()).isFalse();
    }

    @Test
    @DisplayName("summary — 조회 중 예외(VISIT_001)가 나도 트랜잭션이 정리되고 LLM은 호출되지 않는다")
    void summarize_readFails_transactionClosedAndLlmNotCalled() {
        stubVisits(0);
        stubAnomalies(0, List.of());

        assertThatThrownBy(() -> service().summarize(GUARDIAN_ID, summaryRequest()))
                .isInstanceOf(VisitHistoryNotFoundException.class);

        assertThat(transactionManager.isActive()).isFalse();
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }
}
