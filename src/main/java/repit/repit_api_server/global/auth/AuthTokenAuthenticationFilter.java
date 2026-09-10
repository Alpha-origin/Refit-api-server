package repit.repit_api_server.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Authorization 헤더를 읽어 요청의 주인을 정한다.
 *
 * <p>지금까지는 컨트롤러마다 헤더를 문자열로 받아 서비스로 넘기고, 서비스가 그때그때 인증
 * 서버에 물었다. 같은 요청 안에서 두세 번 묻는 자리도 있었고, 아예 묻지 않아 id만 알면 남의
 * 답변이 나가는 자리도 있었다. 확인을 요청 앞단 한 곳으로 모아 그 둘을 함께 없앤다.
 *
 * <p>여기서는 확인만 하고 거절하지 않는다. 인증이 필요한 경로인지는 시큐리티가 판단하고,
 * 확인되지 않은 요청은 {@link RestAuthenticationEntryPoint}가 401로 돌려보낸다.
 */
public class AuthTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthServerClient authServerClient;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final Set<String> skipPaths;

    public AuthTokenAuthenticationFilter(AuthServerClient authServerClient,
                                         HandlerExceptionResolver handlerExceptionResolver) {
        this.authServerClient = authServerClient;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.skipPaths = Set.of(CallbackPaths.ALL);
    }

    /**
     * 서버 간 콜백은 건너뛴다.
     *
     * <p>부르는 쪽에 사용자 토큰이 없어 확인할 것이 없고, 무엇이든 실려 왔을 때 인증 서버에
     * 물었다가 거절당하면 콜백이 통째로 튕긴다. 분석 서버는 두 번 시도한 뒤 결과를 폐기한다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skipPaths.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            // 인증이 필요 없는 경로일 수 있다. 필요한 경로였다면 EntryPoint가 401로 답한다.
            chain.doFilter(request, response);
            return;
        }

        try {
            UserResponse user = authServerClient.getUser(authorization);
            if (user == null || user.getId() == null) {
                // 인증 서버가 200을 주고도 사용자를 비워 보낸 경우다. 인증되지 않은 것으로 두고
                // 넘긴다 — 누구인지 모르는 채로 소유권을 견줄 수는 없다.
                chain.doFilter(request, response);
                return;
            }
            authenticate(request, new AuthUser(user, authorization));
        } catch (RuntimeException e) {
            // 토큰 만료(401)든 인증 서버 장애(5xx)든 사유를 그대로 전한다. 여기서 뭉뚱그려
            // 401로 내리면 다시 로그인해도 풀리지 않는 실패를 사용자가 계속 다시 시도하게 된다.
            //
            // 필터는 DispatcherServlet 밖이라 @RestControllerAdvice가 잡지 못한다. 그래서 같은
            // 예외 처리기로 직접 넘겨, 응답 형태가 컨트롤러에서 난 실패와 어긋나지 않게 한다.
            SecurityContextHolder.clearContext();
            handlerExceptionResolver.resolveException(request, response, null, e);
            return;
        }

        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, AuthUser principal) {
        // 권한은 두지 않는다. 이 서버의 접근 판단은 "인증했는가"와 "본인 것인가"뿐이라,
        // 역할을 흉내 내 두면 쓰이지도 않는 권한 목록을 계속 맞춰야 한다.
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, principal.token(), List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
