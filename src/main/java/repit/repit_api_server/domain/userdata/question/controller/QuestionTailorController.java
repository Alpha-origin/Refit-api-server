package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/questions/tailor")
@RequiredArgsConstructor
public class QuestionTailorController {

    private final QuestionTailorService questionTailorService;

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 저장과 전달만 하고 바로 응답한다.
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestBody QuestionTailorCallbackRequest request
    ) {
        questionTailorService.handleCallback(request);
        return ResponseEntity.ok().build();
    }

    // N:1 전용 콜백. 1:1과 본문 형태가 달라 경로를 나눈다.
    @PostMapping("/multi/callback")
    public ResponseEntity<Void> multiCallback(
            @RequestBody QuestionTailorMultiCallbackRequest request
    ) {
        questionTailorService.handleMultiCallback(request);
        return ResponseEntity.ok().build();
    }

    // 면접 시작(POST /api/interviews) 이후 준비 상태를 확인하는 폴링용 조회.
    @GetMapping
    public ApiResponse<QuestionTailorResponse> getTailorResult(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(questionTailorService.getTailorResult(authUser.id(), interviewId));
    }
}
