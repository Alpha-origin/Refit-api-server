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
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** N:1 면접은 기술 면접관 한 명에 다른 직책이 한 명 이상 붙고, 진행 순서는 기술이 먼저다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceMultiCreateTest {

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;

    @Captor
    private ArgumentCaptor<List<InterviewPersonaEntity>> savedMembers;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                authServerClient, answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);

        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(authServerClient.getUser("Bearer t")).thenReturn(user);

        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setInterviewId(3L);
            return interview;
        });
    }

    private PersonaEntity persona(long id, Role role) {
        return PersonaEntity.builder()
                .personaId(id)
                .personaName("면접관 " + id)
                .role(role)
                .major(role == Role.TECH ? Major.BACKEND : null)
                .type(Type.REALISTIC)
                .tone(InterviewTone.DIRECT)
                .career(8)
                .gender(Gender.FEMALE)
                .build();
    }

    @Test
    void 기술_면접관이_먼저고_나머지는_요청_순서를_따른다() {
        // 요청은 CEO -> 기술 -> 인사 순서로 왔다.
        when(personaRepository.findAllById(List.of(13L, 11L, 12L))).thenReturn(List.of(
                persona(13L, Role.CEO), persona(11L, Role.TECH), persona(12L, Role.HR)));

        InterviewResponse response = service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(13L, 11L, 12L)));

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
        // 면접관이 여럿이라 단일 personaId는 비워 둔다.
        assertThat(response.getPersonaId()).isNull();
        // 기술 면접관이 원질문을 맡아 맨 앞이고, 나머지는 사용자가 고른 순서 그대로다.
        assertThat(response.getPersonaIds()).containsExactly(11L, 13L, 12L);

        verify(interviewPersonaRepository).saveAll(savedMembers.capture());
        assertThat(savedMembers.getValue()).extracting(InterviewPersonaEntity::getPersonaId)
                .containsExactly(11L, 13L, 12L);
        assertThat(savedMembers.getValue()).extracting(InterviewPersonaEntity::getPersonaOrder)
                .containsExactly(0, 1, 2);
        assertThat(savedMembers.getValue().getFirst().getInterviewId()).isEqualTo(3L);
    }

    /** 기술 외 한 명이면 면접관이 한 번은 교대한다. 그것이 N:1의 최소 구성이다. */
    @Test
    void 기술_외_면접관이_한_명이면_두_명짜리_N대1로_열린다() {
        when(personaRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR)));

        InterviewResponse response = service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 12L)));

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
        assertThat(response.getPersonaIds()).containsExactly(11L, 12L);
    }

    /** 분석 서버가 otherPersonas를 네 명까지 받는다. 그 상한까지는 그대로 열려야 한다. */
    @Test
    void 기술_외_면접관이_넷이면_다섯_명짜리_N대1로_열린다() {
        when(personaRepository.findAllById(List.of(12L, 13L, 15L, 16L, 11L))).thenReturn(List.of(
                persona(12L, Role.HR), persona(13L, Role.CEO), persona(15L, Role.PM),
                persona(16L, Role.DESIGN), persona(11L, Role.TECH)));

        InterviewResponse response = service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(12L, 13L, 15L, 16L, 11L)));

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
        // 기술 면접관만 맨 앞으로 올라오고 나머지는 고른 순서 그대로다.
        assertThat(response.getPersonaIds()).containsExactly(11L, 12L, 13L, 15L, 16L);

        verify(interviewPersonaRepository).saveAll(savedMembers.capture());
        assertThat(savedMembers.getValue()).extracting(InterviewPersonaEntity::getPersonaOrder)
                .containsExactly(0, 1, 2, 3, 4);
    }

    /**
     * 분석 서버는 otherPersonas가 넷을 넘으면 요청을 422로 거부한다. 그 실패는 면접 시작을 누른
     * 뒤에야 드러나므로, 면접을 만들 때 막는다.
     *
     * <p>지금은 기술 외 직책이 네 가지뿐이라 다섯 명이면 직책도 반드시 겹친다. 그래도 걸러야
     * 하는 것은 인원이므로, 인원 사유로 막혔는지를 메시지로 확인한다 — 직책이 늘어나 겹치지
     * 않게 되어도 이 상한은 그대로 남아야 한다.
     */
    @Test
    void 기술_외_면접관이_다섯이면_422다() {
        when(personaRepository.findAllById(List.of(11L, 12L, 13L, 15L, 16L, 17L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR), persona(13L, Role.CEO),
                persona(15L, Role.PM), persona(16L, Role.DESIGN), persona(17L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 12L, 13L, 15L, 16L, 17L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1명 이상 4명 이하")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(interviewRepository, never()).save(any());
    }

    @Test
    void 기술_면접관이_없으면_422다() {
        // 원질문을 다시 쓸 자리가 기술 면접관뿐이라, 없으면 질문을 구성할 수 없다.
        when(personaRepository.findAllById(List.of(12L, 13L))).thenReturn(List.of(
                persona(12L, Role.HR), persona(13L, Role.CEO)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(12L, 13L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(interviewRepository, never()).save(any());
    }

    @Test
    void 기술_면접관만_있으면_422다() {
        when(personaRepository.findAllById(List.of(11L))).thenReturn(List.of(persona(11L, Role.TECH)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 같은_직책이_둘이면_422다() {
        when(personaRepository.findAllById(List.of(11L, 14L, 12L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(14L, Role.TECH), persona(12L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 14L, 12L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 같은_면접관을_두_번_지정하면_422다() {
        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 11L, 12L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(personaRepository, never()).findAllById(anyIterable());
    }

    @Test
    void 없는_면접관을_지정하면_404다() {
        when(personaRepository.findAllById(List.of(11L, 12L, 99L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 12L, 99L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
