package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackMultiRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    // 1:1과 N:1이 같은 경로로 돌아온다. 콜백 본문은 personas 블록이 있고 없고만 다르다.
    private static final String CALLBACK_PATH = "/api/feedbacks/callback";
    private static final int MAX_QUESTIONS = 50;
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String INTENTION_MISSING = "질문 의도가 기록되지 않았습니다.";

    private final FeedbackRepository feedbackRepository;
    private final FeedbackItemRepository feedbackItemRepository;
    private final FeedbackPersonaRepository feedbackPersonaRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final PersonaRepository personaRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final AiServerClient aiServerClient;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 실패로 간주한다.
    @Value("${app.feedback.pending-timeout:5m}")
    private Duration pendingTimeout;


    // 외부 서버 호출이 세 번 들어가므로 트랜잭션으로 감싸지 않는다.
    // 감싸면 느린 HTTP 응답을 기다리는 내내 DB 커넥션을 붙잡게 된다.
    // 개별 저장은 각 리포지토리 호출이 자체 트랜잭션으로 처리한다.
    public FeedbackAcceptedResponse requestFeedback(Long userId, Long interviewId) {

        InterviewEntity interview = findInterview(interviewId);
        verifyOwner(interview.getUserId(), userId);

        FeedbackStatus running = runningStatusOf(interviewId);
        if (running == FeedbackStatus.PENDING) {
            throw BusinessException.conflict("이미 피드백을 생성하고 있습니다. 잠시 후 다시 확인해주세요.");
        }
        if (running == FeedbackStatus.SUCCEEDED) {
            throw BusinessException.conflict("이미 생성된 피드백이 있습니다.");
        }

        return startFeedback(interview);
    }

    /**
     * 면접이 끝나 기록이 넘어온 직후 채점을 접수한다. 채팅 서버가 부르는 길이라 사용자 토큰이 없다.
     *
     * <p>채점 대상이 누구 것인지는 토큰이 아니라 면접에 적힌 소유자로 정한다. 면접을 시작할 때
     * 이미 본인 확인을 거쳤고, 여기까지 오는 요청은 채팅 서버가 보내는 서버 간 호출뿐이다.
     *
     * <p>중단된 면접도 그대로 채점한다. 답한 데까지는 평가할 것이 남아 있고, 웹이 직접 요청하던
     * 길도 상태를 가리지 않았다. 답변이 하나도 없는 면접은 {@code loadTranscript}가 막는다.
     *
     * <p>이미 채점 중이거나 끝난 면접은 건너뛴다. 웹이 먼저 요청했거나 같은 기록이 두 번 넘어온
     * 경우라, 여기서 거절로 취급하면 정상적인 흐름이 실패로 남는다.
     */
    public void requestFeedbackForFinishedInterview(Long interviewId) {
        InterviewEntity interview = findInterview(interviewId);

        FeedbackStatus running = runningStatusOf(interviewId);
        if (running != null) {
            log.info("이미 채점이 접수된 면접이라 자동 채점을 건너뜁니다. interviewId={}, status={}",
                    interviewId, running);
            return;
        }

        FeedbackAcceptedResponse accepted = startFeedback(interview);
        log.info("면접 기록을 받아 채점을 접수했습니다. interviewId={}, jobId={}",
                interviewId, accepted == null ? null : accepted.getJobId());
    }

    private InterviewEntity findInterview(Long interviewId) {
        return interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
    }

    /**
     * 이 면접에 이미 살아 있는 채점이 있는지. 없으면 null이다.
     *
     * <p>PENDING과 SUCCEEDED만 살아 있는 것으로 본다. FAILED는 다시 요청할 수 있어야 하고,
     * 콜백이 오지 않은 채 시간을 넘긴 PENDING도 여기서 실패로 정리해 다음 요청을 열어준다.
     */
    private FeedbackStatus runningStatusOf(Long interviewId) {
        FeedbackEntity existing = feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        expireIfTimedOut(existing);
        if (existing.getStatus() == FeedbackStatus.PENDING || existing.getStatus() == FeedbackStatus.SUCCEEDED) {
            return existing.getStatus();
        }
        return null;
    }

    /** 분석 서버에 채점을 맡기고 접수 사실을 남긴다. 웹이 부르는 길과 채팅 서버가 부르는 길이 함께 쓴다. */
    private FeedbackAcceptedResponse startFeedback(InterviewEntity interview) {
        // N:1은 면접관별 평가까지 받아야 해서 엔드포인트가 다르다. 1:1 채점에 태우면
        // 질문이 누구 것인지 잃고 personas 블록도 오지 않는다.
        FeedbackAcceptedResponse accepted = interview.getMode() == InterviewMode.MULTI
                ? aiServerClient.requestMultiFeedback(toMultiRequest(interview))
                : aiServerClient.requestSoloFeedback(toSoloRequest(interview));

        saveAccepted(interview, accepted == null ? null : accepted.getJobId());
        return accepted;
    }

    /**
     * 접수 사실을 남긴다.
     *
     * <p>결과 콜백이 접수 응답보다 먼저 도착할 수 있다. 분석 서버 응답이 늦어지는 일이 있어
     * 읽기 제한 시간을 60초까지 늘려둔 것도 그래서다. 콜백이 먼저 오면 세션으로 면접을 되짚어
     * 행이 이미 만들어지고, 그 행에는 결과까지 담겨 있다.
     *
     * <p>그 위에 접수 행을 새로 넣으면 job_id 유일 색인에 걸려 요청이 통째로 실패한다. 색인이
     * 없었더라도 결과가 빈 PENDING 행이 최신이 되어, 조회가 그것을 집어 들고 이미 받아둔 결과를
     * 가린다. 그래서 같은 작업의 행이 이미 있으면 그대로 둔다.
     */
    private void saveAccepted(InterviewEntity interview, String jobId) {
        if (jobId != null && feedbackRepository.findByJobId(jobId).isPresent()) {
            log.info("채점 결과가 접수 응답보다 먼저 도착해 이미 기록돼 있습니다. interviewId={}, jobId={}",
                    interview.getInterviewId(), jobId);
            return;
        }

        try {
            feedbackRepository.save(FeedbackEntity.builder()
                    .interviewId(interview.getInterviewId())
                    .userId(interview.getUserId())
                    .sessionId(interview.getSessionId())
                    .jobId(jobId)
                    .status(FeedbackStatus.PENDING)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 확인과 저장 사이에 콜백이 끼어든 경우다. 결과는 콜백이 이미 남겼으니 접수만 넘어간다.
            // 이 메서드는 트랜잭션 밖에서 돌아 여기서 삼켜도 다른 저장이 함께 말려들지 않는다.
            log.info("접수를 남기는 사이 채점 결과가 먼저 기록됐습니다. interviewId={}, jobId={}",
                    interview.getInterviewId(), jobId);
        }
    }

    /**
     * 채점에 실을 면접 기록. 질문과 답변, 그리고 질문 id 모음을 함께 들고 다닌다.
     *
     * <p>면접 내용은 채팅 서버가 아니라 우리 DB에서 읽는다. 채팅 서버는 면접이 끝나면 결과를
     * 이 서버로 넘기고 곧바로 세션을 지우므로, 피드백을 요청하는 시점에는 물어볼 상대가 없다.
     * 우리 DB가 그때부터 유일한 원본이다.
     */
    private record Transcript(List<QuestionEntity> questions, List<AnswerEntity> answers, Set<Long> questionIds) {
    }

    /**
     * 채점 대상을 읽고, 분석 서버가 422로 즉시 거부하는 조건을 요청 전에 걸러낸다.
     * 콜백까지 갔다 오면 사용자는 한참 기다린 끝에 실패를 보게 된다.
     */
    private Transcript loadTranscript(InterviewEntity interview) {
        Long interviewId = interview.getInterviewId();

        List<QuestionEntity> questions =
                questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        List<AnswerEntity> allAnswers =
                answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId);

        // 면접이 끝나야 채팅 서버가 기록을 넘겨준다. 그 전에는 채점할 것이 없다.
        if (questions.isEmpty() && interview.getStatus() == Status.IN_PROGRESS) {
            throw BusinessException.unprocessable("면접이 아직 끝나지 않았습니다. 면접을 마친 뒤 다시 요청해주세요.");
        }

        Set<Long> questionIds = questions.stream()
                .map(QuestionEntity::getQuestionId)
                .collect(Collectors.toSet());

        List<AnswerEntity> answers = new ArrayList<>();
        for (AnswerEntity answer : allAnswers) {
            if (!questionIds.contains(answer.getQuestionId())) {
                // 질문 없는 답변을 실어 보내면 분석 서버가 요청 전체를 거부한다.
                log.warn("답변에 대응하는 질문이 없어 채점에서 제외합니다. interviewId={}, questionId={}",
                        interviewId, answer.getQuestionId());
                continue;
            }
            answers.add(answer);
        }

        if (questions.isEmpty()) {
            throw BusinessException.unprocessable("채점할 질문이 없습니다.");
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw BusinessException.unprocessable("질문은 최대 " + MAX_QUESTIONS + "개까지 채점할 수 있습니다.");
        }
        if (answers.isEmpty()) {
            throw BusinessException.unprocessable("채점할 답변이 없습니다.");
        }

        return new Transcript(questions, answers, questionIds);
    }

    private FeedbackSoloRequest toSoloRequest(InterviewEntity interview) {
        Transcript transcript = loadTranscript(interview);
        PersonaEntity persona = soloPersonaOf(interview);

        return FeedbackSoloRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .personaType(enumName(persona == null ? null : persona.getType()))
                .personaTone(enumName(persona == null ? null : persona.getTone()))
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .questions(transcript.questions().stream()
                        .map(question -> toQuestion(question, transcript.questionIds()))
                        .toList())
                .answers(transcript.answers().stream()
                        .map(answer -> FeedbackSoloRequest.Answer.builder()
                                .answerId(String.valueOf(answer.getAnswerId()))
                                .questionId(String.valueOf(answer.getQuestionId()))
                                .content(answer.getContent())
                                .createdAt(toUtc(answer.getCreatedAt()))
                                .build())
                        .toList())
                .build();
    }

    /**
     * N:1 채점 요청.
     *
     * <p>1:1과 다른 점은 두 가지다 — 참여 면접관 명단이 함께 나가고, 질문마다 누가 물었는지가 붙는다.
     * 명단이 있어야 담당 문항이 없는 면접관도 결과에 자리를 얻고, 질문별 면접관이 있어야
     * 면접관별 평가가 나뉜다.
     */
    private FeedbackMultiRequest toMultiRequest(InterviewEntity interview) {
        Transcript transcript = loadTranscript(interview);
        List<PersonaEntity> members = orderedPersonas(interview.getInterviewId());
        Map<Long, Long> personaByQuestion = resolveQuestionPersonas(transcript, members.getFirst().getPersonaId());

        return FeedbackMultiRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .personas(members.stream()
                        .map(persona -> FeedbackMultiRequest.Persona.builder()
                                .personaId(String.valueOf(persona.getPersonaId()))
                                .role(persona.getRole() == null ? null : persona.getRole().name())
                                .style(enumName(persona.getType()))
                                .tone(enumName(persona.getTone()))
                                .build())
                        .toList())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .questions(transcript.questions().stream()
                        .map(question -> toMultiQuestion(question, transcript.questionIds(), personaByQuestion))
                        .toList())
                .answers(transcript.answers().stream()
                        .map(answer -> FeedbackMultiRequest.Answer.builder()
                                .answerId(String.valueOf(answer.getAnswerId()))
                                .questionId(String.valueOf(answer.getQuestionId()))
                                .content(answer.getContent())
                                .createdAt(toUtc(answer.getCreatedAt()))
                                .build())
                        .toList())
                .build();
    }

    /** 면접 진행 순서대로 정리한 면접관. 맨 앞이 기술 면접관이다. */
    private List<PersonaEntity> orderedPersonas(Long interviewId) {
        List<Long> personaIds = interviewPersonaRepository
                .findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();
        if (personaIds.isEmpty()) {
            throw BusinessException.unprocessable("면접에 참여한 면접관을 찾을 수 없습니다.");
        }

        Map<Long, PersonaEntity> found = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, persona -> persona));

        List<PersonaEntity> ordered = new ArrayList<>();
        for (Long personaId : personaIds) {
            PersonaEntity persona = found.get(personaId);
            if (persona == null) {
                throw BusinessException.notFound("면접관을 찾을 수 없습니다: " + personaId);
            }
            ordered.add(persona);
        }
        return ordered;
    }

    /**
     * 질문마다 누가 물었는지를 정한다. 이 값이 비면 분석 서버가 요청 전체를 422로 거부한다.
     *
     * <p>꼬리질문은 부모의 면접관을 물려받는다. 채팅 서버가 값을 달아 보내지만, 예전 면접 기록에는
     * 그 자리가 비어 있다. 부모까지 비어 있으면 기술 면접관 것으로 돌린다 — 한 문항 때문에
     * 면접 전체가 채점되지 않는 편보다 낫다.
     *
     * <p>질문은 저장 순서대로 읽히고 부모가 자식보다 먼저 저장되므로, 한 번 훑으면 부모가 먼저 채워진다.
     */
    private Map<Long, Long> resolveQuestionPersonas(Transcript transcript, Long fallbackPersonaId) {
        Map<Long, Long> personaByQuestion = new HashMap<>();

        for (QuestionEntity question : transcript.questions()) {
            Long personaId = question.getPersonaId();
            if (personaId == null && question.getParentId() != null) {
                personaId = personaByQuestion.get(question.getParentId());
            }
            if (personaId == null) {
                log.warn("질문에 면접관이 없어 기술 면접관으로 채점합니다. questionId={}", question.getQuestionId());
                personaId = fallbackPersonaId;
            }
            personaByQuestion.put(question.getQuestionId(), personaId);
        }

        return personaByQuestion;
    }

    private FeedbackSoloRequest.Question toQuestion(QuestionEntity question, Set<Long> questionIds) {
        Type type = typeOf(question, questionIds);

        // ORIGINAL에 parentId가 실려 있거나 FOLLOW에 없으면 분석 서버가 요청 전체를 422로 거부한다.
        String parentId = type == Type.FOLLOW ? String.valueOf(question.getParentId()) : null;

        return FeedbackSoloRequest.Question.builder()
                .questionId(String.valueOf(question.getQuestionId()))
                .parentId(parentId)
                .type(type)
                .intention(intentionOf(question))
                .content(question.getContent())
                .createdAt(toUtc(question.getCreatedAt()))
                .build();
    }

    private FeedbackMultiRequest.Question toMultiQuestion(QuestionEntity question, Set<Long> questionIds,
                                                         Map<Long, Long> personaByQuestion) {
        Type type = typeOf(question, questionIds);
        String parentId = type == Type.FOLLOW ? String.valueOf(question.getParentId()) : null;

        return FeedbackMultiRequest.Question.builder()
                .questionId(String.valueOf(question.getQuestionId()))
                .personaId(String.valueOf(personaByQuestion.get(question.getQuestionId())))
                .parentId(parentId)
                .type(type)
                .intention(intentionOf(question))
                .content(question.getContent())
                .createdAt(toUtc(question.getCreatedAt()))
                .build();
    }

    /**
     * 질문 종류. 부모를 가리키지 못하는 꼬리질문은 홑질문으로 낮춰 보낸다.
     *
     * <p>결과 저장은 부모를 못 찾아도 질문을 남긴다 — 거기서 막으면 면접 기록이 통째로
     * 사라지기 때문이다. 그렇게 남은 한 건을 FOLLOW인 채로 보내면 분석 서버가 요청 전체를
     * 422로 거부해, 이번에는 면접 전체가 채점되지 않는다.
     */
    private Type typeOf(QuestionEntity question, Set<Long> questionIds) {
        if (question.getType() != Type.FOLLOW) {
            return Type.ORIGINAL;
        }
        if (question.getParentId() != null && questionIds.contains(question.getParentId())) {
            return Type.FOLLOW;
        }

        log.warn("꼬리질문의 부모를 찾을 수 없어 단독 질문으로 보냅니다. questionId={}, parentId={}",
                question.getQuestionId(), question.getParentId());
        return Type.ORIGINAL;
    }

    /**
     * 질문 의도. 분석 서버는 이 값을 채점의 유일한 기준으로 삼아 빈 값을 받지 않는다.
     *
     * <p>채팅 서버가 만드는 꼬리질문은 의도가 비어 올 수 있다. 그 한 건 때문에 면접 전체가
     * 채점되지 않는 편보다는, 비었다고 알리고 나머지를 채점받는 편이 낫다.
     */
    private String intentionOf(QuestionEntity question) {
        if (question.getIntention() != null && !question.getIntention().isBlank()) {
            return question.getIntention();
        }
        log.warn("질문 의도가 비어 있어 채점 기준 없이 보냅니다. questionId={}", question.getQuestionId());
        return INTENTION_MISSING;
    }

    /**
     * 분석 서버는 콜백 전송에 두 번 실패하면 결과를 영구 폐기한다.
     * 그 경우 콜백이 영영 오지 않으므로, 오래 걸린 PENDING은 실패로 정리해 재요청을 열어준다.
     */
    private void expireIfTimedOut(FeedbackEntity feedback) {
        if (feedback.getStatus() != FeedbackStatus.PENDING || feedback.getCreatedAt() == null) {
            return;
        }
        if (feedback.getCreatedAt().plus(pendingTimeout).isAfter(LocalDateTime.now())) {
            return;
        }

        log.warn("피드백 콜백이 {} 내에 도착하지 않아 실패 처리합니다. feedbackId={}, jobId={}",
                pendingTimeout, feedback.getFeedbackId(), feedback.getJobId());

        feedback.setStatus(FeedbackStatus.FAILED);
        feedback.setErrorMessage("피드백 생성 결과를 제때 받지 못했습니다. 다시 시도해주세요.");
        feedbackRepository.save(feedback);
    }

    // 면접 답변과 평가는 본인만 볼 수 있어야 한다.
    private void verifyOwner(Long ownerId, Long requesterId) {
        if (!requesterId.equals(ownerId)) {
            throw BusinessException.forbidden("본인의 면접 피드백만 조회할 수 있습니다.");
        }
    }

    /**
     * 1:1 면접의 면접관. 채팅 서버는 성향·어조를 모른다 — 면접을 열 때 넘기지 않으므로
     * 돌려받을 수도 없다. 우리 DB가 원본이라 여기서 직접 읽는다.
     *
     * <p>N:1 면접은 면접관이 여럿이라 interview.personaId가 비어 있다. 그때는 null이고,
     * 면접관 명단은 별도로 실어 보낸다.
     */
    private PersonaEntity soloPersonaOf(InterviewEntity interview) {
        if (interview.getPersonaId() == null) {
            return null;
        }
        return personaRepository.findById(interview.getPersonaId()).orElse(null);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 채팅 서버는 오프셋 없는 시각을 준다. 두 서버 모두 컨테이너에 TZ를 두지 않아 JVM 기본
     * 시간대가 UTC이므로 그대로 UTC로 읽는다. 한쪽 배포에 TZ가 붙으면 여기서부터 어긋난다.
     */
    private OffsetDateTime toUtc(LocalDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.atOffset(ZoneOffset.UTC);
    }

    /**
     * 분석 서버가 채점을 마치고 보내는 콜백. 재전송이 있을 수 있어 같은 결과를 두 번 받아도 안전해야 한다.
     */
    @Transactional
    public void handleCallback(FeedbackCallbackRequest request) {
        FeedbackEntity feedback = findTarget(request);
        if (feedback == null) {
            log.warn("알 수 없는 피드백 콜백을 받았습니다. jobId={}, sessionId={}",
                    request.getJobId(), request.getSessionId());
            return;
        }

        if (feedback.getJobId() == null) {
            feedback.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applySuccess(feedback, request.getResult());
        } else {
            applyFailure(feedback, request.getError());
        }

        feedbackRepository.save(feedback);
    }

    private FeedbackEntity findTarget(FeedbackCallbackRequest request) {
        if (request.getJobId() != null) {
            FeedbackEntity byJobId = feedbackRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        if (request.getSessionId() == null) {
            return null;
        }

        FeedbackEntity bySession =
                feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc(request.getSessionId()).orElse(null);
        if (bySession != null) {
            return bySession;
        }
        return createFromSession(request.getSessionId());
    }

    /**
     * 접수 기록이 없는 콜백을 세션으로 되짚어 받아낸다.
     *
     * <p>채점을 요청하는 쪽은 언제나 이 서버다. 채팅 서버는 웹소켓만 맡고, 면접이 끝나면
     * 기록을 {@code POST /api/interviews/result}로 넘길 뿐 분석 서버를 부르지 않는다.
     * 그러니 정상적인 흐름이라면 콜백에 대응하는 행이 이미 있다.
     *
     * <p>없을 수 있는 경우는 하나다 — 접수는 됐는데 202 응답을 우리가 못 받은 때. 그러면
     * 요청은 예외로 끝나 행이 남지 않지만 채점은 그대로 돌아 콜백이 온다. 여기서 받아두지
     * 않으면 분석 서버는 두 번 시도한 뒤 결과를 영구 폐기하고, 사용자는 채점이 끝났는데도
     * 아무것도 보지 못한다.
     */
    private FeedbackEntity createFromSession(String sessionId) {
        InterviewEntity interview = interviewRepository.findBySessionId(sessionId).orElse(null);
        if (interview == null) {
            return null;
        }
        return feedbackRepository.save(FeedbackEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .sessionId(sessionId)
                .status(FeedbackStatus.PENDING)
                .build());
    }

    /**
     * 채점 결과를 우리 기록에 맞춰 저장한다.
     *
     * <p>검증은 지우기 전에 모두 끝낸다. 지우고 나서 걸러내면, 걸러내다 멈춘 순간 이전 결과도
     * 새 결과도 없는 상태로 남는다.
     *
     * <p>어긋난 값은 거절하지 않고 맞춰 넣는다. 여기서 예외를 던지면 분석 서버는 두 번 더
     * 시도한 뒤 결과를 영구 폐기하고, 사용자는 면접을 다 보고도 채점을 영영 받지 못한다.
     * 어긋난 사실은 로그로 남겨 원인을 좇는다.
     */
    private void applySuccess(FeedbackEntity feedback, FeedbackCallbackRequest.Result result) {
        Long interviewId = feedback.getInterviewId();
        RecordedCounts counts = recordedCounts(interviewId);
        Set<Long> members = interviewMembers(interviewId);

        List<FeedbackPersonaEntity> personaRows =
                verifiedPersonas(feedback, result.getPersonas(), counts, members, modeOf(interviewId));
        Set<Long> personaIds = personaRows.stream()
                .map(FeedbackPersonaEntity::getPersonaId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FeedbackItemEntity> itemRows = verifiedItems(feedback, result.getFeedbacks(), personaIds, counts);

        FeedbackCallbackRequest.Overall overall = result.getOverall();
        if (overall != null) {
            feedback.setTotalScore(score(overall.getTotalScore(), "종합 점수", interviewId));
            feedback.setIntentAlignmentScore(score(overall.getIntentAlignmentScore(), "의도 부합 점수", interviewId));
            feedback.setReliabilityScore(score(overall.getReliabilityScore(), "신뢰도 점수", interviewId));
            feedback.setSummary(overall.getSummary());
            feedback.setStrengths(overall.getStrengths());
            feedback.setImprovements(overall.getImprovements());
            feedback.setFrequentWords(overall.getFrequentWords());
            feedback.setAnsweredCount(counts.answeredCountOr(overall.getAnsweredCount(),
                    "면접 전체 답변 수", interviewId));
            feedback.setQuestionCount(counts.questionCountOr(overall.getQuestionCount(),
                    "면접 전체 문항 수", interviewId));
        }
        feedback.setStatus(FeedbackStatus.SUCCEEDED);
        feedback.setErrorStatusCode(null);
        feedback.setErrorMessage(null);

        // 콜백이 재전송되어도 문항·면접관이 중복되지 않도록 기존 것을 지우고 다시 넣는다.
        feedbackPersonaRepository.deleteAllByFeedbackId(feedback.getFeedbackId());
        feedbackItemRepository.deleteAllByFeedbackId(feedback.getFeedbackId());

        feedbackPersonaRepository.saveAll(personaRows);
        feedbackItemRepository.saveAll(itemRows);
    }

    /** 이 면접에 실제로 앉아 있던 면접관. 1:1은 명단이 없어 비어 있다. */
    private Set<Long> interviewMembers(Long interviewId) {
        return interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 점수는 0..100 안에 있어야 한다.
     *
     * <p>범위를 벗어난 값은 경계로 당긴다. 그대로 두면 눈금이 넘치거나 음수로 그려지고, 비우면
     * 채점이 끝났는데도 점수가 없는 화면이 된다. 어느 쪽이든 사용자에게는 고장으로 보인다.
     */
    private Integer score(Integer value, String label, Long interviewId) {
        if (value == null || (value >= 0 && value <= 100)) {
            return value;
        }
        int clamped = Math.max(0, Math.min(100, value));
        log.warn("{}가 0..100을 벗어나 경계값으로 맞춥니다. interviewId={}, 받은 값={}, 저장={}",
                label, interviewId, value, clamped);
        return clamped;
    }

    /**
     * 문항 목록을 우리 기록과 맞춰본다.
     *
     * <p>같은 문항이 두 번 오면 뒤엣것을 버린다. 남겨두면 결과 화면에 같은 질문이 두 번 나오고,
     * 문항 수도 기록과 어긋난다.
     *
     * <p>우리 기록에 없는 문항은 버리지 않고 남긴다. 채점 자체는 실제로 이뤄진 것이고, 기록과
     * 어긋나는 것은 저장이 늦었거나 식별자 체계가 갈린 쪽일 수 있다. 지우면 되찾을 수 없다.
     */
    private List<FeedbackItemEntity> verifiedItems(FeedbackEntity feedback,
                                                   List<FeedbackCallbackRequest.Item> received,
                                                   Set<Long> personaIds, RecordedCounts counts) {
        List<FeedbackCallbackRequest.Item> items = received == null ? List.of() : received;
        Set<String> seen = new LinkedHashSet<>();
        List<FeedbackItemEntity> entities = new ArrayList<>();

        for (FeedbackCallbackRequest.Item item : items) {
            String questionId = Objects.toString(item.getQuestionId(), "");
            if (!seen.add(questionId)) {
                log.warn("같은 문항이 두 번 실려와 뒤엣것을 버립니다. feedbackId={}, questionId={}",
                        feedback.getFeedbackId(), questionId);
                continue;
            }
            if (!counts.personaByQuestion().isEmpty()
                    && !counts.personaByQuestion().containsKey(parseQuestionId(questionId))) {
                log.warn("이 면접의 기록에 없는 문항이 채점 결과에 실려 있습니다. feedbackId={}, questionId={}",
                        feedback.getFeedbackId(), questionId);
            }

            entities.add(FeedbackItemEntity.builder()
                    .feedbackId(feedback.getFeedbackId())
                    .questionId(questionId)
                    .sortOrder(entities.size())
                    .personaId(resolveItemPersonaId(feedback, item, personaIds, counts))
                    .questionContent(item.getQuestionContent())
                    .intention(item.getIntention())
                    .userAnswer(item.getUserAnswer())
                    .modelAnswer(item.getModelAnswer())
                    .strengths(item.getStrengths())
                    .improvements(item.getImprovements())
                    .comment(item.getComment())
                    .build());
        }
        return entities;
    }

    /**
     * 문항이 가리킬 면접관. 결과에 실린 면접관 명단 안의 값이어야 한다.
     *
     * <p>웹은 문항을 면접관별로 묶어 그린다. 명단에 없는 면접관을 가리키는 문항은 어느 묶음에도
     * 들어가지 못해 화면에서 사라진다. 그래서 명단에 없으면 우리가 기록해둔 담당 면접관으로,
     * 그마저 명단 밖이면 첫 면접관으로 돌린다 — 잘못된 묶음에 들어가는 편이 사라지는 것보다 낫다.
     *
     * <p>1:1은 면접관 명단 자체가 없다. 그때는 콜백 값을 그대로 둔다.
     */
    private Long resolveItemPersonaId(FeedbackEntity feedback, FeedbackCallbackRequest.Item item,
                                      Set<Long> personaIds, RecordedCounts counts) {
        if (personaIds.isEmpty()) {
            return item.getPersonaId();
        }
        if (item.getPersonaId() != null && personaIds.contains(item.getPersonaId())) {
            return item.getPersonaId();
        }

        Long recorded = counts.personaByQuestion().get(parseQuestionId(item.getQuestionId()));
        if (recorded != null && personaIds.contains(recorded)) {
            log.warn("문항의 면접관이 결과 명단에 없어 기록된 담당 면접관으로 맞춥니다. feedbackId={}, questionId={}, 받은 값={}",
                    feedback.getFeedbackId(), item.getQuestionId(), item.getPersonaId());
            return recorded;
        }

        Long first = personaIds.iterator().next();
        log.warn("문항의 면접관을 되짚지 못해 첫 면접관으로 맞춥니다. feedbackId={}, questionId={}, 받은 값={}",
                feedback.getFeedbackId(), item.getQuestionId(), item.getPersonaId());
        return first;
    }

    private Long parseQuestionId(String questionId) {
        if (questionId == null) {
            return null;
        }
        try {
            return Long.valueOf(questionId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 면접관별 종합을 면접 명단과 맞춰본다.
     *
     * <p>1:1 콜백에는 personas가 없다. 반대로 1:1 면접에 실려오면 버린다 — 면접관이 하나뿐인
     * 면접에 면접관별 종합이 붙으면 화면에 있지도 않은 면접관 카드가 생긴다. 판정은 면접의
     * 방식으로 한다. 명단이 비었다고 1:1로 보면, 명단을 읽지 못한 N:1의 채점까지 통째로 버린다.
     *
     * <p>명단에 없는 면접관은 버리지 않고 남긴다. 그 종합에도 채점 내용이 들어 있고, 지우면
     * 되찾을 수 없다. 대신 어긋났다는 사실을 로그로 남긴다.
     */
    private List<FeedbackPersonaEntity> verifiedPersonas(FeedbackEntity feedback,
                                                         List<FeedbackCallbackRequest.Persona> personas,
                                                         RecordedCounts counts, Set<Long> members,
                                                         InterviewMode mode) {
        if (personas == null || personas.isEmpty()) {
            return List.of();
        }
        if (mode == InterviewMode.SOLO) {
            log.warn("1:1 면접의 채점 결과에 면접관별 종합이 실려와 버립니다. feedbackId={}, 받은 면접관={}건",
                    feedback.getFeedbackId(), personas.size());
            return List.of();
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<FeedbackPersonaEntity> entities = new ArrayList<>();
        for (FeedbackCallbackRequest.Persona persona : personas) {
            if (persona.getPersonaId() == null) {
                // 가리킬 수 없는 면접관이다. 남겨두면 문항이 이 자리를 참조할 수도 없다.
                log.warn("면접관 id가 없는 종합이 있어 제외합니다. feedbackId={}, role={}",
                        feedback.getFeedbackId(), persona.getPersonaRole());
                continue;
            }
            if (!seen.add(persona.getPersonaId())) {
                log.warn("같은 면접관의 종합이 두 번 실려와 뒤엣것을 버립니다. feedbackId={}, personaId={}",
                        feedback.getFeedbackId(), persona.getPersonaId());
                continue;
            }
            if (!members.contains(persona.getPersonaId())) {
                log.warn("이 면접에 없던 면접관의 종합이 실려 있습니다. feedbackId={}, personaId={}, 면접 명단={}",
                        feedback.getFeedbackId(), persona.getPersonaId(), members);
            }

            entities.add(FeedbackPersonaEntity.builder()
                    .feedbackId(feedback.getFeedbackId())
                    .personaId(persona.getPersonaId())
                    .personaRole(persona.getPersonaRole())
                    .sortOrder(entities.size())
                    .score(score(persona.getScore(),
                            "면접관 " + persona.getPersonaId() + " 점수", feedback.getInterviewId()))
                    .comment(persona.getComment())
                    .strengths(persona.getStrengths())
                    .improvements(persona.getImprovements())
                    .answeredCount(counts.answeredCountOfPersona(persona, feedback.getInterviewId()))
                    .questionCount(counts.questionCountOfPersona(persona, feedback.getInterviewId()))
                    .build());
        }

        if (!members.isEmpty() && !seen.containsAll(members)) {
            // 면접에 앉아 있던 면접관 중 종합이 오지 않은 사람이 있다. 그 카드는 화면에서 비게 된다.
            log.warn("면접관별 종합이 면접 명단을 다 덮지 못했습니다. feedbackId={}, 받은 면접관={}, 면접 명단={}",
                    feedback.getFeedbackId(), seen, members);
        }
        return entities;
    }

    /**
     * 저장된 면접 기록으로 센 문항 수와 답변 수.
     *
     * <p>면접 중에 생긴 꼬리질문까지 모두 들어 있는 최종 기록이 여기다. 분석 서버가 보낸 수는
     * 채점에 실제로 쓴 문항만 셌을 수 있어, 화면에 "3문항 중 2개 답변"처럼 기록과 다른 수가
     * 걸리면 사용자는 어느 쪽이 맞는지 알 수 없다. 기록 쪽을 진실로 삼는다.
     *
     * <p>답변은 내용이 있는 것만 센다. 답하지 않고 넘어간 문항은 답변 행 자체가 없지만,
     * 빈 답변이 저장된 기록도 있어 함께 걸러낸다.
     */
    private RecordedCounts recordedCounts(Long interviewId) {
        List<QuestionEntity> questions =
                questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        Set<Long> answered = answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId).stream()
                .filter(answer -> answer.getContent() != null && !answer.getContent().isBlank())
                .map(AnswerEntity::getQuestionId)
                .collect(Collectors.toSet());

        Map<Long, Long> personaByQuestion = new LinkedHashMap<>();
        Map<Long, Integer> questionCountByPersona = new LinkedHashMap<>();
        Map<Long, Integer> answeredCountByPersona = new LinkedHashMap<>();
        int answeredCount = 0;

        for (QuestionEntity question : questions) {
            boolean hasAnswer = answered.contains(question.getQuestionId());
            if (hasAnswer) {
                answeredCount++;
            }

            // 꼬리질문은 부모의 면접관을 물려받는다. 부모는 늘 먼저 저장돼 한 번 훑으면 채워진다.
            Long personaId = question.getPersonaId();
            if (personaId == null && question.getParentId() != null) {
                personaId = personaByQuestion.get(question.getParentId());
            }
            if (personaId == null) {
                continue;
            }

            personaByQuestion.put(question.getQuestionId(), personaId);
            questionCountByPersona.merge(personaId, 1, Integer::sum);
            if (hasAnswer) {
                answeredCountByPersona.merge(personaId, 1, Integer::sum);
            }
        }

        return new RecordedCounts(questions.size(), answeredCount, personaByQuestion,
                questionCountByPersona, answeredCountByPersona);
    }

    /** 기록으로 센 수. 분석 서버가 보낸 수와 어긋나면 기록 쪽을 쓰고 그 사실을 남긴다. */
    private record RecordedCounts(int questionCount, int answeredCount,
                                  Map<Long, Long> personaByQuestion,
                                  Map<Long, Integer> questionCountByPersona,
                                  Map<Long, Integer> answeredCountByPersona) {

        Integer questionCountOr(Integer received, String label, Long interviewId) {
            return reconcile(questionCount, received, label, interviewId);
        }

        Integer answeredCountOr(Integer received, String label, Long interviewId) {
            return reconcile(answeredCount, received, label, interviewId);
        }

        Integer questionCountOfPersona(FeedbackCallbackRequest.Persona persona, Long interviewId) {
            return reconcile(questionCountByPersona.getOrDefault(persona.getPersonaId(), 0),
                    persona.getQuestionCount(),
                    "면접관 " + persona.getPersonaId() + " 문항 수", interviewId);
        }

        Integer answeredCountOfPersona(FeedbackCallbackRequest.Persona persona, Long interviewId) {
            return reconcile(answeredCountByPersona.getOrDefault(persona.getPersonaId(), 0),
                    persona.getAnsweredCount(),
                    "면접관 " + persona.getPersonaId() + " 답변 수", interviewId);
        }

        /**
         * 기록으로 센 수를 쓴다.
         *
         * <p>기록이 통째로 비어 있을 때만 받은 값을 쓴다. 기록이 없는 것은 정상 흐름에 없지만,
         * 그 때문에 이미 받아둔 채점의 수까지 0으로 지울 이유는 없다. 기록이 있는데 어떤 값이
         * 0이라면 그것이 사실이다 — 담당 문항에 하나도 답하지 않은 면접관이 그렇다.
         */
        private Integer reconcile(int recorded, Integer received, String label, Long interviewId) {
            if (questionCount == 0) {
                return received;
            }
            if (received != null && received != recorded) {
                log.warn("{}가 기록과 달라 기록 기준으로 저장합니다. interviewId={}, 받은 값={}, 기록={}",
                        label, interviewId, received, recorded);
            }
            return recorded;
        }
    }

    private void applyFailure(FeedbackEntity feedback, FeedbackCallbackRequest.Error error) {
        feedback.setStatus(FeedbackStatus.FAILED);
        feedback.setErrorStatusCode(error == null ? null : error.getStatusCode());
        feedback.setErrorMessage(error == null
                ? "피드백 생성에 실패했습니다."
                : error.getMessage());
    }

    // 인증 서버 호출이 들어가므로 마찬가지로 트랜잭션 밖에서 처리한다.
    public FeedbackResponse getFeedback(Long userId, Long interviewId) {

        FeedbackEntity feedback = feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElseThrow(() -> BusinessException.notFound("피드백이 없습니다. 먼저 피드백 생성을 요청해주세요."));
        verifyOwner(feedback.getUserId(), userId);

        // 폴링하는 클라이언트가 PENDING에 갇히지 않도록 조회 시점에도 판정한다.
        expireIfTimedOut(feedback);

        List<FeedbackPersonaEntity> personas =
                feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(feedback.getFeedbackId());
        List<FeedbackItemEntity> items =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(feedback.getFeedbackId());

        InterviewEntity interview = interviewRepository.findById(feedback.getInterviewId()).orElse(null);
        List<InterviewEntity> interviews = interview == null ? List.of() : List.of(interview);

        return FeedbackResponse.of(feedback, interview,
                personasOf(interviews).get(feedback.getInterviewId()), personas, items);
    }

    /**
     * 사용자가 받은 피드백 목록. 면접당 가장 최근 채점 하나씩만 최근 것부터 돌려준다.
     *
     * <p>피드백에 적힌 소유자로 골라오므로 단건 조회처럼 따로 본인 확인을 하지 않는다.
     *
     * <p>같은 면접을 다시 채점하면 기록이 새로 쌓인다. 단건 조회가 늘 마지막 채점만 보여주므로
     * 목록도 같은 기준으로 맞춘다. 그러지 않으면 한 면접이 여러 번 늘어서 보인다.
     */
    public List<FeedbackResponse> getAllFeedbacks(Long userId) {

        List<FeedbackEntity> feedbacks = latestPerInterview(
                feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(userId));
        if (feedbacks.isEmpty()) {
            return List.of();
        }

        // 단건 조회와 같은 이유로 여기서도 판정한다. 목록만 보는 클라이언트도 멈춘 채점을 알아야 한다.
        feedbacks.forEach(this::expireIfTimedOut);

        List<Long> feedbackIds = feedbacks.stream().map(FeedbackEntity::getFeedbackId).toList();

        // 면접도 피드백마다 따로 읽지 않고 한 번에 읽어 면접별로 나눈다.
        Map<Long, InterviewEntity> interviewById = interviewsOf(
                feedbacks.stream().map(FeedbackEntity::getInterviewId).toList());

        // 피드백마다 따로 읽으면 건수만큼 쿼리가 늘어난다. 한 번에 읽고 피드백별로 나눈다.
        Map<Long, List<FeedbackPersonaEntity>> personasByFeedback =
                feedbackPersonaRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(feedbackIds)
                        .stream()
                        .collect(Collectors.groupingBy(FeedbackPersonaEntity::getFeedbackId));
        Map<Long, List<FeedbackItemEntity>> itemsByFeedback =
                feedbackItemRepository.findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(feedbackIds)
                        .stream()
                        .collect(Collectors.groupingBy(FeedbackItemEntity::getFeedbackId));

        // 성향·난이도의 원본인 면접관도 마찬가지로 한 번에 읽는다.
        Map<Long, PersonaEntity> personaByInterview = personasOf(List.copyOf(interviewById.values()));

        return feedbacks.stream()
                .map(feedback -> FeedbackResponse.of(
                        feedback,
                        interviewById.get(feedback.getInterviewId()),
                        personaByInterview.get(feedback.getInterviewId()),
                        personasByFeedback.getOrDefault(feedback.getFeedbackId(), List.of()),
                        itemsByFeedback.getOrDefault(feedback.getFeedbackId(), List.of())))
                .toList();
    }

    /** 이 면접의 방식. 면접을 찾지 못하면 비워 둔다. */
    private InterviewMode modeOf(Long interviewId) {
        return interviewRepository.findById(interviewId)
                .map(InterviewEntity::getMode)
                .orElse(null);
    }

    /**
     * 여러 면접을 한 번에. 목록 조회가 면접 수만큼 쿼리를 늘리지 않도록 한다.
     *
     * <p>면접 방식(mode)과 면접관은 피드백에 따로 적어두지 않고 그때마다 면접에서 읽는다.
     * 원본은 면접 행이고, 복사해두면 두 곳이 어긋날 수 있다.
     *
     * <p>면접을 찾지 못하면 그 자리를 비워 둔다. 피드백만 남고 면접이 사라진 경우는 정상 흐름에
     * 없지만, 그 때문에 이미 받아둔 채점까지 못 보게 만들 이유는 없다.
     */
    private Map<Long, InterviewEntity> interviewsOf(List<Long> interviewIds) {
        return interviewRepository.findAllById(interviewIds).stream()
                .collect(Collectors.toMap(InterviewEntity::getInterviewId, interview -> interview));
    }

    /**
     * 면접마다 그 면접을 대표하는 면접관. 성향(스타일)과 난이도가 여기에 있다.
     *
     * <p>채점 결과에는 두 값이 없다 — 분석 서버는 직책만 돌려준다. 면접 방식과 같은 이유로
     * 피드백에 복사해두지 않고 조회할 때 면접관 행에서 읽는다.
     *
     * <p>N:1은 면접관이 셋이지만 성향·난이도는 셋이 같은 값으로 묶여 있어 하나로 대표할 수 있다.
     * 진행 순서 맨 앞을 쓴다.
     *
     * <p>채점 결과의 면접관이 아니라 면접에 걸린 면접관을 읽는다. 채점 결과는 콜백이 와야 생기므로,
     * 그쪽을 보면 아직 채점 중이거나 실패한 피드백에서 두 값이 비어버린다.
     *
     * <p>면접관이 지워졌으면 그 면접만 비고 나머지 채점 결과는 그대로 나간다.
     */
    private Map<Long, PersonaEntity> personasOf(List<InterviewEntity> interviews) {
        if (interviews.isEmpty()) {
            return Map.of();
        }

        // 면접별 대표 면접관 id. 1:1은 면접 행에 그대로 적혀 있다.
        Map<Long, Long> personaIdByInterview = new LinkedHashMap<>();
        List<Long> multiInterviewIds = new ArrayList<>();
        for (InterviewEntity interview : interviews) {
            if (interview.getPersonaId() != null) {
                personaIdByInterview.put(interview.getInterviewId(), interview.getPersonaId());
            } else {
                multiInterviewIds.add(interview.getInterviewId());
            }
        }

        // N:1은 면접관이 따로 걸려 있다. 면접 수만큼 늘리지 않도록 한 번에 읽는다.
        if (!multiInterviewIds.isEmpty()) {
            interviewPersonaRepository.findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(multiInterviewIds)
                    .forEach(member -> personaIdByInterview
                            .putIfAbsent(member.getInterviewId(), member.getPersonaId()));
        }

        Set<Long> personaIds = new LinkedHashSet<>(personaIdByInterview.values());
        if (personaIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, PersonaEntity> personaById = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, persona -> persona));

        Map<Long, PersonaEntity> byInterview = new LinkedHashMap<>();
        personaIdByInterview.forEach((interviewId, personaId) -> {
            PersonaEntity persona = personaById.get(personaId);
            if (persona != null) {
                byInterview.put(interviewId, persona);
            }
        });
        return byInterview;
    }

    /** 최근순으로 들어온 목록에서 면접마다 처음 만난 것, 곧 마지막 채점만 남긴다. */
    private List<FeedbackEntity> latestPerInterview(List<FeedbackEntity> feedbacks) {
        return List.copyOf(feedbacks.stream()
                .collect(Collectors.toMap(
                        FeedbackEntity::getInterviewId,
                        feedback -> feedback,
                        (latest, older) -> latest,
                        LinkedHashMap::new))
                .values());
    }
}
