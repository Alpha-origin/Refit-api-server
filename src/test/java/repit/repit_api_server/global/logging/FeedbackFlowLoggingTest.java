package repit.repit_api_server.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repit.repit_api_server.domain.userdata.feedback.controller.FeedbackController;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드백은 요청과 결과가 다른 요청으로 나뉜다.
 * 요청을 접수하는 호출과 뒤늦게 도착하는 분석 서버 콜백이 각각 온전히 로그로 남는지,
 * 로그를 위해 본문을 먼저 읽는 것이 콜백 파싱을 망가뜨리지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackFlowLoggingTest {

    private static final String CALLBACK_BODY = """
            {
              "jobId": "job-8f21",
              "sessionId": "sess-1",
              "status": "succeeded",
              "result": {
                "overall": {
                  "totalScore": 72,
                  "summary": "근거를 들어 답했습니다."
                }
              }
            }
            """;

    @Mock
    private FeedbackService feedbackService;

    private MockMvc mockMvc;
    private Logger filterLogger;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FeedbackController(feedbackService))
                // 컨트롤러가 @AuthenticationPrincipal로 사용자를 받는다. standalone 설정에는
                // 시큐리티가 없어 이 리졸버를 직접 붙이지 않으면 인자가 null로 들어간다.
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new RequestLoggingFilter(HttpLoggingProperties.defaults()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 인증은 시큐리티 필터가 끝낸 뒤라고 본다. 이 테스트가 보는 것은 그 뒤의 로그다.
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthUser(user, "Bearer token"), null, List.of()));

        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        filterLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(capturedLogs);
        SecurityContextHolder.clearContext();
    }

    @Test
    void 피드백_요청은_쿼리와_상태코드와_응답_본문까지_남는다() throws Exception {
        when(feedbackService.requestFeedback(any(), any()))
                .thenReturn(new FeedbackAcceptedResponse("job-8f21", "sess-1", "accepted", null));

        mockMvc.perform(post("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9"))
                .andExpect(status().isOk());

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("--> POST /api/feedbacks?interviewId=12")
                .contains("auth=Bearer ***")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("<-- POST /api/feedbacks")
                .contains("200 OK")
                .contains("job-8f21"));
    }

    @Test
    void 콜백_본문은_로그에_남고_컨트롤러도_같은_값을_받는다() throws Exception {
        mockMvc.perform(post("/api/feedbacks/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CALLBACK_BODY))
                .andExpect(status().isOk());

        // 로그를 위해 본문을 먼저 읽어도 @RequestBody 파싱은 그대로여야 한다.
        ArgumentCaptor<FeedbackCallbackRequest> captor = ArgumentCaptor.forClass(FeedbackCallbackRequest.class);
        verify(feedbackService).handleCallback(captor.capture());
        assertThat(captor.getValue().getJobId()).isEqualTo("job-8f21");
        assertThat(captor.getValue().getStatus()).isEqualTo("succeeded");
        assertThat(captor.getValue().getResult().getOverall().getTotalScore()).isEqualTo(72);

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("--> POST /api/feedbacks/callback")
                .contains("\"jobId\": \"job-8f21\"")
                .contains("근거를 들어 답했습니다."));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("<-- POST /api/feedbacks/callback")
                .contains("200 OK"));
    }

    @Test
    void 이미_생성중이라_거절된_요청도_상태코드와_사유가_남는다() throws Exception {
        when(feedbackService.requestFeedback(any(), any()))
                .thenThrow(BusinessException.conflict("이미 피드백을 생성하고 있습니다. 잠시 후 다시 확인해주세요."));

        mockMvc.perform(post("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isConflict());

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("<-- POST /api/feedbacks")
                    .contains("409 CONFLICT")
                    .contains("이미 피드백을 생성하고 있습니다.");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }

    @Test
    void 아직_끝나지_않은_피드백_조회도_흐름에_남는다() throws Exception {
        when(feedbackService.getFeedback(any(), any()))
                .thenThrow(BusinessException.notFound("아직 생성된 피드백이 없습니다."));

        mockMvc.perform(get("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isNotFound());

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("--> GET /api/feedbacks?interviewId=12"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("<-- GET /api/feedbacks")
                .contains("404 NOT_FOUND"));
    }

    private List<String> messages() {
        return capturedLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
