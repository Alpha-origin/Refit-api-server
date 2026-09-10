package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.domain.metadata.sse.SseEmitters;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.metadata.sse.SseSubscription;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.auth.CurrentUser;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.common.ApiResponse;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {

    // 분석부터 면접 준비까지를 한 구독으로 덮는다. 분석만 덮던 때보다 재작성 단계만큼 길어졌다.
    private static final long SSE_TIMEOUT = 15 * 60 * 1000L; // 15분
    // 끊겼을 때 클라이언트가 다시 붙기까지의 간격. 정해주지 않으면 브라우저 기본값에 맡기게 된다.
    private static final long RECONNECT_DELAY = 3 * 1000L;

    private final MetaService metaService;
    private final AiMetaDataService aiMetaDataService;
    private final SseEmitterRepository sseEmitterRepository;
    private final SseNotifier sseNotifier;
    private final QuestionTailorService questionTailorService;
    private final CurrentUser currentUser;

    private final AiServerClient aiServerClient;
    private final AnalysisLaunchService analysisLaunchService;

    /**
     * 구독도 남의 것을 들여다볼 수 있는 자리다. 흘러나가는 것은 분석 결과와 면접 준비 상태라
     * 조회와 같은 기준으로 막는다 — 구독을 열기 전에 소유자인지부터 확인한다.
     *
     * <p>토큰은 헤더로 받는다. 쿼리 파라미터로 받으면 접속 URL이 프록시 로그와 브라우저 기록에
     * 그대로 남는다. 브라우저 EventSource는 헤더를 실을 수 없으므로 웹은 헤더를 붙일 수 있는
     * 구현으로 붙어야 한다.
     */
    // 응답 타입을 못박아 둔다. 정하지 않으면 협상 결과에 따라 다른 타입으로 나갈 수 있고,
    // 그러면 중간의 프록시가 이벤트 스트림인 줄 모르고 버퍼에 모았다가 한꺼번에 흘려보낸다.
    @GetMapping(value = "/subscribe/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String jobId) {
        aiMetaDataService.verifyOwner(jobId, currentUser.require(authorization).getId());

        SseSubscription emitter = new SseSubscription(SSE_TIMEOUT);

        // 등록을 먼저 하고 결과를 확인한다. 순서를 뒤집으면 확인과 등록 사이에 도착한 콜백이
        // 흘려보낼 구독을 찾지 못해 이벤트를 그대로 버리고, 구독은 타임아웃까지 매달린다.
        SseSubscription previous = sseEmitterRepository.save(jobId, emitter);
        if (previous != null) {
            // 같은 작업을 다시 구독했다. 밀려난 연결에는 이제 아무것도 흐르지 않으니 여기서 끊는다.
            previous.complete();
        }

        // 정리는 자기 자신이 등록돼 있을 때만 한다. jobId만 보고 지우면 뒤늦게 끝난 예전 구독이
        // 방금 붙은 구독을 밀어낸다.
        emitter.onCompletion(() -> sseEmitterRepository.remove(jobId, emitter));
        emitter.onTimeout(() -> sseEmitterRepository.remove(jobId, emitter));
        emitter.onError((e) -> sseEmitterRepository.remove(jobId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .reconnectTime(RECONNECT_DELAY)
                    .name("connect")
                    .data("connected"));
        } catch (IOException e) {
            sseEmitterRepository.remove(jobId, emitter);
            SseEmitters.completeQuietly(emitter);
            return emitter;
        }

        replay(jobId);
        return emitter;
    }

    /**
     * 구독이 붙기 전에 지나간 단계를 되짚어 보낸다.
     *
     * <p>되짚어주지 않으면 이미 끝난 단계를 구독한 클라이언트는 아무것도 받지 못한 채 타임아웃까지
     * 매달려 있고, EventSource가 그때마다 다시 붙어 재연결만 반복한다.
     *
     * <p>단계가 둘이라 하나만 보고 끝낼 수 없다. 분석만 끝났으면 구독을 이어두고 면접 준비를
     * 기다려야 하고, 준비까지 끝났으면 두 이벤트를 차례로 보내고 닫아야 한다.
     *
     * <p>준비가 실패로 끝난 것도 되짚는다. 성공만 되짚으면 실패한 뒤에 붙은 구독은 아무것도
     * 받지 못한 채 타임아웃까지 매달려, 사용자는 준비 중인지 실패인지 끝내 알 수 없다.
     */
    private void replay(String jobId) {
        CallbackSuccessResponse finished = aiMetaDataService.findFinished(jobId);
        if (finished == null) {
            // 분석이 아직 진행 중이다. 그 뒤 단계는 있을 수 없다.
            return;
        }

        sseNotifier.sendAnalysisResult(jobId, finished.getStatus(), finished);

        QuestionTailorService.PreparationEvent prepared = questionTailorService.findPreparationEvent(jobId);
        if (prepared != null) {
            sseNotifier.sendFinal(jobId, prepared.eventName(), prepared.payload());
        }
    }

    @PostMapping("/sendMetaData")
    public ResponseEntity<MetaDataResponse> sendMetaData(@RequestHeader("Authorization") String authorization) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        MetaDataRequest request = MetaDataRequest.builder()
                .gitUrls(forRequest.getGitUrls())
                .fileUrl(forRequest.getFileUrl())
                .build();

        MetaDataResponse response = aiServerClient.sendMetaData(authorization, request);
        return ResponseEntity.ok(response);
    }

    // 이미 올려둔 자료로 분석만 다시 요청한다. 자료를 올리는 길과 같은 접수를 거친다.
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        return ResponseEntity.ok(analysisLaunchService.launch(authorization, forRequest));
    }

    @PostMapping("/generate-mock")
    public ResponseEntity<GenerateResponse> generateMock(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        return ResponseEntity.ok(analysisLaunchService.launchMock(authorization, forRequest));
    }

    /**
     * 분석 서버가 결과를 보내오는 콜백.
     *
     * <p>흘려보내는 것은 요청이 아니라 저장된 결과다. 저장은 요청을 여러 갈래로 걸러내므로
     * — 결과 없는 성공 콜백은 실패로, 이미 성공한 작업에 온 실패 콜백은 무시로 — 요청을 그대로
     * 내보내면 DB에 없는 완료가 구독으로 새어나간다. 클라이언트는 성공을 받아들고 결과를
     * 조회하다 빈손이 된다.
     *
     * <p>저장이 끝난 뒤에야 전송한다. {@code saveResult}가 돌아왔다는 것은 커밋까지 끝났다는
     * 뜻이라, 구독이 받은 완료는 곧바로 조회해도 DB에 있다.
     */
    @PostMapping("/callback")
    public ApiResponse<CallbackSuccessResponse> callback(
            @RequestBody CallbackSuccessRequest request
            ) {
        CallbackSuccessResponse saved = aiMetaDataService.saveResult(request);
        if (saved == null) {
            // 어느 작업의 결과인지 알 수 없어 저장하지 못했다. 흘려보낼 구독도 찾을 수 없다.
            return ApiResponse.success(null);
        }

        sseNotifier.sendAnalysisResult(saved.getJobId(), saved.getStatus(), saved);

        return ApiResponse.success(saved);
    }

    // 분석 결과 조회. 면접 질문은 재작성이 끝나는 시점에 채팅 서버로 직접 넘어간다.
    @GetMapping
    public ApiResponse<ResultResponse> getResult(
            @RequestHeader("Authorization") String authorization,
            @RequestParam String jobId
    ) {
        // 소유자 확인은 조회와 같은 트랜잭션에서 한다. 따로 부르면 같은 행을 두 번 읽으며
        // result jsonb를 두 번 풀어낸다.
        Long userId = currentUser.require(authorization).getId();
        return ApiResponse.success(aiMetaDataService.getResultForOwner(jobId, userId));
    }
}
