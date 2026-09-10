package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 준비가 멈춘 뒤의 두 갈래 — 스케줄러가 걷어내는 길과 사용자가 다시 시도하는 길.
 *
 * <p>둘 다 없으면 준비가 실패한 면접은 되살릴 방법이 없다. 특히 N:1은 폴백할 원질문이 없어,
 * 한 번 실패하면 면접을 새로 만드는 것 말고는 길이 없었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServicePreparationTest {

    @Mock
    private QuestionTailorRepository questionTailorRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private AnalysisDataRepository analysisDataRepository;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private ChatInterviewHandoffService chatInterviewHandoffService;
    @Mock
    private SseNotifier sseNotifier;

    private QuestionTailorService service;
    private UserResponse user;

    @BeforeEach
    void setUp() {
        service = new QuestionTailorService(questionTailorRepository, interviewRepository,
                interviewPersonaRepository, personaRepository,
                analysisDataRepository, aiServerClient, chatInterviewHandoffService, sseNotifier,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "pendingTimeout", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.test");

        when(questionTailorRepository.save(any(QuestionTailorEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(questionTailorRepository.claimChatDelivery(anyLong())).thenReturn(1);
        when(questionTailorRepository.claimExpiration(anyLong())).thenReturn(1);
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview()));
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona()));
        when(analysisDataRepository.findLatestCompleted(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-1")
                        .userId(7L)
                        .result(Map.of("interview", List.of(
                                Map.of("id", 1, "category", "tech_choice", "question", "왜 Redis 를 썼나요?",
                                        "expected_answer", "캐시 선택 근거", "based_on", List.of()))))
                        .build()));

        user = org.mockito.Mockito.mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(user.getMajor()).thenReturn("BACKEND");
    }

    private InterviewEntity interview() {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(11L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build();
    }

    private PersonaEntity persona() {
        return PersonaEntity.builder()
                .personaId(11L)
                .personaName("면접관")
                .role(Role.TECH)
                .major(Major.BACKEND)
                .type(Type.REALISTIC)
                .tone(InterviewTone.DIRECT)
                .level(Level.NORMAL)
                .career(8)
                .gender(Gender.MALE)
                .build();
    }

    private TailoredQuestionResponse question() {
        return TailoredQuestionResponse.builder()
                .id(1).category("tech_choice").question("왜 Redis 를 썼나요?")
                .expectedAnswer("캐시 선택 근거").basedOn(List.of()).build();
    }

    private QuestionTailorEntity tailor(InterviewMode mode, TailorStatus status) {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("job-1")
                .analysisJobId("analysis-1")
                .mode(mode)
                .status(status)
                .chatDelivered(false)
                .sourceQuestions(List.of(question()))
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    private void givenLatest(QuestionTailorEntity tailor) {
        when(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.ofNullable(tailor));
    }

    /**
     * 폴링하지 않고 SSE만 기다리는 클라이언트에게도 실패가 닿아야 한다. 요청이 들어와야만
     * 판정한다면 그런 클라이언트는 구독 타임아웃까지 아무것도 받지 못한다.
     */
    @Test
    void 스윕이_시간이_지난_N대1을_실패로_닫고_알린다() {
        QuestionTailorEntity stale = tailor(InterviewMode.MULTI, TailorStatus.PENDING);
        when(questionTailorRepository.findAllByStatusAndCreatedAtBefore(eq(TailorStatus.PENDING), any()))
                .thenReturn(List.of(stale));

        service.sweepTimedOutPreparations();

        assertThat(stale.getStatus()).isEqualTo(TailorStatus.FAILED);
        // N:1은 폴백할 원질문이 없다. 기술 질문 2개짜리 면접을 여는 편보다 열지 않는 편이 낫다.
        assertThat(stale.getQuestions()).isNull();

        ArgumentCaptor<InterviewReadyResponse> sent = ArgumentCaptor.forClass(InterviewReadyResponse.class);
        verify(sseNotifier).sendFinal(eq("analysis-1"),
                eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), sent.capture());
        assertThat(sent.getValue().getFailureStage()).isEqualTo(FailureStage.QUESTION_GENERATION);
    }

    /** 1:1은 원질문이 남아 있다. 시간이 지나도 그것으로 면접을 열어준다. */
    @Test
    void 스윕이_시간이_지난_일대일은_원질문으로_열어준다() {
        QuestionTailorEntity stale = tailor(InterviewMode.SOLO, TailorStatus.PENDING);
        when(questionTailorRepository.findAllByStatusAndCreatedAtBefore(eq(TailorStatus.PENDING), any()))
                .thenReturn(List.of(stale));

        service.sweepTimedOutPreparations();

        verify(chatInterviewHandoffService).deliver(stale);
        verify(sseNotifier).sendFinal(eq("analysis-1"), eq(SseNotifier.INTERVIEW_READY), any());
    }

    /** 스윕은 여러 인스턴스에서 함께 돈다. 먼저 차지한 쪽만 닫고 알려야 같은 실패가 두 번 나가지 않는다. */
    @Test
    void 다른_쪽이_먼저_차지한_건은_건드리지_않는다() {
        QuestionTailorEntity stale = tailor(InterviewMode.MULTI, TailorStatus.PENDING);
        when(questionTailorRepository.findAllByStatusAndCreatedAtBefore(eq(TailorStatus.PENDING), any()))
                .thenReturn(List.of(stale));
        when(questionTailorRepository.claimExpiration(1L)).thenReturn(0);

        service.sweepTimedOutPreparations();

        assertThat(stale.getStatus()).isEqualTo(TailorStatus.PENDING);
        verify(sseNotifier, never()).sendFinal(any(), any(), any());
    }

    /** 한 건이 걸려 넘어져도 나머지는 정리돼야 한다. 아니면 그 뒤의 모든 면접이 같이 매달린다. */
    @Test
    void 한_건이_실패해도_나머지를_계속_정리한다() {
        QuestionTailorEntity broken = tailor(InterviewMode.SOLO, TailorStatus.PENDING);
        broken.setTailorId(1L);
        QuestionTailorEntity next = tailor(InterviewMode.MULTI, TailorStatus.PENDING);
        next.setTailorId(2L);
        when(questionTailorRepository.findAllByStatusAndCreatedAtBefore(eq(TailorStatus.PENDING), any()))
                .thenReturn(List.of(broken, next));
        when(chatInterviewHandoffService.deliver(broken)).thenThrow(new IllegalStateException("터짐"));

        service.sweepTimedOutPreparations();

        assertThat(next.getStatus()).isEqualTo(TailorStatus.FAILED);
    }

    @Test
    void 질문_생성에_실패한_건은_다시_시도하면_새로_만든다() {
        QuestionTailorEntity failed = tailor(InterviewMode.SOLO, TailorStatus.FAILED);
        failed.setQuestions(List.of());
        givenLatest(failed);

        service.retryPreparation(interview(), user);

        // 만들 질문이 없어서 멈춘 건이다. 분석 서버에 다시 접수해야 한다.
        verify(aiServerClient).tailorQuestions(any(QuestionTailorRequest.class));
    }

    /** 질문은 이미 준비돼 있다. 다시 만들면 사용자가 이미 본 질문이 까닭 없이 바뀐다. */
    @Test
    void 전달에만_실패한_건은_전달만_다시_한다() {
        QuestionTailorEntity failed = tailor(InterviewMode.SOLO, TailorStatus.SUCCEEDED);
        failed.setQuestions(List.of(question()));
        failed.setChatErrorMessage("채팅 서버에 오류가 발생했습니다.");
        givenLatest(failed);

        service.retryPreparation(interview(), user);

        verify(chatInterviewHandoffService).deliver(failed);
        verify(aiServerClient, never()).tailorQuestions(any());
    }

    @Test
    void 준비_중인_건에는_새_작업을_만들지_않는다() {
        QuestionTailorEntity pending = tailor(InterviewMode.SOLO, TailorStatus.PENDING);
        // 아직 제한 시간을 넘기지 않았다.
        pending.setCreatedAt(LocalDateTime.now());
        givenLatest(pending);

        assertThatThrownBy(() -> service.retryPreparation(interview(), user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(aiServerClient, never()).tailorQuestions(any());
    }

    /** 이미 열려 있는 면접에 새 작업을 만들면 채팅 서버에 같은 면접이 한 번 더 열린다. */
    @Test
    void 이미_열린_면접은_다시_준비하지_않는다() {
        QuestionTailorEntity ready = tailor(InterviewMode.SOLO, TailorStatus.SUCCEEDED);
        ready.setQuestions(List.of(question()));
        ready.setChatDelivered(true);
        givenLatest(ready);

        assertThat(service.retryPreparation(interview(), user)).isSameAs(ready);

        verify(aiServerClient, never()).tailorQuestions(any());
        verify(chatInterviewHandoffService, never()).deliver(any());
    }

    /**
     * 콜백을 기다리다 시간만 지난 건 때문에 재시도가 "준비 중"에 막히면 안 된다.
     * 이미 가망이 없는 작업이라 여기서 닫고 이어서 처리해야 한다.
     */
    @Test
    void 시간이_지난_준비_중인_건은_재시도가_먼저_실패로_닫는다() {
        QuestionTailorEntity stale = tailor(InterviewMode.SOLO, TailorStatus.PENDING);
        givenLatest(stale);

        service.retryPreparation(interview(), user);

        assertThat(stale.getStatus()).isEqualTo(TailorStatus.FAILED);
        // 1:1은 원질문이 폴백으로 들어차므로, 남은 일은 채팅 서버로 넘기는 것뿐이다.
        verify(chatInterviewHandoffService).deliver(stale);
    }

    @Test
    void 시작한_적_없는_면접은_재시도할_수_없다() {
        givenLatest(null);

        assertThatThrownBy(() -> service.retryPreparation(interview(), user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
