package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 포트폴리오 분석을 시작하고, 그 실행을 접수한다.
 *
 * <p>분석은 두 곳에서 시작된다 — 자료를 올리는 길과 분석만 다시 요청하는 길이다. 접수를 한쪽에만
 * 붙여두면 다른 길로 시작한 분석은 주인 없이 남는다. 주인 없는 분석으로는 면접이 열리지 않는다.
 * 면접 시작이 사용자의 최근 완료 분석을 집어 드는 것으로 시작하기 때문이다. 그래서 요청과 접수를
 * 한 묶음으로 두고 두 길이 같은 것을 쓰게 한다.
 *
 * <p>소유자는 시큐리티 필터가 이미 확인한 사용자다. 예전에는 여기서 인증 서버에 한 번 더 물었고,
 * 그 조회가 실패하면 주인 없는 분석이 남았다. 이제 인증되지 않은 요청은 여기까지 오지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisLaunchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLaunchService.class);

    private static final String CALLBACK_PATH = "/api/v1/ai/callback";

    private final AiServerClient aiServerClient;
    private final AiMetaDataService aiMetaDataService;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    public GenerateResponse launch(Long userId, MetaDataResponse metaData) {
        return launch(userId, metaData, aiServerClient::generate);
    }

    public GenerateResponse launchMock(Long userId, MetaDataResponse metaData) {
        return launch(userId, metaData, aiServerClient::generateMock);
    }

    private GenerateResponse launch(Long userId, MetaDataResponse metaData,
                                    Function<GenerateRequest, GenerateResponse> call) {
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(metaData.getFileUrl())
                .github_urls(metaData.getGitUrls())
                .callback_url(callbackBaseUrl + CALLBACK_PATH)
                .build();

        // 분석 서버에 넘기기 직전 시각. 이 작업에 남아 있는 결과가 지난 실행의 것인지 가르는 기준이다.
        LocalDateTime requestedAt = LocalDateTime.now();
        GenerateResponse response = call.apply(request);
        registerJob(response, userId, requestedAt);
        return response;
    }

    /**
     * 이번 분석 실행을 접수한다. 소유자를 기록하고, 같은 jobId에 남아 있던 지난 결과를 걷어낸다.
     *
     * <p>걷어내지 않으면 구독이 붙는 순간 분석 서버의 콜백보다 먼저 옛 결과가 완료 이벤트로 나간다.
     *
     * <p>다만 이 시점에는 분석 서버가 이미 작업을 접수한 뒤다. 기록이 실패했다고 요청 전체를
     * 실패시키면 클라이언트가 jobId를 받지 못해 결과를 영영 조회할 수 없게 되므로, 기록 실패는
     * 예외로 번지지 않게 막는다.
     */
    private void registerJob(GenerateResponse response, Long userId, LocalDateTime requestedAt) {
        if (response == null || response.getJobId() == null) {
            // jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다.
            log.error("분석 서버 응답에 jobId가 없습니다. status={}, message={}",
                    response == null ? null : response.getStatus(),
                    response == null ? null : response.getMessage());
            throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
        }

        try {
            aiMetaDataService.registerJob(response.getJobId(), userId, requestedAt);
        } catch (RuntimeException e) {
            log.error("분석 작업을 접수하지 못했습니다. jobId={}", response.getJobId(), e);
        }
    }
}
