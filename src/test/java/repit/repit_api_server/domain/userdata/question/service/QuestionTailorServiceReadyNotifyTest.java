package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 준비가 끝났음을 구독에 알리는 경로.
 *
 * <p>웹은 분석 jobId 하나로 구독한 채 면접관을 고르고 면접 시작까지 진행한다. 그 구독을 되찾는
 * 열쇠가 재작성 건에 남겨둔 분석 jobId다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServiceReadyNotifyTest {

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

    @BeforeEach
    void setUp() {
        service = new QuestionTailorService(questionTailorRepository, interviewRepository,
                interviewPersonaRepository, personaRepository,
                analysisDataRepository, aiServerClient, chatInterviewHandoffService, sseNotifier,
                new ObjectMapper());

        when(questionTailorRepository.claimChatDelivery(anyLong())).thenReturn(1);
        when(questionTailorRepository.save(any(QuestionTailorEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build()));
    }

    private QuestionTailorEntity pendingTailor() {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("job-1")
                .analysisJobId("analysis-1")
                .status(TailorStatus.PENDING)
                .chatDelivered(false)
                .sourceQuestions(List.of(TailoredQuestionResponse.builder()
                        .id(1).category("tech_choice").question("왜 Redis 를 썼나요?")
                        .expectedAnswer("선택 근거").basedOn(List.of()).build()))
                .build();
    }

    private QuestionTailorCallbackRequest succeeded() {
        return new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"))),
                null);
    }

    @Test
    void 채팅_서버에_면접이_열리면_준비_완료를_구독에_알린다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(succeeded());

        ArgumentCaptor<InterviewReadyResponse> sent = ArgumentCaptor.forClass(InterviewReadyResponse.class);
        // 웹이 구독한 열쇠는 면접 id가 아니라 분석 jobId다.
        verify(sseNotifier).sendFinal(eq("analysis-1"), eq(SseNotifier.INTERVIEW_READY), sent.capture());

        // 채팅 서버에 붙는 데 필요한 것이 실려야 한다.
        assertThat(sent.getValue().getInterviewId()).isEqualTo(3L);
        assertThat(sent.getValue().getSessionId()).isEqualTo("sess-1");
        assertThat(sent.getValue().getTailored()).isTrue();
    }

    /** 재작성이 실패해도 원질문 폴백으로 면접은 그대로 열린다. 여기서 실패로 알리면 웹은 입장하지 않는다. */
    @Test
    void 재작성이_실패해도_원질문으로_열리면_준비_완료로_알린다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "failed", null,
                new QuestionTailorCallbackRequest.Error(502, "질문 재작성에 실패했습니다.")));

        ArgumentCaptor<InterviewReadyResponse> sent = ArgumentCaptor.forClass(InterviewReadyResponse.class);
        verify(sseNotifier).sendFinal(eq("analysis-1"), eq(SseNotifier.INTERVIEW_READY), sent.capture());
        assertThat(sent.getValue().getTailored()).isFalse();
        verify(sseNotifier, never())
                .sendFinal(any(), eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), any());
    }

    /** 못 여는 것은 채팅 서버 전달이 실패했을 때뿐이다. 그때는 웹이 입장하면 안 된다. */
    @Test
    void 채팅_서버에_열지_못하면_준비_실패로_알린다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));
        doThrow(new ExternalApiException("채팅 서버에 오류가 발생했습니다.", null, null))
                .when(chatInterviewHandoffService).deliver(any());

        service.handleCallback(succeeded());

        verify(sseNotifier).sendFinal(eq("analysis-1"),
                eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), any(InterviewReadyResponse.class));
        verify(sseNotifier, never()).sendFinal(any(), eq(SseNotifier.INTERVIEW_READY), any());
    }

    @Test
    void 전달이_끝난_건만_뒤늦은_구독에_되짚는다() {
        QuestionTailorEntity delivered = pendingTailor();
        delivered.setStatus(TailorStatus.SUCCEEDED);
        delivered.setTailored(true);
        delivered.setChatDelivered(true);
        when(questionTailorRepository.findTopByAnalysisJobIdOrderByCreatedAtDesc("analysis-1"))
                .thenReturn(Optional.of(delivered));

        QuestionTailorService.PreparationEvent event = service.findPreparationEvent("analysis-1");

        assertThat(event).isNotNull();
        assertThat(event.eventName()).isEqualTo(SseNotifier.INTERVIEW_READY);
        assertThat(event.payload().getSessionId()).isEqualTo("sess-1");
    }

    /**
     * 전달에 실패한 건은 준비된 것으로 보지 않는다. 그렇다고 아무것도 되짚지 않으면 그 사이에
     * 붙은 구독은 타임아웃까지 매달리므로, 실패로 되짚어 보낸다.
     */
    @Test
    void 전달에_실패한_건은_전달_단계_실패로_되짚는다() {
        QuestionTailorEntity failed = pendingTailor();
        failed.setStatus(TailorStatus.SUCCEEDED);
        failed.setChatDelivered(true);
        failed.setChatErrorMessage("채팅 서버에 오류가 발생했습니다.");
        failed.setQuestions(failed.getSourceQuestions());
        when(questionTailorRepository.findTopByAnalysisJobIdOrderByCreatedAtDesc("analysis-1"))
                .thenReturn(Optional.of(failed));

        QuestionTailorService.PreparationEvent event = service.findPreparationEvent("analysis-1");

        assertThat(event.eventName()).isEqualTo(SseNotifier.INTERVIEW_PREPARATION_FAILED);
        // 질문은 이미 준비돼 있다. 다시 만들 것 없이 전달만 다시 하면 된다.
        assertThat(event.payload().getFailureStage()).isEqualTo(FailureStage.CHAT_DELIVERY);
        assertThat(event.payload().getRetryable()).isTrue();
    }

    /** N:1이 폴백 없이 닫힌 건. 되짚지 않으면 웹은 준비 중 화면에서 15분을 기다린다. */
    @Test
    void 질문을_만들지_못한_건은_생성_단계_실패로_되짚는다() {
        QuestionTailorEntity failed = pendingTailor();
        failed.setMode(InterviewMode.MULTI);
        failed.setStatus(TailorStatus.FAILED);
        failed.setChatDelivered(false);
        failed.setQuestions(null);
        failed.setErrorMessage("질문을 준비하지 못했습니다.");
        when(questionTailorRepository.findTopByAnalysisJobIdOrderByCreatedAtDesc("analysis-1"))
                .thenReturn(Optional.of(failed));

        QuestionTailorService.PreparationEvent event = service.findPreparationEvent("analysis-1");

        assertThat(event.eventName()).isEqualTo(SseNotifier.INTERVIEW_PREPARATION_FAILED);
        assertThat(event.payload().getFailureStage()).isEqualTo(FailureStage.QUESTION_GENERATION);
        assertThat(event.payload().getErrorMessage()).isEqualTo("질문을 준비하지 못했습니다.");
    }

    @Test
    void 아직_준비_중인_건은_되짚지_않는다() {
        when(questionTailorRepository.findTopByAnalysisJobIdOrderByCreatedAtDesc("analysis-1"))
                .thenReturn(Optional.of(pendingTailor()));

        assertThat(service.findPreparationEvent("analysis-1")).isNull();
    }
}
