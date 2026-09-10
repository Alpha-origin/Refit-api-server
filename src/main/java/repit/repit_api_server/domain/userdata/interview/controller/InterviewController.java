package repit.repit_api_server.domain.userdata.interview.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewResultService;
import repit.repit_api_server.domain.userdata.interview.service.InterviewService;
import repit.repit_api_server.domain.userdata.answer.service.AnswerService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {
    private final InterviewService interviewService;
    private final ChatInterviewResultService chatInterviewResultService;
    private final AnswerService answerService;

    @PostMapping("/create")
    public ApiResponse<InterviewResponse> createInterview(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody CreateInterviewRequest request) {
        return ApiResponse.created(interviewService.createInterview(authUser.id(), request));
    }

    // 면접 시작. 질문 재작성을 접수만 하고, 준비가 끝나면 채팅 서버로 면접 데이터가 넘어간다.
    @PostMapping("/{interviewId}")
    public ApiResponse<InterviewPrepareResponse> prepareInterview(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long interviewId
    ) {
        // 사용자 정보를 통째로 넘긴다. 질문 재작성이 전공(major)까지 실어 보내기 때문이다.
        return ApiResponse.success(interviewService.prepareInterview(authUser.user(), interviewId));
    }

    /**
     * 면접 준비 재시도.
     *
     * <p>질문을 만들지 못했으면 새로 만들고, 넘기지 못한 것뿐이면 전달만 다시 한다. 어느 쪽인지는
     * 준비 조회의 {@code failureStage}가 가리킨다. 준비 중이거나 이미 열린 면접에는 새 작업을
     * 만들지 않는다.
     */
    @PostMapping("/{interviewId}/preparation/retry")
    public ApiResponse<InterviewPrepareResponse> retryPreparation(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long interviewId
    ) {
        return ApiResponse.success(interviewService.retryPreparation(authUser.user(), interviewId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<InterviewResponse>> getAllInterview(
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(interviewService.getAllInterviewsByUserId(authUser.id()));
    }

    @GetMapping("/get")
    public ApiResponse<InterviewResponse> getInterview(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(interviewService.getInterviewById(authUser.id(), interviewId));
    }

    // 다시보기. 면접 전문과 답변이 그대로 나가는 자리라 본인 것만 내려준다.
    @GetMapping("/chat")
    public ApiResponse<ChatInterviewAllResponse> getChatInterview(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(interviewService.getChatInterview(authUser.id(), interviewId));
    }

    // 채팅 서버 전용. 면접 기록을 저장하고, 이어서 채점까지 접수한다.
    @PostMapping("/result")
    public void saveInterview(
            @RequestBody SaveInterviewRequest request
    ) {
        chatInterviewResultService.handleResult(request);
    }
}
