package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackMultiRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1:1 피드백 요청을 분석 서버 본문으로 옮기는 부분.
 *
 * <p>면접 내용은 우리 DB에서 읽는다. 채팅 서버는 면접이 끝나면 기록을 이 서버로 넘기고 곧바로
 * 세션을 지우므로, 피드백을 요청하는 시점에는 물어볼 상대가 없다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceSoloRequestTest {

    @Mock
    private FeedbackRepository feedbackRepository;
    @Mock
    private FeedbackItemRepository feedbackItemRepository;
    @Mock
    private FeedbackPersonaRepository feedbackPersonaRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AiServerClient aiServerClient;

    /** 인증을 마친 요청의 주인. 확인은 시큐리티 필터가 끝냈고, 서비스는 id만 받는다. */
    private static final Long USER_ID = 7L;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, feedbackItemRepository, feedbackPersonaRepository,
                interviewRepository, interviewPersonaRepository, personaRepository, questionRepository,
                answerRepository, aiServerClient);
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");

        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.COMPLETED, 5L)));
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(FeedbackEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.REALISTIC)));

        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(questions());
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(answers());
    }

    private InterviewEntity interview(Status status, Long personaId) {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(personaId)
                .mode(personaId == null ? InterviewMode.MULTI : InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(status)
                .build();
    }

    private PersonaEntity hrPersona() {
        return PersonaEntity.builder()
                .personaId(6L)
                .personaName("박인사")
                .role(Role.HR)
                .type(Type.FRIENDLY)
                .tone(InterviewTone.GENTLE)
                .level(Level.NORMAL)
                .career(5)
                .gender(Gender.FEMALE)
                .build();
    }

    private PersonaEntity persona(Type type) {
        return PersonaEntity.builder()
                .personaId(5L)
                .personaName("김테크")
                .role(Role.TECH)
                .type(type)
                .tone(InterviewTone.PRESSURING)
                .level(Level.NORMAL)
                .career(7)
                .gender(Gender.MALE)
                .build();
    }

    /** 저장된 면접 기록. 901은 최초 질문, 902는 901에 달린 꼬리질문이다. */
    private List<QuestionEntity> questions() {
        return List.of(
                QuestionEntity.builder()
                        .questionId(901L)
                        .interviewId(3L)
                        .chatQuestionId(1L)
                        .personaId(5L)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL)
                        .intention("도입 근거 확인")
                        .content("WebFlux 를 도입한 이유가 무엇인가요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                        .build(),
                QuestionEntity.builder()
                        .questionId(902L)
                        .interviewId(3L)
                        .chatQuestionId(-77L)
                        .parentId(901L)
                        .personaId(5L)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.FOLLOW)
                        .intention("대안 검토 확인")
                        .content("가상 스레드는 고려하지 않으셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build());
    }

    private List<AnswerEntity> answers() {
        return List.of(AnswerEntity.builder()
                .answerId(501L)
                .interviewId(3L)
                .questionId(901L)
                .userId(7L)
                .responseTime(90)
                .content("스레드가 I/O 대기에 묶였습니다.")
                .createdAt(LocalDateTime.parse("2026-08-18T01:01:30"))
                .build());
    }

    private FeedbackSoloRequest captureRequest() {
        ArgumentCaptor<FeedbackSoloRequest> sent = ArgumentCaptor.forClass(FeedbackSoloRequest.class);
        verify(aiServerClient).requestSoloFeedback(sent.capture());
        return sent.getValue();
    }

    @Test
    void 면접_내용을_우리_DB에서_읽는다() {
        service.requestFeedback(USER_ID, 3L);

        FeedbackSoloRequest request = captureRequest();
        assertThat(request.getQuestions()).extracting(FeedbackSoloRequest.Question::getQuestionId)
                .containsExactly("901", "902");
        assertThat(request.getQuestions().get(0).getContent())
                .isEqualTo("WebFlux 를 도입한 이유가 무엇인가요?");
        assertThat(request.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void 꼬리질문에만_부모를_싣는다() {
        service.requestFeedback(USER_ID, 3L);

        List<FeedbackSoloRequest.Question> questions = captureRequest().getQuestions();
        // ORIGINAL에 parentId가 실려 있거나 FOLLOW에 없으면 분석 서버가 요청 전체를 거부한다.
        assertThat(questions.get(0).getParentId()).isNull();
        assertThat(questions.get(1).getParentId()).isEqualTo("901");
    }

    @Test
    void 답변에는_저장된_답변_번호를_싣는다() {
        service.requestFeedback(USER_ID, 3L);

        FeedbackSoloRequest.Answer answer = captureRequest().getAnswers().get(0);
        assertThat(answer.getAnswerId()).isEqualTo("501");
        assertThat(answer.getQuestionId()).isEqualTo("901");
        assertThat(answer.getContent()).isEqualTo("스레드가 I/O 대기에 묶였습니다.");
    }

    @Test
    void 오프셋_없는_시각을_UTC로_옮겨_보낸다() {
        service.requestFeedback(USER_ID, 3L);

        FeedbackSoloRequest request = captureRequest();
        assertThat(request.getQuestions().get(0).getCreatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-18T01:00:00Z"));
        assertThat(request.getAnswers().get(0).getCreatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-18T01:01:30Z"));
    }

    @Test
    void 면접관_성향은_우리_DB에서_읽는다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.METICULOUS)));

        service.requestFeedback(USER_ID, 3L);

        assertThat(captureRequest().getPersonaType()).isEqualTo("METICULOUS");
    }

    @Test
    void 면접관_어조도_성향과_함께_보낸다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.METICULOUS)));

        service.requestFeedback(USER_ID, 3L);

        // 어조는 성향과 독립된 축이다. 성향만 보내면 분석 서버가 세기를 성향에서 유추하게 된다.
        assertThat(captureRequest().getPersonaTone()).isEqualTo("PRESSURING");
    }

    @Test
    void 면접관이_여럿이면_N대1_채점으로_보낸다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.COMPLETED, null)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(5L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(6L).personaOrder(1).build()));
        when(personaRepository.findAllById(List.of(5L, 6L))).thenReturn(List.of(persona(Type.REALISTIC), hrPersona()));

        service.requestFeedback(USER_ID, 3L);

        // 1:1 채점에 태우면 질문이 누구 것인지 잃고 면접관별 평가도 오지 않는다.
        verify(aiServerClient, never()).requestSoloFeedback(any());
        ArgumentCaptor<FeedbackMultiRequest> sent = ArgumentCaptor.forClass(FeedbackMultiRequest.class);
        verify(aiServerClient).requestMultiFeedback(sent.capture());

        FeedbackMultiRequest request = sent.getValue();
        assertThat(request.getPersonas()).extracting(FeedbackMultiRequest.Persona::getRole)
                .containsExactly("TECH", "HR");
        assertThat(request.getPersonas()).extracting(FeedbackMultiRequest.Persona::getStyle)
                .containsExactly("REALISTIC", "FRIENDLY");
        assertThat(request.getPersonas()).extracting(FeedbackMultiRequest.Persona::getTone)
                .containsExactly("PRESSURING", "GENTLE");
        assertThat(request.getQuestions()).extracting(FeedbackMultiRequest.Question::getPersonaId)
                .containsExactly("5", "5");
    }

    @Test
    void 면접관이_비어_있는_꼬리질문은_부모의_면접관을_물려받는다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.COMPLETED, null)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(5L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(6L).personaOrder(1).build()));
        when(personaRepository.findAllById(List.of(5L, 6L))).thenReturn(List.of(persona(Type.REALISTIC), hrPersona()));
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of(
                QuestionEntity.builder()
                        .questionId(901L)
                        .interviewId(3L)
                        .personaId(6L)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL)
                        .intention("지원 동기 확인")
                        .content("왜 지원하셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                        .build(),
                QuestionEntity.builder()
                        .questionId(902L)
                        .interviewId(3L)
                        .parentId(901L)
                        // 채팅 서버가 면접관을 달기 전 기록에는 이 자리가 비어 있다.
                        .personaId(null)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.FOLLOW)
                        .intention("동기의 구체성 확인")
                        .content("그 중 어떤 점이 그랬나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build()));

        service.requestFeedback(USER_ID, 3L);

        ArgumentCaptor<FeedbackMultiRequest> sent = ArgumentCaptor.forClass(FeedbackMultiRequest.class);
        verify(aiServerClient).requestMultiFeedback(sent.capture());
        assertThat(sent.getValue().getQuestions()).extracting(FeedbackMultiRequest.Question::getPersonaId)
                .containsExactly("6", "6");
    }

    @Test
    void 의도가_비어도_그_질문만_비운_채_채점을_받는다() {
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of(
                questions().get(0),
                QuestionEntity.builder()
                        .questionId(902L)
                        .interviewId(3L)
                        .parentId(901L)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.FOLLOW)
                        .intention("  ")
                        .content("가상 스레드는 고려하지 않으셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build()));

        service.requestFeedback(USER_ID, 3L);

        // 분석 서버는 빈 의도를 받지 않는다. 한 건 때문에 면접 전체가 채점되지 않으면 안 된다.
        List<FeedbackSoloRequest.Question> questions = captureRequest().getQuestions();
        assertThat(questions).hasSize(2);
        assertThat(questions.get(1).getIntention()).isNotBlank();
    }

    @Test
    void 질문_없는_답변은_채점에서_제외한다() {
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(List.of(
                answers().get(0),
                AnswerEntity.builder()
                        .answerId(502L)
                        .interviewId(3L)
                        .questionId(999L)
                        .userId(7L)
                        .content("어느 질문에도 붙지 않는 답변")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:04:00"))
                        .build()));

        service.requestFeedback(USER_ID, 3L);

        assertThat(captureRequest().getAnswers()).extracting(FeedbackSoloRequest.Answer::getAnswerId)
                .containsExactly("501");
    }

    @Test
    void 부모를_잃은_꼬리질문은_홑질문으로_보낸다() {
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of(
                questions().get(0),
                QuestionEntity.builder()
                        .questionId(902L)
                        .interviewId(3L)
                        // 결과 저장이 부모를 찾지 못하면 이 자리가 빈 채로 남는다.
                        .parentId(null)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.FOLLOW)
                        .intention("대안 검토 확인")
                        .content("가상 스레드는 고려하지 않으셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build()));

        service.requestFeedback(USER_ID, 3L);

        // 부모 없는 FOLLOW를 그대로 보내면 분석 서버가 요청 전체를 422로 거부한다.
        List<FeedbackSoloRequest.Question> questions = captureRequest().getQuestions();
        assertThat(questions).hasSize(2);
        assertThat(questions.get(1).getType())
                .isEqualTo(repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL);
        assertThat(questions.get(1).getParentId()).isNull();
    }

    @Test
    void 부모가_채점_목록에_없는_꼬리질문도_홑질문으로_보낸다() {
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of(
                QuestionEntity.builder()
                        .questionId(902L)
                        .interviewId(3L)
                        // 가리키는 질문이 이 면접에 없다.
                        .parentId(555L)
                        .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.FOLLOW)
                        .intention("대안 검토 확인")
                        .content("가상 스레드는 고려하지 않으셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build()));
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(List.of(
                AnswerEntity.builder()
                        .answerId(501L)
                        .interviewId(3L)
                        .questionId(902L)
                        .userId(7L)
                        .content("측정은 못 해봤습니다.")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:40"))
                        .build()));

        service.requestFeedback(USER_ID, 3L);

        List<FeedbackSoloRequest.Question> questions = captureRequest().getQuestions();
        assertThat(questions.get(0).getType())
                .isEqualTo(repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL);
        assertThat(questions.get(0).getParentId()).isNull();
    }

    @Test
    void 면접이_끝나지_않았으면_그렇게_알린다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.IN_PROGRESS, 5L)));
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of());
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.requestFeedback(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("면접이 아직 끝나지 않았습니다");

        verify(aiServerClient, never()).requestSoloFeedback(any());
    }
}
