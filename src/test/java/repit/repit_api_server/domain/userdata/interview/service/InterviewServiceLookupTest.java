package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 면접 단건 조회(GET /api/interviews/get).
 *
 * <p>없는 면접을 물었을 때 그렇게 답해야 한다. 조회 실패가 500으로 나가면 클라이언트는
 * 잘못된 id 때문인지 서버가 고장난 것인지 구분할 수 없고, 로그에도 장애로 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceLookupTest {

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

    }

    @Test
    void 없는_면접을_조회하면_404다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInterviewById(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("면접을 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 있는_면접은_그대로_돌려준다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(5L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        assertThat(service.getInterviewById(USER_ID, 3L).getInterviewId()).isEqualTo(3L);
    }

    @Test
    void 남의_면접을_조회하면_403이다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                // 요청한 사용자(7L)와 다른 주인이다.
                .userId(9L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        assertThatThrownBy(() -> service.getInterviewById(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
