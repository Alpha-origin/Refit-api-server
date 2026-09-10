package repit.repit_api_server.global.auth;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import repit.repit_api_server.global.error.ErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증되지 않은 요청에 답한다.
 *
 * <p>기본 동작은 로그인 폼으로 보내거나 {@code WWW-Authenticate}를 붙인 빈 401이다. 이 서버는
 * JSON API라, 실패한 이유를 다른 오류와 같은 모양으로 내려준다 — 클라이언트가 응답 하나를
 * 두 갈래로 파싱하지 않아도 되게.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.", objectMapper);
    }

    /**
     * 본문 타입을 못박아 쓴다.
     *
     * <p>구독을 여는 요청은 {@code text/event-stream}만 받겠다고 하지만, 그 요청이 인증에
     * 걸렸을 때도 사유는 JSON으로 나가야 한다. 협상에 맡기면 고를 타입이 없어 본문 없는
     * 응답이 되고, 토큰이 만료된 것인지 남의 것을 본 것인지 클라이언트가 알 수 없다.
     */
    static void writeError(HttpServletResponse response, HttpStatus status, String message,
                           ObjectMapper objectMapper) throws IOException {
        if (response.isCommitted()) {
            // SSE처럼 이미 흘려보내기 시작한 응답에는 상태 코드를 다시 쓸 수 없다.
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(message));
    }
}
