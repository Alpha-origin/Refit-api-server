package repit.repit_api_server.global.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerExceptionResolver;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.response.UserResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Authorization 헤더를 요청당 한 번만 확인한다.
 *
 * <p>예전에는 컨트롤러와 서비스가 저마다 인증 서버에 물었고, 아예 묻지 않는 자리도 있었다.
 * 그 확인이 여기로 모였으므로 통과·거절·건너뜀의 경계가 여기서 정확해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenAuthenticationFilterTest {

    private static final String TOKEN = "Bearer eyJhbGciOiJIUzI1NiJ9";

    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private HandlerExceptionResolver handlerExceptionResolver;
    @Mock
    private FilterChain chain;

    private AuthTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthTokenAuthenticationFilter(authServerClient, handlerExceptionResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private UserResponse user(Long id) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void 확인된_토큰은_사용자와_함께_컨텍스트에_담긴다() throws Exception {
        MockHttpServletRequest request = request("/api/interviews/getAll");
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        when(authServerClient.getUser(TOKEN)).thenReturn(user(7L));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        AuthUser principal = (AuthUser) authentication.getPrincipal();
        assertThat(principal.id()).isEqualTo(7L);
        // 원본 토큰도 함께 남는다. 인증 서버·분석 서버로 그대로 넘겨야 하는 자리가 있다.
        assertThat(principal.token()).isEqualTo(TOKEN);
        verify(chain).doFilter(request, response);
    }

    /**
     * 헤더가 없다고 여기서 막지 않는다. 인증이 필요 없는 경로일 수 있고, 필요한 경로였다면
     * 그 판단은 시큐리티가 해 401로 답한다.
     */
    @Test
    void 헤더가_없으면_인증하지_않고_넘긴다() throws Exception {
        MockHttpServletRequest request = request("/api/interviews/getAll");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(authServerClient);
        verify(chain).doFilter(request, response);
    }

    /** 인증 서버가 200을 주고도 사용자를 비워 보내면 누구인지 모른다. 소유권을 견줄 수 없다. */
    @Test
    void 사용자가_비어_오면_인증하지_않는다() throws Exception {
        MockHttpServletRequest request = request("/api/interviews/getAll");
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        when(authServerClient.getUser(TOKEN)).thenReturn(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    /**
     * 토큰 만료(401)든 인증 서버 장애(5xx)든 사유를 그대로 전한다. 뭉뚱그려 401로 내리면
     * 다시 로그인해도 풀리지 않는 실패를 사용자가 계속 다시 시도하게 된다.
     */
    @Test
    void 인증_서버_실패는_같은_예외_처리기로_넘긴다() throws Exception {
        MockHttpServletRequest request = request("/api/interviews/getAll");
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        ExternalApiException failure =
                new ExternalApiException("인증 서버에 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null);
        when(authServerClient.getUser(TOKEN)).thenThrow(failure);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(handlerExceptionResolver).resolveException(eq(request), any(), isNull(), eq(failure));
        // 여기서 끝낸다. 넘겼다면 인증되지 않은 요청이 컨트롤러까지 들어간다.
        verify(chain, never()).doFilter(any(), any());
    }

    /**
     * 서버 간 콜백은 건너뛴다. 부르는 쪽에 사용자 토큰이 없고, 무엇이든 실려 왔을 때 물었다가
     * 거절당하면 콜백이 통째로 튕긴다 — 분석 서버는 두 번 시도한 뒤 결과를 폐기한다.
     */
    @Test
    void 콜백_경로는_인증_서버에_묻지_않는다() throws Exception {
        MockHttpServletRequest request = request("/api/v1/ai/callback");
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verifyNoInteractions(authServerClient);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    /** 채팅 서버가 면접 기록을 넘기는 자리도 같은 규칙을 따른다. */
    @Test
    void 면접_결과_콜백도_건너뛴다() throws Exception {
        MockHttpServletRequest request = request("/api/interviews/result");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verifyNoInteractions(authServerClient);
        verify(chain).doFilter(request, response);
    }
}
