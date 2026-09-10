package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.BrowserSafeResult;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataService.class);

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_PENDING = "pending";

    private final AnalysisDataRepository analysisDataRepository;

    /**
     * 분석 요청 시점에 이번 실행을 접수한다. 소유자를 기록하고, 지난 실행에 남은 결과를 걷어낸다.
     *
     * <p>분석 서버는 같은 jobId를 다시 내려줄 수 있다. 그 행에 지난 실행의 결과가 그대로 남아
     * 있으면, 구독이 붙는 순간 콜백이 오기도 전에 옛 결과가 완료 이벤트로 나간다. 클라이언트는
     * 새 분석이 끝난 줄 알고 지난 결과를 집어 든다.
     *
     * <p>분석 서버가 빠르면 이 메서드보다 콜백이 먼저 도착할 수 있다. 그 결과까지 지우면
     * 방금 받아둔 결과를 잃으므로, {@code requestedAt}(분석 서버에 요청을 보내기 직전 시각)보다
     * 나중에 끝난 결과는 이번 실행의 것으로 보고 그대로 둔다.
     *
     * <p>행 전체를 저장하는 대신 조건을 담은 갱신을 쓰는 것도 같은 이유다. 읽고 쓰는 사이에
     * 도착한 콜백을 아직 비어 있는 result로 덮어쓰지 않는다.
     */
    @Transactional
    public void registerJob(String jobId, Long userId, LocalDateTime requestedAt) {
        if (jobId == null) {
            return;
        }

        LocalDateTime boundary = requestedAt == null ? LocalDateTime.now() : requestedAt;
        boolean cleared = analysisDataRepository.clearPreviousRun(jobId, AnalysisStatus.PENDING, boundary) > 0;

        // 걷어낸 것도 없고 행도 없다면 이번 실행이 처음이다.
        if (!cleared && !analysisDataRepository.existsById(jobId)) {
            analysisDataRepository.save(AnalysisDataEntity.builder()
                    .jobId(jobId)
                    .userId(userId)
                    .build());
            return;
        }

        if (userId != null) {
            analysisDataRepository.updateUserId(jobId, userId);
        }
    }

    /**
     * 분석 결과 콜백을 저장하고, 저장된 상태를 돌려준다. 재전송이 있을 수 있어 두 번 받아도 안전해야 한다.
     *
     * <p>실패 콜백에는 result가 없다. status를 보지 않고 그대로 덮어쓰면 먼저 받아둔 성공
     * 결과까지 지워지므로, 성공 콜백일 때만 결과를 저장한다.
     *
     * <p>돌려주는 값은 요청을 되비춘 것이 아니라 DB에 남은 것이다. 이 메서드는 요청을 여러 갈래로
     * 걸러내므로, 요청을 그대로 흘려보내면 저장이 걸러낸 값까지 구독으로 새어나간다. 클라이언트는
     * DB에 없는 성공을 받아들고 결과를 조회하다 빈손이 된다.
     *
     * <p>저장하지 못했으면 아무것도 돌려주지 않는다. 흘려보낼 상태가 없다는 뜻이다.
     */
    @Transactional
    public CallbackSuccessResponse saveResult(CallbackSuccessRequest request) {
        String jobId = request.getJobId();
        if (jobId == null) {
            log.warn("jobId 없는 분석 콜백을 받았습니다. status={}", request.getStatus());
            return null;
        }

        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            data.setStatus(AnalysisStatus.SUCCEEDED);
            data.setResult(request.getResult());
            data.setErrorStatusCode(null);
            data.setErrorMessage(null);
            data.setCompletedAt(LocalDateTime.now());
            return toResponse(analysisDataRepository.save(data));
        }

        // 이미 결과를 받아둔 작업이라면 뒤늦은 실패 콜백에 그 결과를 잃을 이유가 없다.
        if (data.getStatus() == AnalysisStatus.SUCCEEDED) {
            log.warn("이미 성공한 분석에 실패 콜백이 도착해 무시합니다. jobId={}, error={}",
                    jobId, describeError(request.getError()));
            // 지켜낸 성공을 그대로 돌려준다. 흘려보낼 것이 있다면 그건 이 실패가 아니라 저 성공이다.
            return toResponse(data);
        }

        log.warn("분석에 실패했습니다. jobId={}, status={}, error={}",
                jobId, request.getStatus(), describeError(request.getError()));
        data.setStatus(AnalysisStatus.FAILED);
        if (request.getError() != null) {
            data.setErrorStatusCode(request.getError().getStatus_code());
            data.setErrorMessage(request.getError().getMessage());
        }
        data.setCompletedAt(LocalDateTime.now());
        return toResponse(analysisDataRepository.save(data));
    }

    /**
     * 이미 끝난 작업이면 콜백과 같은 모양으로 돌려준다. 아직 진행 중이거나 모르는 작업이면 null.
     *
     * <p>구독이 콜백보다 늦게 붙는 경우가 있다. 그때 SSE로 흘릴 것이 없으면 클라이언트는
     * 영영 아무것도 받지 못하므로, 저장해둔 결과로 되짚어준다.
     *
     * <p>끝났다는 판정은 SUCCEEDED와 FAILED에만 준다. "PENDING이 아니면 끝난 것"으로 보면
     * 상태를 알 수 없는 행이 완료로 새어나가, 콜백이 오기도 전에 실패 이벤트가 나간다.
     */
    @Transactional(readOnly = true)
    public CallbackSuccessResponse findFinished(String jobId) {
        AnalysisDataEntity data = analysisDataRepository.findById(jobId).orElse(null);
        if (data == null || !isFinished(data)) {
            return null;
        }

        // 되짚기가 언제 무엇을 보고 나갔는지 남긴다. 구독이 붙자마자 완료가 나갔다면 이 줄이
        // 근거를 보여주고, 같은 줄이 몇 초 간격으로 되풀이되면 클라이언트가 완료를 받고도
        // 구독을 닫지 않아 다시 붙고 있다는 뜻이다.
        log.info("구독 시점에 이미 끝나 있어 저장된 결과를 되짚어 보냅니다. jobId={}, status={}, 결과있음={}, 완료시각={}",
                jobId, data.getStatus(), data.getResult() != null, data.getCompletedAt());

        return toResponse(data);
    }

    /**
     * 이 분석이 요청한 사용자의 것인지 확인한다.
     *
     * <p>jobId는 추측할 수 있는 값이 아니지만 그것만으로 열어두면 결과가 유출됐을 때 막을
     * 길이 없다. 분석 결과에는 포트폴리오 요약과 질문의 기대 답변이 그대로 들어 있다.
     *
     * <p>소유자가 비어 있는 행은 접수 응답을 받지 못해 사용자를 붙이지 못한 분석이다.
     * 누구 것인지 모르는 자료라 아무에게도 내주지 않는다.
     *
     * <p>여기서는 소유자만 읽는다. 구독은 결과를 쓰지 않는데, 엔티티를 불러오면 result jsonb가
     * 딸려 와 읽지도 않을 값을 풀어내는 데 시간을 쓴다.
     */
    @Transactional(readOnly = true)
    public void verifyOwner(String jobId, Long userId) {
        AnalysisDataRepository.AnalysisOwner owner = analysisDataRepository.findOwner(jobId)
                .orElseThrow(() -> BusinessException.notFound("분석 결과를 찾을 수 없습니다. jobId=" + jobId));

        verifyOwner(jobId, owner.getUserId(), userId);
    }

    /** 소유자를 견주는 기준. 어디서 읽어 왔든 같은 판정을 거치게 한다. */
    private void verifyOwner(String jobId, Long ownerId, Long requesterId) {
        if (ownerId == null) {
            log.warn("소유자가 붙지 않은 분석을 조회하려 했습니다. jobId={}", jobId);
            throw BusinessException.forbidden("본인의 분석 결과만 볼 수 있습니다.");
        }
        if (!ownerId.equals(requesterId)) {
            throw BusinessException.forbidden("본인의 분석 결과만 볼 수 있습니다.");
        }
    }

    /**
     * 구독에 흘려보낼 만큼 끝났는지.
     *
     * <p>성공은 result가 실제로 있어야 끝난 것으로 본다. status만 보면 결과가 저장된 적 없는
     * 행까지 완료로 나가, 클라이언트는 분석이 끝난 줄 알고 조회했다가 빈손이 된다.
     *
     * <p>실패는 result 없이도 내보낸다. 실패는 결과가 없다는 것이 확정된 상태다. 여기서 막으면
     * 실패한 분석을 구독한 클라이언트는 타임아웃까지 아무것도 받지 못한 채 매달린다.
     */
    private boolean isFinished(AnalysisDataEntity data) {
        if (data.getStatus() == AnalysisStatus.SUCCEEDED) {
            return data.getResult() != null;
        }
        return data.getStatus() == AnalysisStatus.FAILED;
    }

    /**
     * 저장된 행을 구독으로 흘려보낼 모양으로 옮긴다.
     *
     * <p>콜백 전송과 구독 시점 되짚기가 같은 변환을 거치게 해, 어느 경로로 받든 클라이언트가
     * 보는 내용이 같도록 한다.
     */
    /** 구독으로 흘러가는 값이라 채점 기준은 빼고 싣는다. */
    private CallbackSuccessResponse toResponse(AnalysisDataEntity data) {
        return CallbackSuccessResponse.builder()
                .jobId(data.getJobId())
                .status(statusName(data.getStatus()))
                .result(BrowserSafeResult.withoutExpectedAnswers(data.getResult()))
                .error(toError(data))
                .build();
    }

    private CallbackSuccessRequest.Error toError(AnalysisDataEntity data) {
        if (data.getErrorStatusCode() == null && data.getErrorMessage() == null) {
            return null;
        }
        return CallbackSuccessRequest.Error.builder()
                .status_code(data.getErrorStatusCode())
                .message(data.getErrorMessage())
                .build();
    }

    private String describeError(CallbackSuccessRequest.Error error) {
        if (error == null) {
            return null;
        }
        return error.getStatus_code() + " " + error.getMessage();
    }

    /**
     * 저장된 분석 결과를 원형 그대로 돌려준다.
     * 면접에 쓸 질문은 재작성이 끝나는 시점에 이 서버가 채팅 서버로 직접 넘기므로,
     * 여기서 재작성본을 끼워넣지 않는다.
     *
     * <p>모르는 jobId를 빈 결과로 돌려주지 않는다. 그러면 호출자는 "아직 끝나지 않은 분석"과
     * "존재하지 않는 작업"을 똑같은 {@code result: null}로 받아, 잘못된 jobId로 조회하고 있다는
     * 사실을 알 수 없다. 아직 결과가 없는 경우도 상태를 함께 실어 이유가 드러나게 한다.
     *
     * <p>소유자 확인을 여기서 함께 한다. 확인과 조회를 따로 부르면 트랜잭션이 둘로 갈려
     * 영속성 컨텍스트를 공유하지 못하고, 같은 행을 두 번 읽으며 result jsonb를 두 번 풀어낸다.
     * 한 트랜잭션에서 한 번 읽어 두 일을 같이 끝낸다.
     *
     * <p>견주는 순서는 그대로다 — 모르는 작업은 404, 남의 작업은 403. 소유권을 먼저 보면
     * 없는 작업까지 403이 되어, 잘못된 jobId로 조회하는 중인지 알 수 없다.
     */
    @Transactional(readOnly = true)
    public ResultResponse getResultForOwner(String jobId, Long userId) {
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseThrow(() -> BusinessException.notFound("분석 결과를 찾을 수 없습니다. jobId=" + jobId));

        // 분석 결과에는 질문의 기대 답변이 그대로 들어 있다. 본인 것만 내려준다.
        verifyOwner(jobId, data.getUserId(), userId);

        if (data.getResult() == null) {
            log.warn("결과가 아직 없는 분석을 조회했습니다. jobId={}, status={}", jobId, data.getStatus());
        }

        return ResultResponse.builder()
                .jobId(data.getJobId())
                .status(statusName(data.getStatus()))
                // 브라우저가 읽는 자리다. 채점 기준은 API에서 채팅 서버로만 간다.
                .result(BrowserSafeResult.withoutExpectedAnswers(data.getResult()))
                .error(toError(data))
                .build();
    }

    private String statusName(AnalysisStatus status) {
        if (status == AnalysisStatus.SUCCEEDED) {
            return STATUS_SUCCEEDED;
        }
        if (status == AnalysisStatus.FAILED) {
            return STATUS_FAILED;
        }
        return STATUS_PENDING;
    }
}
