package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채팅 서버가 면접을 마치고 넘겨주는 기록을 저장하는 부분.
 *
 * <p>채팅 서버 질문 번호는 우리 PK가 아니다. 그 번호를 우리 PK로 옮기는 자리가 여기뿐이라,
 * 어긋나면 답변이 엉뚱한 질문에 붙거나 통째로 사라진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceSaveResultTest {

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

    @Captor
    private ArgumentCaptor<List<AnswerEntity>> savedAnswers;

    private InterviewService service;

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
                .status(Status.IN_PROGRESS)
                .build()));
        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(call -> call.getArgument(0));

        // 저장하면 DB가 PK를 발급한다. 채팅 서버 번호와 겹치지 않는 값을 준다.
        AtomicLong nextId = new AtomicLong(900L);
        when(questionRepository.save(any(QuestionEntity.class))).thenAnswer(call -> {
            QuestionEntity given = call.getArgument(0);
            return QuestionEntity.builder()
                    .questionId(nextId.incrementAndGet())
                    .interviewId(given.getInterviewId())
                    .parentId(given.getParentId())
                    .chatQuestionId(given.getChatQuestionId())
                    .personaId(given.getPersonaId())
                    .type(given.getType())
                    .intention(given.getIntention())
                    .content(given.getContent())
                    .createdAt(given.getCreatedAt())
                    .build();
        });
    }

    /** 채팅 서버가 보내는 형태 그대로. 꼬리질문(-1)은 부모(1)보다 뒤에 온다. */
    private SaveInterviewRequest request() {
        SaveInterviewRequest.QnA first = new SaveInterviewRequest.QnA(
                new SaveInterviewRequest.Question(1L, 5L, "tech_choice",
                        "WebFlux 를 도입한 이유가 무엇인가요?", "도입 근거 확인",
                        List.of("order-api/src/router.java")),
                new SaveInterviewRequest.Answer(1L, 90, "스레드가 I/O 대기에 묶였습니다.",
                        LocalDateTime.parse("2026-08-18T01:01:30")));

        // 꼬리질문은 채팅 서버가 면접 중에 만든 것이라 기대 답변이 없고, 번호가 음수다.
        SaveInterviewRequest.QnA follow = new SaveInterviewRequest.QnA(
                new SaveInterviewRequest.Question(-1L, 5L, "대안 검토 확인",
                        "가상 스레드는 고려하지 않으셨나요?", null, null),
                new SaveInterviewRequest.Answer(-1L, 40, "측정은 못 해봤습니다.",
                        LocalDateTime.parse("2026-08-18T01:02:40")));

        SaveInterviewRequest.QnA unanswered = new SaveInterviewRequest.QnA(
                new SaveInterviewRequest.Question(2L, 5L, "ops",
                        "장애 대응 경험이 있나요?", "운영 경험 확인", null),
                null);

        return new SaveInterviewRequest("sess-1", 3L, 7L, Status.COMPLETED,
                LocalDateTime.parse("2026-08-18T01:00:00"), List.of(first, follow, unanswered));
    }

    private List<QuestionEntity> savedQuestions() {
        ArgumentCaptor<QuestionEntity> saved = ArgumentCaptor.forClass(QuestionEntity.class);
        verify(questionRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        return saved.getAllValues();
    }

    private List<AnswerEntity> savedAnswers() {
        verify(answerRepository).saveAll(savedAnswers.capture());
        return savedAnswers.getValue();
    }

    @Test
    void 채팅_서버_질문_번호를_따로_남긴다() {
        service.saveInterview(request());

        List<QuestionEntity> questions = savedQuestions();
        assertThat(questions).extracting(QuestionEntity::getChatQuestionId)
                .containsExactly(1L, -1L, 2L);
        assertThat(questions).extracting(QuestionEntity::getInterviewId)
                .containsOnly(3L);
        // 채팅 서버는 질문마다의 시각을 보내지 않는다. not null 컬럼이라 받은 시각으로 채운다.
        assertThat(questions.get(0).getCreatedAt()).isNotNull();
    }

    /**
     * 질문 종류는 채팅 서버가 보내지 않는다. 원질문 번호는 분석 결과의 지역 번호(1..N)라 양수고,
     * 꼬리질문 번호는 채팅 서버가 음수로 매긴다. 그 부호가 둘을 가르는 유일한 단서다.
     */
    @Test
    void 질문_번호의_부호로_꼬리질문을_가려낸다() {
        service.saveInterview(request());

        assertThat(savedQuestions()).extracting(QuestionEntity::getType)
                .containsExactly(Type.ORIGINAL, Type.FOLLOW, Type.ORIGINAL);
    }

    /**
     * 꼬리질문의 부모도 채팅 서버가 보내지 않는다. 채팅 서버는 꼬리질문을 답한 질문 바로 뒤에
     * 끼워 넣으므로, 목록에서 직전에 나온 원질문이 곧 부모다.
     */
    @Test
    void 꼬리질문을_직전_원질문에_매단다() {
        service.saveInterview(request());

        List<QuestionEntity> questions = savedQuestions();
        // 저장 순서대로 901, 902, 903이 발급된다. 꼬리질문의 부모는 채팅 번호 1이 아니라 901이어야 한다.
        assertThat(questions.get(0).getParentId()).isNull();
        assertThat(questions.get(1).getParentId()).isEqualTo(901L);
        assertThat(questions.get(2).getParentId()).isNull();
    }

    /**
     * 채점은 질문 의도 하나를 기준으로 이뤄진다. 원질문은 면접을 열 때 우리가 넘긴 기대 답변이
     * 그대로 돌아오고, 그것이 없는 꼬리질문은 채팅 서버가 정한 의도가 category로 온다.
     */
    @Test
    void 기대_답변을_질문_의도로_남기고_비면_분류를_쓴다() {
        service.saveInterview(request());

        assertThat(savedQuestions()).extracting(QuestionEntity::getIntention)
                .containsExactly("도입 근거 확인", "대안 검토 확인", "운영 경험 확인");
    }

    @Test
    void 답변을_우리_질문_PK에_매단다() {
        service.saveInterview(request());

        List<AnswerEntity> answers = savedAnswers();
        assertThat(answers).extracting(AnswerEntity::getQuestionId).containsExactly(901L, 902L);
        assertThat(answers).extracting(AnswerEntity::getUserId).containsOnly(7L);
        assertThat(answers.get(0).getContent()).isEqualTo("스레드가 I/O 대기에 묶였습니다.");
        assertThat(answers.get(0).getCreatedAt())
                .isEqualTo(LocalDateTime.parse("2026-08-18T01:01:30"));
    }

    @Test
    void 대응하는_질문이_없는_답변은_건너뛴다() {
        SaveInterviewRequest.QnA orphan = new SaveInterviewRequest.QnA(
                new SaveInterviewRequest.Question(1L, 5L, "tech_choice",
                        "WebFlux 를 도입한 이유가 무엇인가요?", "도입 근거 확인", null),
                new SaveInterviewRequest.Answer(999L, 90, "어느 질문에도 붙지 않는 답변",
                        LocalDateTime.parse("2026-08-18T01:01:30")));

        service.saveInterview(new SaveInterviewRequest("sess-1", 3L, 7L, Status.COMPLETED,
                LocalDateTime.parse("2026-08-18T01:00:00"), List.of(orphan)));

        assertThat(savedAnswers()).isEmpty();
    }

    @Test
    void 다시_받아도_같은_결과가_되도록_기존_기록을_지운다() {
        service.saveInterview(request());

        verify(answerRepository).deleteAllByInterviewId(3L);
        verify(questionRepository).deleteAllByInterviewId(3L);
    }

    @Test
    void 면접_상태와_세션을_갱신한다() {
        service.saveInterview(request());

        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(saved.getValue().getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void 세션이_비어_오면_기존_세션을_유지한다() {
        service.saveInterview(new SaveInterviewRequest(null, 3L, 7L, Status.COMPLETED,
                LocalDateTime.parse("2026-08-18T01:00:00"), List.of()));

        // session_id 는 not null 이다. null 로 덮으면 저장이 통째로 실패하고, 그 500 이
        // 채팅 서버의 완료 처리를 끊어 면접 기록을 되찾을 길이 사라진다.
        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void 상태가_비어_오면_끝난_것으로_본다() {
        service.saveInterview(new SaveInterviewRequest("sess-1", 3L, 7L, null,
                LocalDateTime.parse("2026-08-18T01:00:00"), List.of()));

        // 기록을 넘겼다는 것 자체가 면접이 끝났다는 뜻이다.
        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void 없는_면접이면_저장하지_않고_알린다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveInterview(request()))
                .isInstanceOf(BusinessException.class);

        verify(questionRepository, org.mockito.Mockito.never()).save(any(QuestionEntity.class));
    }
}
