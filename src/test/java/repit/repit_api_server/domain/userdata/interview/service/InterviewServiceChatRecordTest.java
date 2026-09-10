package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewQnAResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 전체 기록 조회(GET /api/interviews/chat).
 *
 * <p>채팅 서버는 면접이 끝나면 기록을 넘기고 곧바로 세션을 지운다. 끝난 면접을 채팅 서버에
 * 물으면 없는 세션이라 실패하므로, 저장된 기록이 있으면 우리 DB에서 읽어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceChatRecordTest {

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;

    private InterviewService service;

    /** 인증을 마친 요청의 주인. 확인은 시큐리티 필터가 끝냈고, 서비스는 id만 받는다. */
    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);


        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(5L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                .build()));
    }

    /** 901은 최초 질문, 902는 901에 달린 꼬리질문, 903은 답하지 않고 넘어간 질문이다. */
    private List<QuestionEntity> storedQuestions() {
        return List.of(
                QuestionEntity.builder()
                        .questionId(901L)
                        .interviewId(3L)
                        .chatQuestionId(1L)
                        .personaId(5L)
                        .type(Type.ORIGINAL)
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
                        .type(Type.FOLLOW)
                        .intention("대안 검토 확인")
                        .content("가상 스레드는 고려하지 않으셨나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:02:00"))
                        .build(),
                QuestionEntity.builder()
                        .questionId(903L)
                        .interviewId(3L)
                        .chatQuestionId(2L)
                        .personaId(5L)
                        .type(Type.ORIGINAL)
                        .intention("운영 경험 확인")
                        .content("장애 대응 경험이 있나요?")
                        .createdAt(LocalDateTime.parse("2026-08-18T01:03:00"))
                        .build());
    }

    private List<AnswerEntity> storedAnswers() {
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

    private void givenStoredRecord() {
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(storedQuestions());
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(storedAnswers());
    }

    @Test
    void 끝난_면접은_채팅_서버에_묻지_않는다() {
        givenStoredRecord();

        service.getChatInterview(USER_ID, 3L);

        // 세션은 이미 지워졌다. 물으면 실패한다.
        verify(chatServerClient, never()).getInterview(anyString());
    }

    @Test
    void 저장된_기록으로_면접_전체를_돌려준다() {
        givenStoredRecord();

        ChatInterviewAllResponse response = service.getChatInterview(USER_ID, 3L);

        assertThat(response.getSessionId()).isEqualTo("sess-1");
        assertThat(response.getInterviewId()).isEqualTo(3L);
        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(response.getCurrentQuestionIndex()).isEqualTo(3);
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-08-18T01:00:00"));
        assertThat(response.getQnAResponses()).hasSize(3);
    }

    @Test
    void 질문_번호는_우리_PK로_내려간다() {
        givenStoredRecord();

        List<ChatInterviewQnAResponse> qnAs = service.getChatInterview(USER_ID, 3L).getQnAResponses();

        // 채팅 서버 번호(1, -77, 2)는 면접 안에서만 유일해 밖에서는 가리키는 것이 없다.
        assertThat(qnAs).extracting(qnA -> qnA.getQuestion().getQuestionId())
                .containsExactly(901L, 902L, 903L);
        assertThat(qnAs.get(1).getQuestion().getParentId()).isEqualTo(901L);
        assertThat(qnAs.get(1).getQuestion().getQuestionType()).isEqualTo(Type.FOLLOW);
        assertThat(qnAs.get(0).getQuestion().getQuestionContent())
                .isEqualTo("WebFlux 를 도입한 이유가 무엇인가요?");
    }

    @Test
    void 답변을_질문에_매달아_내려준다() {
        givenStoredRecord();

        List<ChatInterviewQnAResponse> qnAs = service.getChatInterview(USER_ID, 3L).getQnAResponses();

        assertThat(qnAs.get(0).getAnswer().getQuestionId()).isEqualTo(901L);
        assertThat(qnAs.get(0).getAnswer().getAnswerContent()).isEqualTo("스레드가 I/O 대기에 묶였습니다.");
        assertThat(qnAs.get(0).getAnswer().getResponseTime()).isEqualTo(90);
        assertThat(qnAs.get(0).getAnswer().getAnswerCreatedAt())
                .isEqualTo(LocalDateTime.parse("2026-08-18T01:01:30"));
    }

    @Test
    void 답하지_않고_넘어간_질문은_답변을_비운다() {
        givenStoredRecord();

        List<ChatInterviewQnAResponse> qnAs = service.getChatInterview(USER_ID, 3L).getQnAResponses();

        assertThat(qnAs.get(1).getAnswer()).isNull();
        assertThat(qnAs.get(2).getAnswer()).isNull();
    }

    @Test
    void 진행_중인_면접은_채팅_서버_세션을_읽는다() {
        // 채팅 면접 질문은 결과가 넘어올 때 한꺼번에 들어온다. 그 전에는 우리 DB가 비어 있다.
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of());
        when(chatServerClient.getInterview("sess-1")).thenReturn(new ChatInterviewAllResponse());

        service.getChatInterview(USER_ID, 3L);

        verify(chatServerClient).getInterview("sess-1");
    }

    @Test
    void 없는_면접이면_알린다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getChatInterview(USER_ID, 3L))
                .isInstanceOf(BusinessException.class);
    }
}
