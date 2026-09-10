package repit.repit_api_server.global.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import repit.repit_api_server.domain.userdata.feedback.controller.FeedbackController;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.config.SecurityConfig;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.logging.HttpLoggingProperties;
import repit.repit_api_server.global.response.UserResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증이 필요한 자리와 열어둘 자리.
 *
 * <p>기본은 막고 열 곳만 적는 규칙이라, 그 경계가 실제로 그렇게 도는지 한 번은 확인해야 한다.
 * 특히 콜백은 열려 있어야 하고 — 막히면 분석 결과가 폐기된다 — 나머지는 막혀 있어야 한다.
 */
@WebMvcTest(controllers = FeedbackController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        InternalCallbackAuthInterceptor.class})
// 요청 로깅 필터가 함께 올라온다. 그 설정값은 본 설정 클래스가 등록하므로 여기서 따로 켜준다.
@EnableConfigurationProperties(HttpLoggingProperties.class)
class SecurityPathRulesTest {

    private static final String TOKEN = "Bearer eyJhbGciOiJIUzI1NiJ9";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthServerClient authServerClient;
    @MockitoBean
    private FeedbackService feedbackService;

    private UserResponse user(Long id) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** 토큰이 없으면 컨트롤러까지 가지 않는다. 사유는 다른 오류와 같은 JSON 모양으로 나간다. */
    @Test
    void 토큰_없는_요청은_401이고_서비스까지_가지_않는다() throws Exception {
        mockMvc.perform(get("/api/feedbacks?interviewId=12"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(feedbackService);
    }

    @Test
    void 확인된_토큰이면_통과한다() throws Exception {
        when(authServerClient.getUser(TOKEN)).thenReturn(user(7L));
        when(feedbackService.requestFeedback(7L, 12L))
                .thenReturn(new FeedbackAcceptedResponse("job-1", "sess-1", "accepted", null));

        mockMvc.perform(post("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk());

        // 인증 서버에는 요청당 한 번만 묻는다.
        verify(authServerClient).getUser(TOKEN);
    }

    /**
     * 분석 서버 콜백은 사용자 토큰 없이 들어온다. 여기서 401로 튕기면 분석 서버는 두 번 시도한
     * 뒤 결과를 폐기한다 — 사용자에게는 끝나지 않는 분석으로 보인다.
     */
    @Test
    void 콜백은_토큰_없이도_받는다() throws Exception {
        mockMvc.perform(post("/api/feedbacks/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"job-1\",\"sessionId\":\"sess-1\",\"status\":\"succeeded\"}"))
                .andExpect(status().isOk());

        verify(feedbackService).handleCallback(any());
        // 콜백 경로에서는 인증 서버에 묻지도 않는다.
        verifyNoInteractions(authServerClient);
    }

    /**
     * 인증 서버가 무너진 것과 토큰이 만료된 것은 다르다. 둘 다 401로 내리면 사용자는 다시
     * 로그인해도 풀리지 않는 실패를 계속 다시 시도하게 된다.
     */
    @Test
    void 인증_서버_장애는_401이_아니라_그대로_전해진다() throws Exception {
        when(authServerClient.getUser(TOKEN)).thenThrow(new ExternalApiException(
                "인증 서버에 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null));

        mockMvc.perform(get("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("인증 서버에 오류가 발생했습니다."));

        verify(feedbackService, never()).getFeedback(any(), any());
    }

    /** 만료된 토큰은 인증 서버가 내려준 사유가 그대로 나간다. */
    @Test
    void 만료된_토큰은_인증_서버가_준_사유로_거절된다() throws Exception {
        when(authServerClient.getUser(TOKEN)).thenThrow(new ExternalApiException(
                "인증에 실패했습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED, null));

        mockMvc.perform(get("/api/feedbacks?interviewId=12")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증에 실패했습니다. 다시 로그인해주세요."));

        verifyNoInteractions(feedbackService);
    }
}
