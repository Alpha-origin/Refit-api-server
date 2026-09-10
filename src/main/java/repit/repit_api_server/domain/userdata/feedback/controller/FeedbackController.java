package repit.repit_api_server.domain.userdata.feedback.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ApiResponse<FeedbackAcceptedResponse> requestFeedback(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.created(feedbackService.requestFeedback(authUser.id(), interviewId));
    }

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 그대로 저장만 하고 응답한다.
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestBody FeedbackCallbackRequest request
    ) {
        feedbackService.handleCallback(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ApiResponse<FeedbackResponse> getFeedback(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(feedbackService.getFeedback(authUser.id(), interviewId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<FeedbackResponse>> getAllFeedbacks(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ApiResponse.success(feedbackService.getAllFeedbacks(authUser.id()));
    }
}
