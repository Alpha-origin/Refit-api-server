package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.global.client.AiServerClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N:1 질문 구성 콜백.
 *
 * <p>1:1 재작성과 갈리는 지점은 두 곳이다 — 신규 질문 4개는 콜백이 유일한 원본이라 채점 기준을
 * 함께 받아 남겨야 하고, 실패했을 때 돌아갈 원질문이 없어 폴백하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServiceMultiCallbackTest {

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

    @Captor
    private ArgumentCaptor<QuestionTailorEntity> savedTailor;

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
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(pendingMultiTailor()));
    }

    private QuestionTailorEntity pendingMultiTailor() {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("job-1")
                .analysisJobId("analysis-1")
                .mode(InterviewMode.MULTI)
                .status(TailorStatus.PENDING)
                .chatDelivered(false)
                // 기술 면접관에게 넘긴 원질문 2개만 남아 있다. 나머지 4문항은 아직 존재하지 않는다.
                .sourceQuestions(List.of(TailoredQuestionResponse.builder()
                        .id(2)
                        .category("tech_choice")
                        .question("왜 Redis 를 썼나요?")
                        .expectedAnswer("캐시 계층 선택 근거와 대안 비교")
                        .build()))
                .build();
    }

    private QuestionTailorEntity lastSaved() {
        verify(questionTailorRepository, atLeastOnce()).save(savedTailor.capture());
        return savedTailor.getValue();
    }

    private QuestionTailorMultiCallbackRequest.Question question(int id, long personaId, String content) {
        return new QuestionTailorMultiCallbackRequest.Question(id, personaId, "tech_choice", content,
                "이 질문으로 확인할 것 " + id, List.of("order-api/CacheConfig.java"));
    }

    @Test
    void 질문에_붙어온_면접관과_채점_기준을_그대로_남긴다() {
        service.handleMultiCallback(new QuestionTailorMultiCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorMultiCallbackRequest.Result(List.of(
                        question(2, 11L, "다시 쓴 Redis 질문"),
                        question(6, 12L, "팀에서 갈등이 있었다면?"))),
                null));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        // 배열 순서가 그대로 면접 진행 순서다.
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getId).containsExactly(2, 6);
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getPersonaId)
                .containsExactly(11L, 12L);
        // 신규 질문의 채점 기준은 이 값뿐이다. 버리면 되찾을 데가 없다.
        assertThat(saved.getQuestions().get(1).getExpectedAnswer()).isEqualTo("이 질문으로 확인할 것 6");
    }

    /**
     * 실패를 알리지 않으면 구독은 15분 타임아웃까지 매달린다. 준비 제한 시간은 2분이라
     * 사용자는 그 차이만큼 아무 소식 없이 준비 중 화면을 보게 된다.
     */
    @Test
    void 질문을_만들지_못하면_실패를_구독에_알린다() {
        service.handleMultiCallback(new QuestionTailorMultiCallbackRequest("job-1", "3", "failed", null,
                new QuestionTailorMultiCallbackRequest.Error(502, "질문 생성에 실패했습니다.")));

        ArgumentCaptor<InterviewReadyResponse> sent = ArgumentCaptor.forClass(InterviewReadyResponse.class);
        verify(sseNotifier).sendFinal(eq("analysis-1"),
                eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), sent.capture());

        // 질문부터 다시 만들어야 하는 실패다. 전달만 다시 하면 되는 실패와 구분돼야 한다.
        assertThat(sent.getValue().getFailureStage()).isEqualTo(FailureStage.QUESTION_GENERATION);
        assertThat(sent.getValue().getRetryable()).isTrue();
        assertThat(sent.getValue().getErrorMessage()).isEqualTo("질문 생성에 실패했습니다.");
        // 넘길 질문이 없으니 채팅 서버로는 가지 않는다.
        verify(chatInterviewHandoffService, never()).deliver(any());
    }

    @Test
    void 실패하면_원질문으로_폴백하지_않고_면접을_열지_않는다() {
        service.handleMultiCallback(new QuestionTailorMultiCallbackRequest("job-1", "3", "failed", null,
                new QuestionTailorMultiCallbackRequest.Error(502, "질문 생성에 실패했습니다.")));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.FAILED);
        // 기술 질문 2개짜리 면접이 N:1인 척 열리는 편보다 열지 않는 편이 낫다.
        assertThat(saved.getQuestions()).isNull();
        assertThat(saved.getErrorMessage()).isEqualTo("질문 생성에 실패했습니다.");
        verify(chatInterviewHandoffService, never()).deliver(any());
    }

    @Test
    void 쓸_수_있는_질문이_하나도_없으면_실패로_닫는다() {
        service.handleMultiCallback(new QuestionTailorMultiCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorMultiCallbackRequest.Result(List.of(
                        new QuestionTailorMultiCallbackRequest.Question(6, 12L, "hr", "  ", "의도", List.of()))),
                null));

        assertThat(lastSaved().getStatus()).isEqualTo(TailorStatus.FAILED);
        verify(chatInterviewHandoffService, never()).deliver(any());
    }

    @Test
    void 성공하면_채팅_서버로_넘긴다() {
        service.handleMultiCallback(new QuestionTailorMultiCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorMultiCallbackRequest.Result(List.of(question(2, 11L, "다시 쓴 Redis 질문"))),
                null));

        verify(chatInterviewHandoffService).deliver(any());
        assertThat(lastSaved().getChatDelivered()).isTrue();
    }
}
