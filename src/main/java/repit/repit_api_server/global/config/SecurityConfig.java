package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import repit.repit_api_server.global.auth.AuthTokenAuthenticationFilter;
import repit.repit_api_server.global.auth.CallbackPaths;
import repit.repit_api_server.global.auth.RestAccessDeniedHandler;
import repit.repit_api_server.global.auth.RestAuthenticationEntryPoint;
import repit.repit_api_server.global.client.AuthServerClient;

/**
 * 인증이 필요한 자리와 열어둘 자리를 가른다.
 *
 * <p>기준은 "기본은 막고, 열 곳만 적는다"이다. 반대로 두면 API를 새로 추가할 때 아무 표시도
 * 없이 인증 없는 경로가 하나 늘어난다 — 실제로 답변·질문 조회가 그렇게 열려 있었다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 인증 없이 열어두는 자리. 콜백은 내부 인증값이 따로 지킨다. */
    private static final String[] PUBLIC_PATHS = {
            // 컨트롤러에서 예외가 나면 여기로 forward된다. 막아두면 원래 사유가 401로 덮인다.
            "/error",
            "/actuator/health",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
    };

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * 필터를 빈으로 두지 않고 여기서 만든다.
     *
     * <p>{@code @Component}로 올리면 부트가 서블릿 필터로도 자동 등록해, 시큐리티 체인 밖에서
     * 한 번 더 돈다. 인증 서버 왕복을 줄이려고 만든 필터가 요청마다 두 번 왕복하게 된다.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthServerClient authServerClient,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) throws Exception {
        AuthTokenAuthenticationFilter authTokenFilter =
                new AuthTokenAuthenticationFilter(authServerClient, handlerExceptionResolver);

        http
                // 토큰으로만 인증한다. 쿠키를 쓰지 않으므로 CSRF가 성립하지 않는다.
                .csrf(csrf -> csrf.disable())
                // CORS는 CorsConfig의 필터가 시큐리티보다 먼저 처리한다. 여기서 또 다루면
                // 설정이 두 곳으로 갈라져 어느 쪽이 적용됐는지 좇기 어려워진다.
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(CallbackPaths.ALL).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
