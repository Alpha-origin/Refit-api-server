package repit.repit_api_server.domain.userdata.answer.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.answer.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.answer.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.answer.service.AnswerService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/answer")
@RequiredArgsConstructor
public class AnswerController {
    private final AnswerService answerService;

    @PostMapping
    public ApiResponse<AnswerResponse> createAnswer(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody AnswerRequest request) {
        return ApiResponse.created(answerService.createAnswer(authUser.id(), request));
    }

    @GetMapping
    public ApiResponse<AnswerResponse> getAnswer(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam("answerId") Long answerId) {
        return ApiResponse.success(answerService.getAnswerById(authUser.id(), answerId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<AnswerResponse>> getAllAnswer(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId) {
        return ApiResponse.success(answerService.getAllAnswer(authUser.id(), interviewId));
    }
}
