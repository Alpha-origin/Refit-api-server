package repit.repit_api_server.domain.userdata.answer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import repit.repit_api_server.domain.userdata.answer.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.answer.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@Service
@RequiredArgsConstructor
public class AnswerService {
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final InterviewRepository interviewRepository;

    public AnswerResponse createAnswer(Long userId, AnswerRequest request) {
        // 없는 질문에 달린 답변은 어디에도 매달 수 없다. 저장하지 않았다는 것을 알려야 한다 —
        // 성공으로 답하면 클라이언트는 답변이 남은 줄 알고 넘어가고, 그대로 사라진다.
        questionRepository.findById(request.getQuestionId())
                .orElseThrow(() -> BusinessException.notFound("질문을 찾을 수 없습니다"));

        AnswerEntity answer = AnswerEntity.builder()
                .interviewId(request.getInterviewId())
                .questionId(request.getQuestionId())
                .userId(userId)
                .responseTime(request.getResponseTime())
                .content(request.getContent())
                .createdAt(LocalDateTime.now())
                .build();
        answerRepository.save(answer);

        return AnswerResponse.from(answer);
    }

    /**
     * 답변 하나를 읽는다. 본인 것만 내려준다.
     *
     * <p>답변 id는 순번이라 옆 번호를 넣어보는 것만으로 남의 답변에 닿는다. 답변은 면접에서
     * 무슨 말을 했는지가 그대로 담긴 자리라, 소유자를 견주지 않으면 조회 한 번으로 새어나간다.
     */
    public AnswerResponse getAnswerById(Long userId, Long answerId) {
        AnswerEntity answer = answerRepository.findById(answerId)
                .orElseThrow(() -> BusinessException.notFound("답변을 찾을 수 없습니다"));
        if (!userId.equals(answer.getUserId())) {
            throw BusinessException.forbidden("본인의 답변만 볼 수 있습니다.");
        }
        return AnswerResponse.from(answer);
    }

    /**
     * 한 면접의 답변 전체.
     *
     * <p>기준은 답변이 아니라 면접의 소유자다. 답변이 하나도 없는 면접이면 견줄 답변이 없어,
     * 답변 쪽만 보고 판단하면 남의 면접 번호로 빈 목록을 받아 존재 여부를 떠볼 수 있다.
     */
    public List<AnswerResponse> getAllAnswer(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!userId.equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접 답변만 볼 수 있습니다.");
        }

        List<AnswerEntity> answers = answerRepository.findAllByInterviewId(interviewId);
        return answers.stream()
                .map(AnswerResponse::from)
                .toList();
    }
}
