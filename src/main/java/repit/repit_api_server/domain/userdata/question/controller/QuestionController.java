package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService questionService;

    @PostMapping
    public ApiResponse<QuestionResponse> createQuestion() {
        return ApiResponse.created(questionService.createQuestion());
    }

    @GetMapping
    public ApiResponse<QuestionResponse> getQuestionById(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long questionId) {
        return ApiResponse.success(questionService.getQuestionById(authUser.id(), questionId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<QuestionResponse>> getAllQuestion(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam Long interviewId) {
        return ApiResponse.success(questionService.getAllByInterview(authUser.id(), interviewId));
    }
}
