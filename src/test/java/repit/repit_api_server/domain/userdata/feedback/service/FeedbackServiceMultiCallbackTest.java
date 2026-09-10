package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N:1 피드백 콜백 저장.
 *
 * <p>채점을 요청하는 쪽은 언제나 이 서버다. 그래도 접수 기록이 없는 콜백이 올 수 있다 —
 * 접수는 됐는데 202 응답을 우리가 못 받으면 행이 남지 않은 채로 채점만 돌기 때문이다.
 * 그때도 결과를 받아내야 한다. 여기서 흘리면 분석 서버가 두 번 시도한 뒤 영구 폐기한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceMultiCallbackTest {

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

    @Captor
    private ArgumentCaptor<List<FeedbackPersonaEntity>> savedPersonas;
    @Captor
    private ArgumentCaptor<List<FeedbackItemEntity>> savedItems;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, feedbackItemRepository, feedbackPersonaRepository,
                interviewRepository, interviewPersonaRepository, personaRepository, questionRepository,
                answerRepository, aiServerClient);

        when(feedbackRepository.save(any(FeedbackEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private FeedbackCallbackRequest callback() {
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                72, 80, 61, "면접관이 바뀐 뒤 설명이 달라졌습니다.",
                List.of("캐시 도입 배경을 수치와 함께 설명함"),
                List.of("설명이 엇갈림"), List.of(), 6, 7);

        FeedbackCallbackRequest.Persona tech = new FeedbackCallbackRequest.Persona(
                11L, "TECH", 78, "대안 검토가 얕습니다.",
                List.of("측정값을 근거로 제시함"), List.of("탈락 이유가 없음"), 3, 3);
        FeedbackCallbackRequest.Persona hr = new FeedbackCallbackRequest.Persona(
                12L, "HR", 70, "동기가 추상적입니다.", List.of(), List.of(), 2, 2);
        FeedbackCallbackRequest.Persona ceo = new FeedbackCallbackRequest.Persona(
                13L, "CEO", 64, "우선순위 근거가 약합니다.", List.of(), List.of(), 2, 2);

        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "2", 11L, "Redis를 캐시로 두신 이유는?", "기술 선택의 근거",
                "조회가 쓰기보다 많아서요.", "지연을 수치로 제시한다.",
                List.of("p99 지연을 근거로 든 점"), List.of("무효화 전략 미언급"), "부작용까지는 못 짚었습니다.");

        return new FeedbackCallbackRequest("job-1", "sess-1", "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr, ceo), List.of(item)), null);
    }

    @Test
    void 접수_기록이_없어도_세션으로_면접을_찾아_결과를_받아낸다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.empty());
        when(feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc("sess-1")).thenReturn(Optional.empty());
        when(interviewRepository.findBySessionId("sess-1")).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.MULTI)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        service.handleCallback(callback());

        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        FeedbackEntity feedback = saved.getValue();
        assertThat(feedback.getInterviewId()).isEqualTo(3L);
        assertThat(feedback.getUserId()).isEqualTo(7L);
        assertThat(feedback.getJobId()).isEqualTo("job-1");
        assertThat(feedback.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(feedback.getReliabilityScore()).isEqualTo(61);
    }

    @Test
    void 면접관별_종합과_문항의_면접관을_저장한다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(FeedbackEntity.builder()
                .feedbackId(5L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .jobId("job-1")
                .status(FeedbackStatus.PENDING)
                .build()));

        service.handleCallback(callback());

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        List<FeedbackPersonaEntity> personas = savedPersonas.getValue();
        assertThat(personas).hasSize(3);
        assertThat(personas).extracting(FeedbackPersonaEntity::getPersonaRole)
                .containsExactly("TECH", "HR", "CEO");
        // 분석 서버가 보낸 순서가 곧 면접 진행 순서다.
        assertThat(personas).extracting(FeedbackPersonaEntity::getSortOrder).containsExactly(0, 1, 2);
        assertThat(personas.getFirst().getFeedbackId()).isEqualTo(5L);
        assertThat(personas.getFirst().getScore()).isEqualTo(78);
        assertThat(personas.getFirst().getStrengths()).containsExactly("측정값을 근거로 제시함");

        verify(feedbackItemRepository).saveAll(savedItems.capture());
        assertThat(savedItems.getValue().getFirst().getPersonaId()).isEqualTo(11L);
    }

    @Test
    void 콜백이_다시_와도_면접관_종합이_중복되지_않는다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(FeedbackEntity.builder()
                .feedbackId(5L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .jobId("job-1")
                .status(FeedbackStatus.SUCCEEDED)
                .build()));

        service.handleCallback(callback());

        verify(feedbackPersonaRepository).deleteAllByFeedbackId(5L);
    }

    /** 901은 기술 면접관, 902는 거기 달린 꼬리질문, 903은 인사 면접관 몫이고 답하지 않았다. */
    private void givenRecordedTranscript() {
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(List.of(
                QuestionEntity.builder().questionId(901L).interviewId(3L).personaId(11L)
                        .type(Type.ORIGINAL).content("Redis를 캐시로 두신 이유는?").build(),
                // 채팅 서버가 면접 중에 만든 꼬리질문. 면접관은 부모에게서 물려받는다.
                QuestionEntity.builder().questionId(902L).interviewId(3L).parentId(901L)
                        .type(Type.FOLLOW).content("무효화는 어떻게 하셨나요?").build(),
                QuestionEntity.builder().questionId(903L).interviewId(3L).personaId(12L)
                        .type(Type.ORIGINAL).content("팀에서 갈등이 있었다면?").build()));
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(List.of(
                AnswerEntity.builder().answerId(501L).interviewId(3L).questionId(901L).userId(7L)
                        .content("조회가 쓰기보다 많아서요.").build(),
                AnswerEntity.builder().answerId(502L).interviewId(3L).questionId(902L).userId(7L)
                        .content("TTL을 짧게 뒀습니다.").build()));
    }

    private FeedbackEntity acceptedFeedback() {
        return FeedbackEntity.builder()
                .feedbackId(5L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .jobId("job-1")
                .status(FeedbackStatus.PENDING)
                .build();
    }

    /**
     * 문항 수는 면접 중에 생긴 꼬리질문까지 포함한 최종 기록 기준이어야 한다.
     * 분석 서버가 센 수와 어긋나면 화면에 기록에 없는 수가 걸려 사용자가 어느 쪽이 맞는지 알 수 없다.
     */
    @Test
    void 문항_수와_답변_수는_기록_기준으로_저장한다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenRecordedTranscript();

        service.handleCallback(callback());

        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        // 콜백은 6/7이라고 했지만 기록에는 문항 3개에 답변 2개가 남아 있다.
        assertThat(saved.getValue().getQuestionCount()).isEqualTo(3);
        assertThat(saved.getValue().getAnsweredCount()).isEqualTo(2);

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        List<FeedbackPersonaEntity> personas = savedPersonas.getValue();
        // 기술 면접관 몫은 원질문 하나와 거기 달린 꼬리질문 하나다.
        assertThat(personas.getFirst().getQuestionCount()).isEqualTo(2);
        assertThat(personas.getFirst().getAnsweredCount()).isEqualTo(2);
        // 인사 면접관 몫은 한 문항이고 답하지 않았다.
        assertThat(personas.get(1).getQuestionCount()).isEqualTo(1);
        assertThat(personas.get(1).getAnsweredCount()).isZero();
    }

    /**
     * 문항이 명단에 없는 면접관을 가리키면 웹의 어느 묶음에도 들어가지 못해 화면에서 사라진다.
     * 기록해둔 담당 면접관으로 맞춰 되살린다.
     */
    @Test
    void 명단_밖_면접관을_가리키는_문항은_기록된_담당으로_맞춘다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenRecordedTranscript();

        FeedbackCallbackRequest request = callback();
        FeedbackCallbackRequest.Item stray = new FeedbackCallbackRequest.Item(
                "903", 99L, "팀에서 갈등이 있었다면?", "협업 태도",
                null, "사실과 대응을 나눠 말한다.", List.of(), List.of(), "답하지 않았습니다.");
        FeedbackCallbackRequest withStray = new FeedbackCallbackRequest("job-1", "sess-1", "succeeded",
                new FeedbackCallbackRequest.Result(request.getResult().getOverall(),
                        request.getResult().getPersonas(), List.of(stray)),
                null);

        service.handleCallback(withStray);

        verify(feedbackItemRepository).saveAll(savedItems.capture());
        assertThat(savedItems.getValue().getFirst().getPersonaId()).isEqualTo(12L);
    }

    @Test
    void 세션도_면접도_모르면_아무것도_저장하지_않는다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.empty());
        when(feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc("sess-1")).thenReturn(Optional.empty());
        when(interviewRepository.findBySessionId("sess-1")).thenReturn(Optional.empty());

        service.handleCallback(callback());

        verify(feedbackRepository, never()).save(any());
        verify(feedbackPersonaRepository, never()).saveAll(any());
    }

    /** N:1 면접 명단. 채점 결과의 면접관은 이 안에 있어야 한다. */
    private void givenInterviewMembers() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L).userId(7L).mode(InterviewMode.MULTI)
                .sessionId("sess-1").status(Status.COMPLETED).build()));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(11L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(12L).personaOrder(1).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(13L).personaOrder(2).build()));
    }

    private FeedbackCallbackRequest callbackWith(List<FeedbackCallbackRequest.Persona> personas,
                                                 List<FeedbackCallbackRequest.Item> items) {
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                72, 80, 61, "요약", List.of(), List.of(), List.of(), 1, 1);
        return new FeedbackCallbackRequest("job-1", "sess-1", "succeeded",
                new FeedbackCallbackRequest.Result(overall, personas, items), null);
    }

    private FeedbackCallbackRequest.Item item(String questionId, Long personaId) {
        return new FeedbackCallbackRequest.Item(questionId, personaId, "질문", "의도",
                "답변", "모범답변", List.of(), List.of(), "총평");
    }

    private FeedbackCallbackRequest.Persona persona(Long personaId, Integer score) {
        return new FeedbackCallbackRequest.Persona(personaId, "TECH", score, "총평",
                List.of(), List.of(), 1, 1);
    }

    /**
     * 1:1 면접에 면접관별 종합이 붙으면 화면에 있지도 않은 면접관 카드가 생긴다.
     * 판정은 명단이 비었는지가 아니라 면접 방식으로 한다.
     */
    @Test
    void 일대일_면접에_실려온_면접관_종합은_버린다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L).userId(7L).mode(InterviewMode.SOLO)
                .sessionId("sess-1").status(Status.COMPLETED).build()));

        service.handleCallback(callbackWith(List.of(persona(11L, 78)), List.of(item("2", 11L))));

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        assertThat(savedPersonas.getValue()).isEmpty();
    }

    /** 같은 면접관이 두 번 오면 결과 화면에 같은 카드가 두 개 생긴다. */
    @Test
    void 같은_면접관의_종합이_두_번_오면_하나만_남긴다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenInterviewMembers();

        service.handleCallback(callbackWith(
                List.of(persona(11L, 78), persona(11L, 64)), List.of(item("2", 11L))));

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        assertThat(savedPersonas.getValue()).hasSize(1);
        assertThat(savedPersonas.getValue().getFirst().getScore()).isEqualTo(78);
    }

    /** 같은 문항이 두 번 나오면 결과에 같은 질문이 두 번 걸리고 문항 수도 기록과 어긋난다. */
    @Test
    void 같은_문항이_두_번_오면_하나만_남긴다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenInterviewMembers();

        service.handleCallback(callbackWith(List.of(persona(11L, 78)),
                List.of(item("2", 11L), item("2", 11L), item("3", 12L))));

        verify(feedbackItemRepository).saveAll(savedItems.capture());
        assertThat(savedItems.getValue()).extracting(FeedbackItemEntity::getQuestionId)
                .containsExactly("2", "3");
        // 남은 것들의 순번은 빈자리 없이 이어져야 한다.
        assertThat(savedItems.getValue()).extracting(FeedbackItemEntity::getSortOrder)
                .containsExactly(0, 1);
    }

    /** 눈금을 넘치거나 음수로 그리게 두면 사용자에게는 고장으로 보인다. */
    @Test
    void 범위를_벗어난_점수는_경계값으로_맞춘다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenInterviewMembers();

        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                150, -3, 61, "요약", List.of(), List.of(), List.of(), 1, 1);
        service.handleCallback(new FeedbackCallbackRequest("job-1", "sess-1", "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(persona(11L, 120)),
                        List.of(item("2", 11L))), null));

        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getTotalScore()).isEqualTo(100);
        assertThat(saved.getValue().getIntentAlignmentScore()).isZero();

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        assertThat(savedPersonas.getValue().getFirst().getScore()).isEqualTo(100);
    }

    /**
     * 검증을 지우기 전에 끝내지 않으면, 걸러내다 멈춘 순간 이전 결과도 새 결과도 없는 상태로 남는다.
     */
    @Test
    void 기존_결과는_새_결과를_다_확인한_뒤에_지운다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(acceptedFeedback()));
        givenInterviewMembers();

        service.handleCallback(callbackWith(List.of(persona(11L, 78)), List.of(item("2", 11L))));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                questionRepository, feedbackItemRepository);
        order.verify(questionRepository).findAllByInterviewIdOrderByQuestionIdAsc(3L);
        order.verify(feedbackItemRepository).deleteAllByFeedbackId(5L);
        order.verify(feedbackItemRepository).saveAll(any());
    }
}
