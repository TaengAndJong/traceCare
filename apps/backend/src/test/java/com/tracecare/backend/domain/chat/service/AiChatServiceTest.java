package com.tracecare.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tracecare.backend.common.exception.ErrorCode;
import com.tracecare.backend.common.exception.auth.AccessDeniedCustomException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.chat.client.EmbeddingClient;
import com.tracecare.backend.domain.chat.client.LlmClient;
import com.tracecare.backend.domain.chat.dto.request.ChatRequest;
import com.tracecare.backend.domain.chat.dto.response.ChatResponse;
import com.tracecare.backend.domain.chat.entity.ChatHistory;
import com.tracecare.backend.domain.chat.repository.ChatEmbeddingStore;
import com.tracecare.backend.domain.chat.repository.ChatHistoryRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;

/**
 * AiChatService는 실제 Gemini를 호출하지 않는다 — {@link LlmClient}/{@link EmbeddingClient}를 전부 Mock으로 대체한다(이번
 * 세션 명시 요구사항: 자동화 테스트는 무료 티어 한도를 소진하면 안 됨).
 */
@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long TARGET_ID = 2L;

    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatHistoryRepository chatHistoryRepository;
    @Mock private ChatEmbeddingStore chatEmbeddingStore;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private LlmClient llmClient;

    private AiChatService service() {
        return new AiChatService(
                guardianTargetRepository,
                userRepository,
                chatHistoryRepository,
                chatEmbeddingStore,
                embeddingClient,
                llmClient);
    }

    @Test
    @DisplayName("careTargetId 없이 질문하면 관계 검증 없이 바로 LLM을 호출하고 대화를 저장한다")
    void chat_withoutCareTargetId_skipsRelationCheck() {
        // given
        when(embeddingClient.embed(anyString())).thenReturn(new float[] {0.1f});
        when(chatEmbeddingStore.findSimilar(eq(GUARDIAN_ID), eq(null), any(), anyInt()))
                .thenReturn(List.of());
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("테스트 답변");
        ChatHistory saved = ChatHistory.create(GUARDIAN_ID, null, "테스트 질문", "테스트 답변");
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenReturn(saved);

        ChatRequest request = ChatRequest.builder().message("테스트 질문").build();

        // when
        ChatResponse response = service().chat(GUARDIAN_ID, request);

        // then
        assertThat(response.getAnswer()).isEqualTo("테스트 답변");
        verify(guardianTargetRepository, never())
                .findByGuardianIdAndTargetIdAndStatus(anyLong(), anyLong(), anyString());
        verify(chatEmbeddingStore).save(eq(saved.getId()), any(float[].class));
    }

    @Test
    @DisplayName("careTargetId가 있고 ACTIVE 관계가 있으면 그 target_id로 저장하고 같은 target_id로만 RAG 검색한다")
    void chat_withActiveCareTarget_scopesStorageAndSearchToTarget() {
        // given
        UUID targetPublicId = UUID.randomUUID();
        User target = mockUserWithId(TARGET_ID);
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.of(target));
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(GuardianTarget.class)));
        when(embeddingClient.embed(anyString())).thenReturn(new float[] {0.1f});
        when(chatEmbeddingStore.findSimilar(eq(GUARDIAN_ID), eq(TARGET_ID), any(), anyInt()))
                .thenReturn(List.of());
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("아이 답변");
        ChatHistory saved = ChatHistory.create(GUARDIAN_ID, TARGET_ID, "우리 애 지금 어디야", "아이 답변");
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenReturn(saved);

        ChatRequest request =
                ChatRequest.builder()
                        .message("우리 애 지금 어디야")
                        .careTargetId(targetPublicId.toString())
                        .build();

        // when
        ChatResponse response = service().chat(GUARDIAN_ID, request);

        // then
        assertThat(response.getAnswer()).isEqualTo("아이 답변");
        verify(chatEmbeddingStore).findSimilar(eq(GUARDIAN_ID), eq(TARGET_ID), any(), anyInt());
    }

    @Test
    @DisplayName("careTargetId가 있는데 ACTIVE 관계가 없으면 TARGET_002로 거부하고 LLM을 호출하지 않는다")
    void chat_withUnrelatedCareTarget_throwsAccessDenied() {
        // given
        UUID targetPublicId = UUID.randomUUID();
        User target = mockUserWithId(TARGET_ID);
        when(userRepository.findByPublicId(targetPublicId)).thenReturn(Optional.of(target));
        when(guardianTargetRepository.findByGuardianIdAndTargetIdAndStatus(
                        GUARDIAN_ID, TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(Optional.empty());

        ChatRequest request =
                ChatRequest.builder()
                        .message("우리 애 지금 어디야")
                        .careTargetId(targetPublicId.toString())
                        .build();

        // when & then
        assertThatThrownBy(() -> service().chat(GUARDIAN_ID, request))
                .isInstanceOf(AccessDeniedCustomException.class)
                .satisfies(
                        e ->
                                assertThat(((AccessDeniedCustomException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.TARGET_002));
        verify(llmClient, never()).generateAnswer(anyString(), any());
    }

    @Test
    @DisplayName("RAG 검색용 질문 Embedding이 실패해도 빈 맥락으로 LLM을 호출해 대화가 이어진다")
    void chat_queryEmbeddingFails_degradesToEmptyContextAndStillAnswers() {
        // given
        when(embeddingClient.embed(anyString()))
                .thenThrow(new RuntimeException("embedding down"))
                .thenThrow(new RuntimeException("embedding down"));
        when(llmClient.generateAnswer(anyString(), eq(List.of(new LlmClient.Turn("user", "질문")))))
                .thenReturn("답변");
        ChatHistory saved = ChatHistory.create(GUARDIAN_ID, null, "질문", "답변");
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenReturn(saved);

        ChatRequest request = ChatRequest.builder().message("질문").build();

        // when
        ChatResponse response = service().chat(GUARDIAN_ID, request);

        // then — RAG/사후 임베딩 둘 다 실패해도 응답은 정상
        assertThat(response.getAnswer()).isEqualTo("답변");
        verify(chatEmbeddingStore, never()).save(any(), any());
    }

    @Test
    @DisplayName("저장 후 Embedding 생성이 실패해도 이미 만든 ChatHistory 저장/응답에는 영향이 없다")
    void chat_postSaveEmbeddingFails_stillReturnsAnswer() {
        // given
        when(embeddingClient.embed(anyString()))
                .thenReturn(new float[] {0.1f})
                .thenThrow(new RuntimeException("embedding down"));
        when(chatEmbeddingStore.findSimilar(eq(GUARDIAN_ID), eq(null), any(), anyInt()))
                .thenReturn(List.of());
        when(llmClient.generateAnswer(anyString(), any())).thenReturn("답변2");
        ChatHistory saved = ChatHistory.create(GUARDIAN_ID, null, "질문2", "답변2");
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenReturn(saved);

        ChatRequest request = ChatRequest.builder().message("질문2").build();

        // when
        ChatResponse response = service().chat(GUARDIAN_ID, request);

        // then
        assertThat(response.getAnswer()).isEqualTo("답변2");
        verify(chatEmbeddingStore, never()).save(any(), any(float[].class));
    }

    private User mockUserWithId(Long id) {
        User user = org.mockito.Mockito.mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }
}
