package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final AiServerClient aiServerClient;
    private final InterviewRepository interviewRepository;

    public QuestionResponse createQuestion() {
        QuestionResponse response = aiServerClient.createQuestion();
        QuestionEntity question = QuestionEntity.builder()
                .interviewId(response.getInterviewId())
                .parentId(response.getQuestionId())
                .type(response.getType())
                .intention(response.getIntention())
                .content(response.getContent())
                .createdAt(LocalDateTime.now())
                .build();
        questionRepository.save(question);
        return response;
    }

    /**
     * 질문 하나를 읽는다. 본인 면접의 질문만 내려준다.
     *
     * <p>재작성된 질문에는 그 사람의 포트폴리오에서 뽑은 내용이 그대로 들어간다. 질문 id는
     * 순번이라, 소유자를 견주지 않으면 번호를 바꿔가며 남의 이력을 읽어낼 수 있다.
     */
    public QuestionResponse getQuestionById(Long userId, Long questionId) {
        // 없는 질문에 성공 응답을 주면 클라이언트는 본문이 빈 것을 정상으로 읽는다.
        QuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> BusinessException.notFound("질문을 찾을 수 없습니다"));
        verifyOwner(userId, question.getInterviewId());

        return QuestionResponse.from(question);
    }

    public List<QuestionResponse> getAllByInterview(Long userId, Long interviewId) {
        InterviewEntity interview = verifyOwner(userId, interviewId);
        List<QuestionEntity> questions = questionRepository.findAllByInterviewId(interview.getInterviewId());
        return questions.stream()
                .map(QuestionResponse::from)
                .toList();
    }

    /** 질문은 그것이 매달린 면접의 주인 것이다. 질문 자체에는 사용자가 적혀 있지 않아 면접을 거쳐 확인한다. */
    private InterviewEntity verifyOwner(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!userId.equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접 질문만 볼 수 있습니다.");
        }
        return interview;
    }
}
