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
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 면접이 고르는 것은 페르소나 하나다. id 우선, 이름 폴백이 지켜지는지 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceCreateTest {

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

    /** 인증을 마친 요청의 주인. 확인은 시큐리티 필터가 끝냈고, 서비스는 id만 받는다. */
    private static final Long USER_ID = 7L;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);


        when(personaRepository.findById(1L)).thenReturn(Optional.of(persona()));
        when(personaRepository.findByPersonaName("압박 면접관")).thenReturn(Optional.of(persona()));
        when(interviewRepository.save(any(InterviewEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PersonaEntity persona() {
        return PersonaEntity.builder()
                .personaId(1L)
                .personaName("압박 면접관")
                .major(Major.BACKEND)
                .type(Type.METICULOUS)
                .tone(InterviewTone.PRESSURING)
                .career(10)
                .gender(Gender.MALE)
                .build();
    }

    @Test
    void personaId가_있으면_이름은_보지_않는다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(1L, "무시되는 이름", null));

        assertThat(response.getPersonaId()).isEqualTo(1L);
        assertThat(response.getUserId()).isEqualTo(7L);
        verify(personaRepository, never()).findByPersonaName(any());
    }

    @Test
    void personaId가_없으면_이름으로_찾는다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "압박 면접관", null));

        assertThat(response.getPersonaId()).isEqualTo(1L);
        verify(personaRepository).findByPersonaName("압박 면접관");
    }

    @Test
    void 페르소나를_아예_지정하지_않으면_422다() {
        assertThatThrownBy(() -> service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "  ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 없는_페르소나를_지정하면_404다() {
        when(personaRepository.findByPersonaName("없는 면접관")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "없는 면접관", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
