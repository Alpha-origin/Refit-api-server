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
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
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
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사용자가 받은 피드백 전체 조회.
 *
 * <p>면접 단위로 하나씩 부르던 것을 한 번에 돌려주는 길이라, 피드백마다 딸린 면접관 평가와
 * 문항 평가를 제 주인에게 붙여 주는 것과 쿼리를 건수만큼 늘리지 않는 것이 요점이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceGetAllTest {

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

    }

    @Test
    void 사용자의_피드백을_최근순_그대로_돌려준다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(feedback(10L, 100L), feedback(11L, 101L)));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses).extracting(FeedbackResponse::getFeedbackId).containsExactly(10L, 11L);
        assertThat(responses).extracting(FeedbackResponse::getInterviewId).containsExactly(100L, 101L);
    }

    @Test
    void 면접관_평가와_문항_평가를_각자의_피드백에_붙인다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(feedback(10L, 100L), feedback(11L, 101L)));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of(persona(10L, "백엔드 리드"), persona(11L, "인사 담당")));
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of(item(10L, "q-1"), item(10L, "q-2"), item(11L, "q-3")));

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses.get(0).getPersonas()).extracting("personaRole").containsExactly("백엔드 리드");
        assertThat(responses.get(0).getFeedbacks()).extracting("questionId").containsExactly("q-1", "q-2");
        assertThat(responses.get(1).getPersonas()).extracting("personaRole").containsExactly("인사 담당");
        assertThat(responses.get(1).getFeedbacks()).extracting("questionId").containsExactly("q-3");
    }

    @Test
    void 같은_면접을_다시_채점했으면_마지막_채점만_보여준다() {
        FeedbackEntity latest = feedback(12L, 100L);
        FeedbackEntity older = feedback(10L, 100L);
        // 리포지토리가 최근순으로 주므로 같은 면접이면 앞에 오는 것이 마지막 채점이다.
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(latest, feedback(11L, 101L), older));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(12L, 11L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(12L, 11L)))
                .thenReturn(List.of());

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses).extracting(FeedbackResponse::getFeedbackId).containsExactly(12L, 11L);
        assertThat(responses).extracting(FeedbackResponse::getInterviewId).containsExactly(100L, 101L);
    }

    @Test
    void 면접_방식을_피드백마다_붙여_준다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(feedback(10L, 100L), feedback(11L, 101L)));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());
        // 면접 수만큼 조회가 늘지 않도록 한 번에 읽어온다.
        when(interviewRepository.findAllById(List.of(100L, 101L)))
                .thenReturn(List.of(interview(100L, InterviewMode.MULTI), interview(101L, InterviewMode.SOLO)));

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses).extracting(FeedbackResponse::getMode)
                .containsExactly(InterviewMode.MULTI, InterviewMode.SOLO);
        verify(interviewRepository, never()).findById(any());
    }

    @Test
    void 면접이_남아_있지_않으면_방식은_비워_둔다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(feedback(10L, 100L)));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L)))
                .thenReturn(List.of());
        when(interviewRepository.findAllById(List.of(100L))).thenReturn(List.of());

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses.get(0).getMode()).isNull();
        assertThat(responses.get(0).getFeedbackId()).isEqualTo(10L);
    }

    @Test
    void 피드백이_없으면_더_조회하지_않고_빈_목록을_준다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

        assertThat(service.getAllFeedbacks(USER_ID)).isEmpty();

        verify(feedbackPersonaRepository, never()).findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(any());
        verify(feedbackItemRepository, never()).findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(any());
    }

    @Test
    void 콜백이_제때_오지_않은_피드백은_목록에서도_실패로_보인다() {
        FeedbackEntity stale = feedback(10L, 100L);
        stale.setStatus(FeedbackStatus.PENDING);
        stale.setCreatedAt(LocalDateTime.now().minusHours(1));

        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(stale));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L)))
                .thenReturn(List.of());

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses.get(0).getStatus()).isEqualTo(FeedbackStatus.FAILED);
        verify(feedbackRepository).save(stale);
    }

    @Test
    void 면접관의_성향과_난이도도_한_번에_읽어_피드백마다_붙인다() {
        when(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(feedback(10L, 100L), feedback(11L, 101L)));
        when(feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(List.of(10L, 11L)))
                .thenReturn(List.of());
        // 1:1은 면접 행에 면접관이 적혀 있고, N:1은 면접관이 따로 걸려 있다.
        when(interviewRepository.findAllById(List.of(100L, 101L)))
                .thenReturn(List.of(interview(100L, InterviewMode.SOLO, 1L),
                        interview(101L, InterviewMode.MULTI, null)));
        when(interviewPersonaRepository.findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(List.of(101L)))
                .thenReturn(List.of(interviewPersona(101L, 2L, 0), interviewPersona(101L, 3L, 1)));
        when(personaRepository.findAllById(any()))
                .thenReturn(List.of(persona(1L, Type.METICULOUS, Level.HARD),
                        persona(2L, Type.FRIENDLY, Level.EASY)));

        List<FeedbackResponse> responses = service.getAllFeedbacks(USER_ID);

        assertThat(responses).extracting(FeedbackResponse::getStyle)
                .containsExactly(Type.METICULOUS, Type.FRIENDLY);
        assertThat(responses).extracting(FeedbackResponse::getLevel)
                .containsExactly(Level.HARD, Level.EASY);
        // 면접 수만큼 조회가 늘지 않도록 면접관도 한 번에 읽는다.
        verify(personaRepository, times(1)).findAllById(any());
        verify(interviewPersonaRepository, times(1))
                .findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(any());
        verify(interviewPersonaRepository, never()).findAllByInterviewIdOrderByPersonaOrderAsc(any());
    }

    private UserResponse user(Long id) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private FeedbackEntity feedback(Long feedbackId, Long interviewId) {
        return FeedbackEntity.builder()
                .feedbackId(feedbackId)
                .interviewId(interviewId)
                .userId(7L)
                .sessionId("sess-" + interviewId)
                .status(FeedbackStatus.SUCCEEDED)
                .totalScore(80)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private InterviewEntity interview(Long interviewId, InterviewMode mode) {
        return interview(interviewId, mode, null);
    }

    private InterviewEntity interview(Long interviewId, InterviewMode mode, Long personaId) {
        return InterviewEntity.builder()
                .interviewId(interviewId)
                .userId(7L)
                .mode(mode)
                .personaId(personaId)
                .build();
    }

    private InterviewPersonaEntity interviewPersona(Long interviewId, Long personaId, int personaOrder) {
        return InterviewPersonaEntity.builder()
                .interviewId(interviewId)
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

    private FeedbackPersonaEntity persona(Long feedbackId, String role) {
        return FeedbackPersonaEntity.builder()
                .feedbackId(feedbackId)
                .personaId(1L)
                .personaRole(role)
                .sortOrder(0)
                .build();
    }

    private FeedbackItemEntity item(Long feedbackId, String questionId) {
        return FeedbackItemEntity.builder()
                .feedbackId(feedbackId)
                .questionId(questionId)
                .sortOrder(0)
                .build();
    }
}
