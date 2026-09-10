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
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
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
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 시작 시 분석 서버로 나가는 요청.
 *
 * <p>1:1과 N:1이 갈리기 전, 원질문을 읽는 구간은 두 경로가 함께 지난다. 그 구간에서 N:1에만
 * 필요한 값까지 해석하면 1:1 면접 시작이 그 해석에 발이 묶인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServiceRequestTest {

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

        user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(user.getMajor()).thenReturn("BACKEND");

        when(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.empty());
        when(questionTailorRepository.save(any(QuestionTailorEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona(11L, Role.TECH)));
        givenAnalysisResult(projectSummary());
    }

    private void givenAnalysisResult(Object projectSummary) {
        givenAnalysisResult(projectSummary, originalQuestions());
    }

    private void givenAnalysisResult(Object projectSummary, List<Map<String, Object>> interview) {
        when(analysisDataRepository.findLatestCompleted(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-1")
                        .userId(7L)
                        .result(Map.of("project_summary", projectSummary, "interview", interview))
                        .build()));
    }

    /** 분석 서버가 원질문을 넉넉히 만들어 보낸 경우. id는 1부터 이어진다. */
    private List<Map<String, Object>> numberedQuestions(int count) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            questions.add(Map.of("id", id, "category", "tech_choice", "question", id + "번 질문",
                    "expected_answer", id + "번 기대 답변", "based_on", List.of()));
        }
        return questions;
    }

    /** /generate 산출물. 와이어 포맷이 snake_case다. */
    private List<Map<String, Object>> originalQuestions() {
        return List.of(
                Map.of("id", 1, "category", "tech_choice", "question", "왜 Redis 를 썼나요?",
                        "expected_answer", "캐시 선택 근거", "based_on", List.of("order-api/CacheConfig.java")),
                Map.of("id", 2, "category", "structure", "question", "모듈을 왜 나눴나요?",
                        "expected_answer", "경계 설정 기준", "based_on", List.of()),
                Map.of("id", 3, "category", "troubleshooting", "question", "장애는 어떻게 잡았나요?",
                        "expected_answer", "원인 추적 과정", "based_on", List.of()));
    }

    /** 저장된 실제 모양 그대로다 — 키는 전부 snake_case, core_features 원소에 based_on 이 붙는다. */
    private Map<String, Object> projectSummary() {
        return Map.of(
                "overview", "주문 처리를 맡는 백엔드",
                "repositories", List.of(Map.of("repo", "order-api", "role", "api_server", "description", "주문 API")),
                "core_features", List.of(Map.of("name", "주문 생성", "description", "결제 승인 후 주문을 만든다",
                        "based_on", List.of("order-api/OrderService.java"))),
                "tech_stack", List.of("Spring", "Redis"));
    }

    private PersonaEntity persona(long id, Role role) {
        return PersonaEntity.builder()
                .personaId(id)
                .personaName("면접관 " + id)
                .role(role)
                .major(role == Role.TECH ? Major.BACKEND : null)
                .type(Type.REALISTIC)
                .tone(InterviewTone.PRESSURING)
                .level(Level.NORMAL)
                .career(8)
                .gender(Gender.MALE)
                .build();
    }

    private InterviewEntity interview(InterviewMode mode) {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(mode == InterviewMode.SOLO ? 11L : null)
                .mode(mode)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build();
    }

    private void givenMultiPersonas() {
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(11L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(12L).personaOrder(1).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(13L).personaOrder(2).build()));
        when(personaRepository.findAllById(List.of(11L, 12L, 13L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR), persona(13L, Role.CEO)));
    }

    /**
     * 프로젝트 요약은 분석 결과에 통째로 저장해둔 값이라 형태를 우리가 못 박아둘 수 없다.
     * 1:1은 그 값을 쓰지 않으므로, 어떤 모양이 들어 있든 면접 시작이 걸리면 안 된다.
     */
    @Test
    void 일대일은_프로젝트_요약_형태에_걸리지_않는다() {
        givenAnalysisResult("요약이 문자열로 들어 있다");

        service.requestTailor(interview(InterviewMode.SOLO), user);

        verify(aiServerClient).tailorQuestions(any(QuestionTailorRequest.class));
    }

    @Test
    void 일대일도_면접관_성향과_어조를_함께_보낸다() {
        givenAnalysisResult("요약이 문자열로 들어 있다");
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona(11L, Role.TECH)));

        service.requestTailor(interview(InterviewMode.SOLO), user);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        // 성향만 보내면 분석 서버가 질문의 세기를 성향에서 유추하게 된다. 두 축은 독립이다.
        assertThat(sent.getValue().getProfile().getPersonaType()).isEqualTo("REALISTIC");
        assertThat(sent.getValue().getProfile().getPersonaTone()).isEqualTo("PRESSURING");
    }

    /**
     * 1:1 면접은 다섯 문항이다.
     *
     * <p>분석 서버가 원질문을 몇 개 만들지는 우리가 정하지 않는다. 그대로 흘려보내면 같은 1:1
     * 면접인데 분석 결과에 따라 길이가 달라진다.
     */
    @Test
    void 일대일은_원질문이_많아도_다섯_문항만_보낸다() {
        givenAnalysisResult(projectSummary(), numberedQuestions(8));

        service.requestTailor(interview(InterviewMode.SOLO), user);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        assertThat(sent.getValue().getQuestions()).extracting(QuestionTailorRequest.Question::getId)
                .containsExactly(1, 2, 3, 4, 5);
    }

    /**
     * 고른 다섯 개가 그대로 sourceQuestions로 남아야 한다.
     * 재작성이 실패하면 이 값이 그대로 면접에 쓰이므로, 여기에 여덟 개가 남으면 폴백된 면접만 길어진다.
     */
    @Test
    void 일대일은_고른_다섯_문항만_원질문으로_남긴다() {
        givenAnalysisResult(projectSummary(), numberedQuestions(8));

        QuestionTailorEntity saved = service.requestTailor(interview(InterviewMode.SOLO), user);

        assertThat(saved.getSourceQuestions()).hasSize(5);
    }

    /** 분석 결과가 다섯 개에 못 미치면 있는 만큼 간다. 없는 질문을 만들어낼 수는 없다. */
    @Test
    void 일대일은_원질문이_모자라면_있는_만큼_보낸다() {
        service.requestTailor(interview(InterviewMode.SOLO), user);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        assertThat(sent.getValue().getQuestions()).hasSize(3);
    }

    /**
     * 원질문이 많다고 면접을 막지 않는다.
     *
     * <p>분석 서버가 몇 개를 만들지는 우리가 정하지 않는데, 예전에는 열 개를 넘으면 422로 돌려보냈다.
     * 어차피 앞에서 다섯 개만 쓰므로 막을 이유가 없다.
     */
    @Test
    void 일대일은_원질문이_열_개를_넘어도_면접을_연다() {
        givenAnalysisResult(projectSummary(), numberedQuestions(12));

        service.requestTailor(interview(InterviewMode.SOLO), user);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        assertThat(sent.getValue().getQuestions()).extracting(QuestionTailorRequest.Question::getId)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void N대1도_원질문이_열_개를_넘어도_면접을_연다() {
        givenMultiPersonas();
        givenAnalysisResult(projectSummary(), numberedQuestions(12));

        service.requestTailor(interview(InterviewMode.MULTI), user);

        ArgumentCaptor<QuestionTailorMultiRequest> sent =
                ArgumentCaptor.forClass(QuestionTailorMultiRequest.class);
        verify(aiServerClient).tailorQuestionsMulti(sent.capture());
        assertThat(sent.getValue().getQuestions()).extracting(QuestionTailorMultiRequest.Question::getId)
                .containsExactly(1, 2);
    }

    /**
     * 쓰지도 않을 뒤쪽 질문 때문에 면접이 막히면 안 된다.
     *
     * <p>id 중복은 분석 서버가 요청을 거부하는 사유라 막아야 하지만, 그것은 실제로 넘기는 질문에
     * 겹침이 있을 때의 이야기다. 버릴 질문까지 훑으면 멀쩡한 면접이 막힌다.
     */
    @Test
    void 버릴_질문의_id가_겹치는_것은_면접을_막지_않는다() {
        List<Map<String, Object>> questions = new ArrayList<>(numberedQuestions(5));
        // 여섯 번째부터 id가 겹친다. 다섯 개만 쓰므로 넘어가는 질문에는 겹침이 없다.
        questions.add(Map.of("id", 1, "category", "tech_choice", "question", "겹치는 질문",
                "expected_answer", "겹치는 기대 답변", "based_on", List.of()));
        givenAnalysisResult(projectSummary(), questions);

        service.requestTailor(interview(InterviewMode.SOLO), user);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        assertThat(sent.getValue().getQuestions()).extracting(QuestionTailorRequest.Question::getId)
                .containsExactly(1, 2, 3, 4, 5);
    }

    /** 넘길 질문 안에 겹침이 있으면 분석 서버가 요청을 거부한다. 그건 그대로 막는다. */
    @Test
    void 넘길_질문의_id가_겹치면_422다() {
        List<Map<String, Object>> questions = new ArrayList<>(numberedQuestions(3));
        questions.add(Map.of("id", 1, "category", "tech_choice", "question", "겹치는 질문",
                "expected_answer", "겹치는 기대 답변", "based_on", List.of()));
        givenAnalysisResult(projectSummary(), questions);

        assertThatThrownBy(() -> service.requestTailor(interview(InterviewMode.SOLO), user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(aiServerClient, never()).tailorQuestions(any());
    }

    @Test
    void N대1은_요약을_읽지_못하면_422로_알린다() {
        givenMultiPersonas();
        givenAnalysisResult("요약이 문자열로 들어 있다");

        assertThatThrownBy(() -> service.requestTailor(interview(InterviewMode.MULTI), user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // 500으로 나가면 사용자는 서버가 고장난 것인지 분석을 다시 해야 하는 것인지 알 수 없다.
        verify(aiServerClient, never()).tailorQuestionsMulti(any());
    }

    /**
     * 저장된 result의 최상위 키가 camelCase로 와도 요약은 살아 있어야 한다.
     *
     * <p>이름이 어긋나면 값이 조용히 빈다. 그러면 분석 결과에는 요약이 멀쩡히 들어 있는데도
     * N:1만 "요약이 없다"며 열리지 않고, 사용자는 분석을 다시 돌려도 같은 자리에서 막힌다.
     */
    @Test
    void N대1은_요약이_camelCase_키로_저장돼_있어도_읽는다() {
        givenMultiPersonas();
        when(analysisDataRepository.findLatestCompleted(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-1")
                        .userId(7L)
                        .result(Map.of("projectSummary", projectSummary(), "interview", originalQuestions()))
                        .build()));

        service.requestTailor(interview(InterviewMode.MULTI), user);

        ArgumentCaptor<QuestionTailorMultiRequest> sent =
                ArgumentCaptor.forClass(QuestionTailorMultiRequest.class);
        verify(aiServerClient).tailorQuestionsMulti(sent.capture());
        assertThat(sent.getValue().getProjectSummary().getOverview()).isEqualTo("주문 처리를 맡는 백엔드");
    }

    /**
     * 요약이 비어 막힐 때, 안내는 이미 끝나 있는 분석을 가리켜야 한다.
     *
     * <p>같은 분석 결과에서 원질문은 이미 읽힌 뒤라 "분석을 먼저 진행하라"는 안내는 사실과 다르다.
     * 그 말을 들은 사용자는 이미 해둔 일을 다시 하고 같은 자리에서 다시 막힌다.
     */
    @Test
    void N대1은_요약이_없으면_분석이_끝나있음을_가리켜_알린다() {
        givenMultiPersonas();
        when(analysisDataRepository.findLatestCompleted(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-1")
                        .userId(7L)
                        .result(Map.of("interview", originalQuestions()))
                        .build()));

        assertThatThrownBy(() -> service.requestTailor(interview(InterviewMode.MULTI), user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("분석 결과에 프로젝트 요약이 없어")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(aiServerClient, never()).tailorQuestionsMulti(any());
    }

    @Test
    void N대1은_원질문_두_개와_면접관_구성을_실어_보낸다() {
        givenMultiPersonas();

        service.requestTailor(interview(InterviewMode.MULTI), user);

        ArgumentCaptor<QuestionTailorMultiRequest> sent =
                ArgumentCaptor.forClass(QuestionTailorMultiRequest.class);
        verify(aiServerClient).tailorQuestionsMulti(sent.capture());
        QuestionTailorMultiRequest request = sent.getValue();

        // 기술 면접관 몫은 원질문 앞에서 두 개. questionCount는 이 개수와 같아야 한다.
        assertThat(request.getQuestions()).extracting(QuestionTailorMultiRequest.Question::getId)
                .containsExactly(1, 2);
        assertThat(request.getTechPersona().getQuestionCount()).isEqualTo(2);
        assertThat(request.getTechPersona().getRole()).isEqualTo("TECH");
        assertThat(request.getOtherPersonas()).extracting(QuestionTailorMultiRequest.Persona::getRole)
                .containsExactly("HR", "CEO");
        // 성향과 어조는 독립된 축이라 둘 다 실려야 면접관마다 무엇을 어떻게 물을지가 정해진다.
        assertThat(request.getTechPersona().getStyle()).isEqualTo("REALISTIC");
        assertThat(request.getTechPersona().getTone()).isEqualTo("PRESSURING");
        // snake_case로 저장된 요약이 camelCase로 옮겨져야 근거가 살아서 넘어간다.
        assertThat(request.getProjectSummary().getOverview()).isEqualTo("주문 처리를 맡는 백엔드");
        assertThat(request.getProjectSummary().getTechStack()).containsExactly("Spring", "Redis");
        assertThat(request.getProjectSummary().getCoreFeatures()).hasSize(1);
        // based_on 까지 옮겨져야 신규 질문이 근거 없는 추측 질문이 되지 않는다.
        assertThat(request.getProjectSummary().getCoreFeatures().getFirst().getBasedOn())
                .containsExactly("order-api/OrderService.java");
    }

    /**
     * 면접관 수는 사용자가 정한다. 기술 면접관 몫은 그대로고, 늘어난 인원만큼 신규 질문 몫이 붙는다.
     * 여기서 인원을 흘리면 그만큼의 면접관이 질문 없이 앉아 있게 된다.
     */
    @Test
    void N대1은_면접관이_넷_더_붙어도_그대로_실어_보낸다() {
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(11L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(12L).personaOrder(1).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(13L).personaOrder(2).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(15L).personaOrder(3).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(16L).personaOrder(4).build()));
        when(personaRepository.findAllById(List.of(11L, 12L, 13L, 15L, 16L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR), persona(13L, Role.CEO),
                persona(15L, Role.PM), persona(16L, Role.DESIGN)));

        service.requestTailor(interview(InterviewMode.MULTI), user);

        ArgumentCaptor<QuestionTailorMultiRequest> sent =
                ArgumentCaptor.forClass(QuestionTailorMultiRequest.class);
        verify(aiServerClient).tailorQuestionsMulti(sent.capture());
        QuestionTailorMultiRequest request = sent.getValue();

        // 저장된 진행 순서 그대로 나가야 질문 배열도 그 순서로 돌아온다.
        assertThat(request.getOtherPersonas()).extracting(QuestionTailorMultiRequest.Persona::getRole)
                .containsExactly("HR", "CEO", "PM", "DESIGN");
        // 기술 면접관 몫은 인원이 늘어도 그대로다. 늘어나는 것은 신규 질문 쪽뿐이다.
        assertThat(request.getTechPersona().getQuestionCount()).isEqualTo(2);
        assertThat(request.getOtherPersonas())
                .allSatisfy(persona -> assertThat(persona.getQuestionCount()).isEqualTo(2));
    }

    /** 기대 답변이 비면 분석 서버가 요청 전체를 422로 거부한다. 콜백까지 갔다 오기 전에 막는다. */
    @Test
    void 기대_답변이_빈_원질문이면_보내지_않는다() {
        givenMultiPersonas();
        when(analysisDataRepository.findLatestCompleted(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-1")
                        .userId(7L)
                        .result(Map.of("project_summary", projectSummary(), "interview", List.of(
                                Map.of("id", 1, "category", "tech_choice", "question", "왜 Redis 를 썼나요?",
                                        "expected_answer", "  ", "based_on", List.of()))))
                        .build()));

        assertThatThrownBy(() -> service.requestTailor(interview(InterviewMode.MULTI), user))
                .isInstanceOf(BusinessException.class);

        verify(aiServerClient, never()).tailorQuestionsMulti(any());
    }
}
