package repit.repit_api_server.global.auth;

import repit.repit_api_server.global.response.UserResponse;

/**
 * 인증을 마친 요청의 주인.
 *
 * <p>{@link AuthTokenAuthenticationFilter}가 요청당 한 번 만들어 SecurityContext에 넣고,
 * 컨트롤러는 {@code @AuthenticationPrincipal}로 받아 쓴다. 예전처럼 서비스마다 토큰을 들고
 * 다니며 인증 서버에 다시 묻지 않는다 — 한 요청에서 같은 답을 두세 번 받아오던 왕복이 사라진다.
 *
 * <p>원본 토큰까지 함께 들고 있는 것은 이 서버가 인증 서버·분석 서버로 사용자 토큰을 그대로
 * 넘겨야 하는 자리가 남아 있기 때문이다. 그런 곳은 {@link #token()}을 명시적으로 받아 간다.
 */
public record AuthUser(UserResponse user, String token) {

    public Long id() {
        return user.getId();
    }
}
