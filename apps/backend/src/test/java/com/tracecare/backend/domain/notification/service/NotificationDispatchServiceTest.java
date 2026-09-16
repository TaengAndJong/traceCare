package com.tracecare.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;
import com.tracecare.backend.domain.guardian.entity.GuardianTarget;
import com.tracecare.backend.domain.guardian.repository.GuardianTargetRepository;
import com.tracecare.backend.domain.notification.entity.NotificationHistory;
import com.tracecare.backend.domain.notification.fcm.FcmSender;
import com.tracecare.backend.domain.notification.repository.NotificationHistoryRepository;
import com.tracecare.backend.domain.place.repository.PlaceRepository;

/**
 * dispatchArrival() 트랜잭션 분리 리팩터링(§4.2 이후 버그 수정) 전 회귀 방지 기준선이다. 여기 있는 테스트는 리팩터링 전/후 동일하게
 * 통과해야 한다 — "결과(저장된 데이터, 발송 여부)가 동일함을 증명"하는 게 목적이라 {@code @Transactional} 유무 같은 구현 방식은
 * 검증하지 않는다(그건 트랜잭션 경계가 실제로 동작하는 Testcontainers 통합 테스트에서만 증명 가능,
 * {@code NotificationDispatchServiceIntegrationTest} 참고).
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    private static final Long CARE_TARGET_ID = 1L;

    @Mock private GuardianTargetRepository guardianTargetRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationHistoryRepository notificationHistoryRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private FcmSender fcmSender;

    private NotificationDispatchService service() {
        return new NotificationDispatchService(
                guardianTargetRepository,
                userRepository,
                notificationHistoryRepository,
                placeRepository,
                fcmSender);
    }

    private GuardianTarget guardian(Long guardianTargetId, Long guardianId) {
        GuardianTarget guardianTarget =
                GuardianTarget.createActive(guardianId, CARE_TARGET_ID, GuardianTarget.ROLE_SUB);
        org.springframework.test.util.ReflectionTestUtils.setField(
                guardianTarget, "id", guardianTargetId);
        return guardianTarget;
    }

    private User careTarget() {
        User user = User.createFromOAuth("target@example.com", "GOOGLE", "oauth-id");
        user.confirmRole("CARE_TARGET", "테스트대상", LocalDate.of(2015, 1, 1));
        return user;
    }

    @Test
    @DisplayName("ACTIVE Guardian이 여러 명이면 각각에게 개별 NotificationHistory를 생성한다")
    void dispatchArrival_multipleGuardians_createsNotificationForEach() {
        // given
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian(1L, 11L), guardian(2L, 22L)));
        when(userRepository.findById(CARE_TARGET_ID)).thenReturn(Optional.of(careTarget()));
        when(fcmSender.send(anyLong(), any(), any())).thenReturn(true);

        // when
        service().dispatchArrival(CARE_TARGET_ID, "우리집");

        // then
        ArgumentCaptor<NotificationHistory> captor =
                ArgumentCaptor.forClass(NotificationHistory.class);
        verify(notificationHistoryRepository, times(2)).save(captor.capture());
        List<Long> savedGuardianIds = captor.getAllValues().stream().map(NotificationHistory::getUserId).toList();
        assertThat(savedGuardianIds).containsExactlyInAnyOrder(11L, 22L);
        assertThat(captor.getAllValues())
                .allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(NotificationHistory.STATUS_SENT));
    }

    @Test
    @DisplayName("FCM 발송이 실패(false 반환)해도 예외를 던지지 않고 status=FAILED로 이력을 남긴다")
    void dispatchArrival_sendFails_marksFailedButStillSaves() {
        // given
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of(guardian(1L, 11L)));
        when(userRepository.findById(CARE_TARGET_ID)).thenReturn(Optional.of(careTarget()));
        when(fcmSender.send(eq(11L), any(), any())).thenReturn(false);

        // when
        service().dispatchArrival(CARE_TARGET_ID, "우리집");

        // then
        ArgumentCaptor<NotificationHistory> captor =
                ArgumentCaptor.forClass(NotificationHistory.class);
        verify(notificationHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationHistory.STATUS_FAILED);
    }

    @Test
    @DisplayName("ACTIVE Guardian이 없으면 아무 조회/저장도 하지 않고 즉시 반환한다")
    void dispatchArrival_noActiveGuardians_doesNothing() {
        // given
        when(guardianTargetRepository.findByTargetIdAndStatus(
                        CARE_TARGET_ID, GuardianTarget.STATUS_ACTIVE))
                .thenReturn(List.of());

        // when
        service().dispatchArrival(CARE_TARGET_ID, "우리집");

        // then
        verify(notificationHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(fcmSender, org.mockito.Mockito.never()).send(anyLong(), any(), any());
    }
}
