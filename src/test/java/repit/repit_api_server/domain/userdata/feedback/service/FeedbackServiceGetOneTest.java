package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 면접 하나에 대한 피드백 조회.
 *
 * <p>웹은 1:1과 N:1 결과를 다르게 그려야 해서, 채점 내용과 함께 그 면접이 어떤 방식이었는지도
 * 받아야 한다. 면접관 평가가 비어 있는 것만으로는 1:1인지 아직 안 온 것인지 가릴 수 없다.
 *
 * <p>면접관의 성향(스타일)과 난이도도 같이 나간다. 채점 결과에는 없는 값이라 면접관 행에서 읽어
 * 붙인다. 압박형 HARD에서 받은 70점과 친화형 EASY에서 받은 70점은 같은 점수가 아니다.
 * N:1도 면접관 셋이 같은 성향·난이도로 묶이므로 면접마다 값 하나로 족하다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceGetOneTest {

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
        ReflectionTestUtils.setField(service, "pendingTimeout", Duration.ofMinutes(5));

        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(feedback()));
        when(feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(10L)).thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(10L)).thenReturn(List.of());
    }

    @Test
    void N대1_면접의_피드백은_MULTI로_나간다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.MULTI)));

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
    }

    @Test
    void 일대일_면접의_피드백은_SOLO로_나간다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.SOLO)));

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getMode()).isEqualTo(InterviewMode.SOLO);
    }

    @Test
    void 면접이_남아_있지_않아도_채점_결과는_그대로_준다() {
        when(interviewRepository.findById(100L)).thenReturn(Optional.empty());

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getMode()).isNull();
        assertThat(response.getTotalScore()).isEqualTo(80);
    }

    @Test
    void 일대일_피드백에는_면접관의_성향과_난이도가_함께_나간다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.SOLO, 5L)));
        when(personaRepository.findAllById(any()))
                .thenReturn(List.of(persona(5L, Type.METICULOUS, Level.HARD)));

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getStyle()).isEqualTo(Type.METICULOUS);
        assertThat(response.getLevel()).isEqualTo(Level.HARD);
    }

    @Test
    void N대1은_면접에_걸린_면접관에서_성향과_난이도를_읽는다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.MULTI, null)));
        // 면접관 셋은 성향·난이도가 같은 값으로 묶여 있어 맨 앞 하나로 대표한다.
        when(interviewPersonaRepository.findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(List.of(100L)))
                .thenReturn(List.of(interviewPersona(5L, 0), interviewPersona(6L, 1)));
        when(personaRepository.findAllById(any()))
                .thenReturn(List.of(persona(5L, Type.METICULOUS, Level.HARD)));

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getStyle()).isEqualTo(Type.METICULOUS);
        assertThat(response.getLevel()).isEqualTo(Level.HARD);
    }

    @Test
    void 아직_채점이_끝나지_않은_N대1도_성향과_난이도는_나간다() {
        FeedbackEntity pending = feedback();
        pending.setStatus(FeedbackStatus.PENDING);
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(pending));
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.MULTI, null)));
        // 채점 결과의 면접관은 콜백이 와야 생긴다. 그쪽을 보면 여기서 값이 비어버린다.
        when(feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(10L)).thenReturn(List.of());
        when(interviewPersonaRepository.findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(List.of(100L)))
                .thenReturn(List.of(interviewPersona(5L, 0)));
        when(personaRepository.findAllById(any()))
                .thenReturn(List.of(persona(5L, Type.FRIENDLY, Level.EASY)));

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getStatus()).isEqualTo(FeedbackStatus.PENDING);
        assertThat(response.getStyle()).isEqualTo(Type.FRIENDLY);
        assertThat(response.getLevel()).isEqualTo(Level.EASY);
    }

    @Test
    void 면접관이_지워졌으면_성향과_난이도만_비우고_채점_결과는_그대로_준다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.SOLO, 5L)));
        when(personaRepository.findAllById(any())).thenReturn(List.of());

        FeedbackResponse response = service.getFeedback(USER_ID, 100L);

        assertThat(response.getStyle()).isNull();
        assertThat(response.getLevel()).isNull();
        assertThat(response.getTotalScore()).isEqualTo(80);
    }

    private UserResponse user(Long id) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private FeedbackEntity feedback() {
        return FeedbackEntity.builder()
                .feedbackId(10L)
                .interviewId(100L)
                .userId(7L)
                .sessionId("sess-100")
                .status(FeedbackStatus.SUCCEEDED)
                .totalScore(80)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private InterviewEntity interview(InterviewMode mode) {
        return interview(mode, mode == InterviewMode.SOLO ? 5L : null);
    }

    private InterviewEntity interview(InterviewMode mode, Long personaId) {
        return InterviewEntity.builder()
                .interviewId(100L)
                .userId(7L)
                .mode(mode)
                .personaId(personaId)
                .build();
    }

    private InterviewPersonaEntity interviewPersona(Long personaId, int personaOrder) {
        return InterviewPersonaEntity.builder()
                .interviewId(100L)
                .personaId(personaId)
                .personaOrder(personaOrder)
                .build();
    }

    private PersonaEntity persona(Long personaId, Type type, Level level) {
        return PersonaEntity.builder()
                .personaId(personaId)
                .personaName("면접관-" + personaId)
                .role(Role.TECH)
                .type(type)
                .level(level)
                .career(5)
                .gender(Gender.MALE)
                .build();
    }
}
