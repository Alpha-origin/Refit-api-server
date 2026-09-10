package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.metadata.sse.SseSubscription;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 구독 하나가 분석 완료와 면접 준비 완료를 차례로 받는다.
 *
 * <p>웹은 구독을 붙여둔 채로 면접관을 고르고 면접 시작까지 진행한다. 그래서 분석이 끝났다고
 * 구독을 닫으면, 정작 입장해도 되는 시점을 알릴 길이 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataControllerSseTest {

    @Mock
    private MetaService metaService;
    @Mock
    private AiMetaDataService aiMetaDataService;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AnalysisLaunchService analysisLaunchService;

    private SseEmitterRepository sseEmitterRepository;
    private AiMetaDataController controller;

    @BeforeEach
    void setUp() {
        sseEmitterRepository = new SseEmitterRepository();
        controller = new AiMetaDataController(
                metaService, aiMetaDataService, sseEmitterRepository,
                new SseNotifier(sseEmitterRepository), questionTailorService,
                aiServerClient, analysisLaunchService);
    }

    /**
     * 구독은 소유자만 열 수 있다. 그 확인은 시큐리티 필터와 서비스가 하고, 이 테스트가 보는
     * 것은 그 뒤의 이벤트 흐름이라 인증을 마친 사용자를 그대로 넘긴다.
     */
    private static final AuthUser AUTH_USER = authUser();

    private static AuthUser authUser() {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        return new AuthUser(user, "Bearer token");
    }

    @Test
    void 아직_끝나지_않은_작업은_구독만_붙이고_완료_이벤트를_보내지_않는다() {
        when(aiMetaDataService.findFinished("job-1")).thenReturn(null);

        SseEmitter emitter = controller.subscribe(AUTH_USER, "job-1");

        assertThat(sseEmitterRepository.get("job-1")).isSameAs(emitter);
    }

    /**
     * 분석이 끝나도 아직 면접에 들어갈 수는 없다. 여기서 닫으면 웹은 면접 시작을 요청한 뒤
     * 준비가 끝나는 순간을 받을 연결이 없어 폴링으로 돌아가야 한다.
     */
    @Test
    void 분석이_끝나도_구독을_닫지_않고_면접_준비를_기다린다() {
        when(aiMetaDataService.findFinished("job-2")).thenReturn(null);
        SseEmitter emitter = controller.subscribe(AUTH_USER, "job-2");

        CallbackSuccessRequest request = analysisCallback("job-2");
        when(aiMetaDataService.saveResult(request)).thenReturn(saved("job-2", "succeeded"));

        controller.callback(request);

        assertThat(sseEmitterRepository.get("job-2")).isSameAs(emitter);
    }

    /** 분석이 실패하면 이 흐름으로는 면접을 열 수 없다. 기다릴 것이 없으므로 닫는다. */
    @Test
    void 분석이_실패하면_구독을_닫는다() {
        when(aiMetaDataService.findFinished("job-3")).thenReturn(null);
        controller.subscribe(AUTH_USER, "job-3");

        CallbackSuccessRequest request = analysisCallback("job-3");
        when(aiMetaDataService.saveResult(request)).thenReturn(saved("job-3", "failed"));

        controller.callback(request);

        assertThat(sseEmitterRepository.get("job-3")).isNull();
    }

    // 되짚기는 확인보다 등록이 먼저여야 한다. 순서가 뒤집히면 그 사이에 온 콜백이 버려진다.
    @Test
    void 분석만_끝나_있으면_되짚되_구독은_남긴다() {
        when(aiMetaDataService.findFinished("job-4")).thenReturn(saved("job-4", "succeeded"));
        when(questionTailorService.findPreparationEvent("job-4")).thenReturn(null);

        SseEmitter emitter = controller.subscribe(AUTH_USER, "job-4");

        assertThat(sseEmitterRepository.get("job-4")).isSameAs(emitter);
    }

    /** 면접 준비까지 끝난 뒤에 붙은 구독은 두 단계를 모두 되짚어 받고 끝나야 한다. */
    @Test
    void 면접_준비까지_끝나_있으면_되짚고_구독을_닫는다() {
        when(aiMetaDataService.findFinished("job-5")).thenReturn(saved("job-5", "succeeded"));
        when(questionTailorService.findPreparationEvent("job-5"))
                .thenReturn(new QuestionTailorService.PreparationEvent(SseNotifier.INTERVIEW_READY,
                        InterviewReadyResponse.ready(3L, "sess-1", true)));

        controller.subscribe(AUTH_USER, "job-5");

        assertThat(sseEmitterRepository.get("job-5")).isNull();
    }

    /**
     * 콜백과 구독 시점 되짚기가 겹쳐도 같은 이벤트는 한 번만 나가야 한다.
     *
     * <p>구독을 닫지 않게 되면서 "먼저 걷어낸 쪽만 보낸다"는 규약이 이 이벤트에는 걸리지 않는다.
     * 두 번 나가면 웹은 면접을 두 개 만든다.
     */
    @Test
    void 분석_완료는_겹쳐서_불려도_한_번만_나간다() throws IOException {
        SseSubscription emitter = spy(new SseSubscription(null));
        sseEmitterRepository.save("job-6", emitter);

        CallbackSuccessRequest request = analysisCallback("job-6");
        when(aiMetaDataService.saveResult(request)).thenReturn(saved("job-6", "succeeded"));

        controller.callback(request);
        controller.callback(request);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // 되짚기 경로가 jobId만 보고 정리하면 같은 작업을 보고 있던 다른 구독까지 밀어낸다.
    @Test
    void 되짚기는_같은_작업을_보고_있던_다른_구독을_밀어내지_않는다() {
        SseSubscription other = new SseSubscription(null);
        sseEmitterRepository.save("job-7", other);

        when(aiMetaDataService.findFinished("job-7")).thenReturn(saved("job-7", "succeeded"));
        when(questionTailorService.findPreparationEvent("job-7"))
                .thenReturn(new QuestionTailorService.PreparationEvent(SseNotifier.INTERVIEW_READY,
                        InterviewReadyResponse.ready(3L, "sess-1", true)));

        SseEmitter emitter = controller.subscribe(AUTH_USER, "job-7");

        assertThat(emitter).isNotSameAs(other);
        assertThat(sseEmitterRepository.get("job-7")).isNull();
    }

    /**
     * 구독자가 먼저 떠나면 이벤트를 쓸 때 broken pipe가 난다. 새로고침으로도 나는 일이라
     * 서버 오류로 다루지 않는다. 결과는 이미 DB에 있어 다시 붙으면 되짚어 나간다.
     */
    @Test
    void 이미_떠난_구독은_오류로_닫지_않는다() throws IOException {
        SseSubscription gone = spy(new SseSubscription(null));
        doThrow(new AsyncRequestNotUsableException(
                "ServletResponse failed to flushBuffer", new IOException("Broken pipe")))
                .when(gone).send(any(SseEmitter.SseEventBuilder.class));
        sseEmitterRepository.save("job-8", gone);

        CallbackSuccessRequest request = analysisCallback("job-8");
        when(aiMetaDataService.saveResult(request)).thenReturn(saved("job-8", "succeeded"));

        controller.callback(request);

        assertThat(sseEmitterRepository.get("job-8")).isNull();
        verify(gone).complete();
        verify(gone, never()).completeWithError(any());
    }

    /** 클라이언트 사정이 아닌 실패까지 묻으면 손봐야 할 것을 못 본다. */
    @Test
    void 그_밖의_실패는_오류로_닫는다() throws IOException {
        SseSubscription broken = spy(new SseSubscription(null));
        IOException failure = new IOException("직렬화 실패");
        doThrow(failure).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        sseEmitterRepository.save("job-9", broken);

        CallbackSuccessRequest request = analysisCallback("job-9");
        when(aiMetaDataService.saveResult(request)).thenReturn(saved("job-9", "succeeded"));

        controller.callback(request);

        assertThat(sseEmitterRepository.get("job-9")).isNull();
        verify(broken).completeWithError(failure);
        verify(broken, never()).complete();
    }

    /**
     * 결과 없는 성공 콜백은 실패로 저장된다. 요청을 그대로 흘려보내면 클라이언트는 성공을 받아들고
     * 결과를 조회하다 빈손이 되므로, 나가는 것은 저장된 상태여야 한다.
     */
    @Test
    void 저장된_것이_실패면_요청이_성공이어도_실패로_나간다() throws IOException {
        SseSubscription emitter = spy(new SseSubscription(null));
        sseEmitterRepository.save("job-10", emitter);

        CallbackSuccessRequest request = CallbackSuccessRequest.builder()
                .jobId("job-10")
                .status("succeeded")
                .build();
        CallbackSuccessResponse stored = saved("job-10", "failed");
        when(aiMetaDataService.saveResult(request)).thenReturn(stored);

        controller.callback(request);

        ArgumentCaptor<SseEmitter.SseEventBuilder> event =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(event.capture());

        List<Object> parts = event.getValue().build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(parts).anyMatch(part -> part instanceof String line
                && line.contains("question-generation-failed"));
        // 나간 본문도 요청이 아니라 저장된 것이어야 한다.
        assertThat(parts).contains(stored);
    }

    /** 저장하지 못한 콜백은 흘려보낼 구독을 찾을 수도 없다. jobId 없이 찾아 나서면 그 자체로 터진다. */
    @Test
    void 저장하지_못한_콜백은_흘려보내지_않는다() {
        when(aiMetaDataService.findFinished("job-11")).thenReturn(null);
        SseEmitter emitter = controller.subscribe(AUTH_USER, "job-11");

        CallbackSuccessRequest request = CallbackSuccessRequest.builder()
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build();
        when(aiMetaDataService.saveResult(request)).thenReturn(null);

        controller.callback(request);

        // 구독이 그대로 남아 있다는 건 아무것도 흘려보내지 않았다는 뜻이다.
        assertThat(sseEmitterRepository.get("job-11")).isSameAs(emitter);
    }

    private CallbackSuccessRequest analysisCallback(String jobId) {
        return CallbackSuccessRequest.builder()
                .jobId(jobId)
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build();
    }

    private CallbackSuccessResponse saved(String jobId, String status) {
        return CallbackSuccessResponse.builder()
                .jobId(jobId)
                .status(status)
                .result("succeeded".equals(status) ? Map.of("project_summary", "요약") : null)
                .build();
    }
}
