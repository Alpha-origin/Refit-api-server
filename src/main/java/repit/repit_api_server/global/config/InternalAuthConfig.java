package repit.repit_api_server.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import repit.repit_api_server.global.auth.CallbackPaths;
import repit.repit_api_server.global.auth.InternalCallbackAuthInterceptor;

/**
 * 서버 간 콜백 경로에만 내부 인증을 건다.
 *
 * <p>여기 걸리는 경로는 사용자가 아니라 분석·채팅 서버가 부르는 자리다. 사용자 토큰으로는
 * 지킬 수 없어 별도 인증을 쓰고, 반대로 사용자용 경로에는 이 인증을 걸지 않는다.
 *
 * <p>경로 목록은 {@link CallbackPaths}가 들고 있다. 시큐리티에서 열어주는 목록과 같은 것을
 * 써야 둘이 어긋나지 않는다 — 한쪽에만 추가하면 아무도 지키지 않는 콜백이 생긴다.
 *
 * <p>경로를 늘릴 때는 부르는 쪽이 헤더를 보내도록 먼저 배포해야 한다. 순서를 뒤집으면
 * 그 콜백이 통째로 401로 튕기고, 분석 서버는 두 번 시도한 뒤 결과를 폐기한다.
 */
@Configuration
@RequiredArgsConstructor
public class InternalAuthConfig implements WebMvcConfigurer {

    private final InternalCallbackAuthInterceptor internalCallbackAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalCallbackAuthInterceptor)
                .addPathPatterns(CallbackPaths.ALL);
    }
}
