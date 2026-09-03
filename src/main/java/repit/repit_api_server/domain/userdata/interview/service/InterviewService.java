package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatAnswerResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewQnAResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatQuestionResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    /**
     * N:1 면접의 기술 외 면접관 수.
     *
     * <p>기술 면접관 한 명은 반드시 있어야 한다. 원질문을 다시 쓰는 몫이 그 자리라 대신할 면접관이 없다.
     * 나머지 직책은 정확히 이만큼이 붙어, N:1은 언제나 세 명이다.
     *
     * <p>인원이 곧 문항 수다. 기술 면접관이 두 문항, 나머지가 한 명당 두 문항을 맡아 N:1 면접은 여섯
     * 문항으로 정해져 있다 — {@code QuestionTailorService.TECH_QUESTION_COUNT} 참고. 인원을 가변으로
     * 두면 같은 N:1 면접인데 문항 수가 달라지므로, 늘리려면 문항 배분을 먼저 정하고 함께 바꾼다.
     * (분석 서버 otherPersonas 자체는 네 명까지 받는다.)
     */
    private static final int OTHER_PERSONA_COUNT = 2;

    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final ChatServerClient chatServerClient;
    private final AuthServerClient authServerClient;
    private final AnswerRepository answerRepository;
    private final PersonaRepository personaRepository;
    private final QuestionTailorService questionTailorService;
    private final InterviewPersonaRepository interviewPersonaRepository;

    public InterviewResponse createInterview(String authorization, CreateInterviewRequest request) {
        UserResponse user = currentUser(authorization);

        if (request.getPersonaIds() != null && !request.getPersonaIds().isEmpty()) {
            return createMultiInterview(user, request.getPersonaIds());
        }

        PersonaEntity persona = findPersona(request);

        InterviewEntity interview = InterviewEntity.builder()
                .userId(user.getId())
                .mode(InterviewMode.SOLO)
                .personaId(persona.getPersonaId())
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .build();

        InterviewEntity saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved);
    }

    /**
     * N:1 면접 생성. 기술 면접관 한 명에 다른 직책이 한 명씩 더 붙는다.
     *
     * <p>진행 순서는 기술 면접관이 먼저고, 나머지는 요청에 담긴 순서를 그대로 따른다. 질문 배열도
     * 이 순서를 따르고, 꼬리질문이 부모 질문 바로 뒤에 삽입되므로 한 면접관의 질문 묶음이 끝나야
     * 다음 면접관으로 넘어간다.
     */
    private InterviewResponse createMultiInterview(UserResponse user, List<Long> requestedIds) {
        List<Long> personaIds = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        if (personaIds.size() != requestedIds.size()) {
            throw BusinessException.unprocessable("같은 면접관을 두 번 지정할 수 없습니다.");
        }

        Map<Long, PersonaEntity> personas = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));
        for (Long personaId : personaIds) {
            if (!personas.containsKey(personaId)) {
                throw BusinessException.notFound("페르소나를 찾을 수 없습니다: " + personaId);
            }
        }

        List<PersonaEntity> ordered = orderForMulti(personaIds.stream().map(personas::get).toList());

        InterviewEntity saved = interviewRepository.save(InterviewEntity.builder()
                .userId(user.getId())
                .mode(InterviewMode.MULTI)
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .build());

        List<InterviewPersonaEntity> members = new ArrayList<>();
        for (int order = 0; order < ordered.size(); order++) {
            members.add(InterviewPersonaEntity.builder()
                    .interviewId(saved.getInterviewId())
                    .personaId(ordered.get(order).getPersonaId())
                    .personaOrder(order)
                    .build());
        }
        interviewPersonaRepository.saveAll(members);

        return InterviewResponse.from(saved, ordered.stream().map(PersonaEntity::getPersonaId).toList());
    }

    /**
     * 진행 순서를 정한다. 기술 면접관이 맨 앞이고 나머지는 요청 순서 그대로다.
     *
     * <p>기술 면접관이 없으면 다시 쓸 원질문을 맡을 사람이 없고, 같은 직책이 둘이면 슬롯이
     * 겹쳐 면접이 성립하지 않는다. 인원이 {@link #OTHER_PERSONA_COUNT}명에서 어긋나도 문항 수가
     * 정해진 여섯에서 벗어난다. 셋 다 생성 시점에 막는다 — 여기를 지나면 질문을 만드는 쪽에서는
     * 이미 구성이 맞다고 보고 문항을 나눈다.
     */
    private List<PersonaEntity> orderForMulti(List<PersonaEntity> personas) {
        List<PersonaEntity> tech = personas.stream()
                .filter(persona -> persona.getRole() == Role.TECH)
                .toList();
        if (tech.size() != 1) {
            throw BusinessException.unprocessable("N:1 면접에는 기술 면접관을 한 명 지정해야 합니다.");
        }

        List<PersonaEntity> others = personas.stream()
                .filter(persona -> persona.getRole() != Role.TECH)
                .toList();
        if (others.size() != OTHER_PERSONA_COUNT) {
            throw BusinessException.unprocessable(
                    "N:1 면접에는 기술 외 면접관을 " + OTHER_PERSONA_COUNT + "명 지정해야 합니다.");
        }

        Set<Role> seen = EnumSet.noneOf(Role.class);
        for (PersonaEntity persona : others) {
            if (!seen.add(persona.getRole())) {
                throw BusinessException.unprocessable("같은 직책의 면접관을 두 명 지정할 수 없습니다.");
            }
        }

        List<PersonaEntity> ordered = new ArrayList<>();
        ordered.add(tech.getFirst());
        ordered.addAll(others);
        return ordered;
    }

    /**
     * 면접이 고르는 것은 페르소나 하나다.
     * id가 오면 그것을 쓰고, 없으면 이름으로 찾는다 — 이름은 바뀔 수 있어 id 쪽이 안전하지만,
     * 이름으로 보내던 기존 웹 요청도 그대로 받아야 한다.
     */
    private PersonaEntity findPersona(CreateInterviewRequest request) {
        if (request.getPersonaId() != null) {
            return personaRepository.findById(request.getPersonaId())
                    .orElseThrow(() -> BusinessException.notFound("페르소나를 찾을 수 없습니다"));
        }
        if (request.getPersonaName() == null || request.getPersonaName().isBlank()) {
            throw BusinessException.unprocessable("면접에 쓸 페르소나를 지정해주세요.");
        }
        return personaRepository.findByPersonaName(request.getPersonaName())
                .orElseThrow(() -> BusinessException.notFound(
                        "페르소나를 찾을 수 없습니다: " + request.getPersonaName()));
    }

    private UserResponse currentUser(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user;
    }

    /**
     * 면접 시작. 웹이 부르는 진입점이다.
     *
     * <p>DB에 저장돼 있는 원질문을 페르소나와 함께 분석 서버로 보내 재작성을 요청한다.
     * 재작성은 비동기라 여기서는 접수만 하고, 콜백이 도착해 질문이 확정되면 그때
     * 채팅 서버로 면접 데이터가 넘어간다. 준비 상태는 GET /api/questions/tailor 로 확인한다.
     */
    public InterviewPrepareResponse prepareInterview(String authorization, Long interviewId) {
        UserResponse user = currentUser(authorization);

        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!user.getId().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 시작할 수 있습니다.");
        }
        // N:1은 질문 재작성이 아니라 신규 생성이 섞인 multi tailor로 간다. 갈림길은 서비스 안에 있다.
        QuestionTailorEntity tailor = questionTailorService.requestTailor(interview, user);
        return InterviewPrepareResponse.of(tailor, interview.getSessionId());
    }

    /**
     * 준비가 실패로 끝난 면접을 다시 준비한다.
     *
     * <p>실패한 건을 그대로 두면 면접 시작을 다시 눌러도 그 실패가 그대로 돌아온다. 특히 N:1은
     * 폴백할 원질문이 없어 한 번 실패하면 면접을 새로 만드는 것 말고는 길이 없었다.
     */
    public InterviewPrepareResponse retryPreparation(String authorization, Long interviewId) {
        UserResponse user = currentUser(authorization);

        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!user.getId().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 다시 준비할 수 있습니다.");
        }

        QuestionTailorEntity tailor = questionTailorService.retryPreparation(interview, user);
        return InterviewPrepareResponse.of(tailor, interview.getSessionId());
    }

    public List<InterviewResponse> getAllInterviewsByUserId(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);

        return interviewRepository.findAllByUserId(user.getId()).stream()
                .map(interview -> InterviewResponse.from(interview, personaIdsOf(interview)))
                .toList();
    }

    public InterviewResponse getInterviewById(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview, currentUser(authorization));
        return InterviewResponse.from(interview, personaIdsOf(interview));
    }

    /**
     * 면접은 본인 것만 볼 수 있다.
     *
     * <p>면접 id는 순번이라 옆 번호를 넣어보는 것만으로 남의 면접에 닿는다. 그 안에는 면접
     * 전문과 답변이 들어 있어, 소유자를 견주지 않으면 조회 한 번으로 그대로 새어나간다.
     */
    private void verifyOwner(InterviewEntity interview, UserResponse user) {
        if (!user.getId().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 볼 수 있습니다.");
        }
    }

    /** N:1 면접관 목록. 1:1은 interview.personaId 하나로 끝나므로 조회하지 않는다. */
    private List<Long> personaIdsOf(InterviewEntity interview) {
        if (interview.getMode() != InterviewMode.MULTI) {
            return List.of();
        }
        return interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(interview.getInterviewId())
                .stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();
    }

    /**
     * 면접의 전체 기록. 어디서 읽을지는 면접이 끝났는지에 달려 있다.
     *
     * <p>채팅 서버는 면접이 끝나면 기록을 /api/interviews/result로 넘기고 곧바로 세션을 지운다.
     * 그때부터 우리 DB가 유일한 원본이라, 저장된 기록이 있으면 그쪽에서 읽는다. 채팅 서버에
     * 물으면 이미 없는 세션이라 실패한다.
     *
     * <p>진행 중인 면접은 아직 우리 DB에 아무것도 없다. 채팅 면접 질문은 결과가 넘어올 때
     * 한꺼번에 들어오기 때문이다. 그 사이에는 채팅 서버 세션이 현재 상태를 들고 있다.
     */
    public ChatInterviewAllResponse getChatInterview(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview, currentUser(authorization));

        List<QuestionEntity> questions =
                questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        if (!questions.isEmpty()) {
            return fromStored(interview, questions);
        }

        return chatServerClient.getInterview(interview.getSessionId());
    }

    /**
     * 저장된 기록을 채팅 서버가 내려주던 형태로 되돌린다.
     *
     * <p>질문 번호는 채팅 서버 번호가 아니라 우리 PK로 내려간다. 채팅 서버 번호는 면접 안에서만
     * 유일해 밖에서는 가리키는 것이 없고, 피드백 문항도 우리 PK로 매겨져 결과 화면에서 둘을
     * 맞춰봐야 하기 때문이다.
     */
    private ChatInterviewAllResponse fromStored(InterviewEntity interview, List<QuestionEntity> questions) {
        Map<Long, AnswerEntity> answerByQuestionId =
                answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interview.getInterviewId()).stream()
                        .collect(Collectors.toMap(AnswerEntity::getQuestionId, Function.identity(),
                                // 한 질문에 답변이 둘일 이유는 없지만, 있다면 나중 것이 최종 답변이다.
                                (first, second) -> second));

        List<ChatInterviewQnAResponse> qnAs = questions.stream()
                .map(question -> new ChatInterviewQnAResponse(
                        toChatQuestion(question),
                        toChatAnswer(interview, question, answerByQuestionId.get(question.getQuestionId()))))
                .toList();

        return new ChatInterviewAllResponse(
                interview.getSessionId(),
                interview.getInterviewId(),
                interview.getUserId(),
                interview.getStatus(),
                // 끝난 면접이라 진행 위치는 마지막 질문 다음이다.
                questions.size(),
                interview.getCreatedAt(),
                qnAs);
    }

    private ChatQuestionResponse toChatQuestion(QuestionEntity question) {
        return new ChatQuestionResponse(
                question.getQuestionId(),
                question.getParentId(),
                question.getType(),
                question.getIntention(),
                question.getContent(),
                question.getPersonaId(),
                question.getCreatedAt());
    }

    /** 답하지 않고 넘어간 질문은 답변이 비어 있다. 채팅 서버도 그 자리를 비워서 내려준다. */
    private ChatAnswerResponse toChatAnswer(InterviewEntity interview, QuestionEntity question, AnswerEntity answer) {
        if (answer == null) {
            return null;
        }
        return new ChatAnswerResponse(
                interview.getInterviewId(),
                question.getQuestionId(),
                answer.getUserId(),
                answer.getResponseTime(),
                answer.getContent(),
                answer.getCreatedAt());
    }

    /**
     * 채팅 서버가 면접을 마치고 넘겨주는 전체 기록을 저장한다.
     *
     * <p>채팅 서버는 이 요청을 보낸 직후 세션을 지운다. 여기가 마지막 기회라 실패하면 면접
     * 내용이 사라진다. 콜백이 두 번 와도 같은 결과가 되도록 기존 기록을 지우고 다시 넣는다.
     */
    @Transactional
    public void saveInterview(SaveInterviewRequest request) {
        InterviewEntity interview = interviewRepository.findById(request.getInterviewId())
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));

        // 세션과 상태는 비어 오면 덮어쓰지 않는다. 둘 다 not null 컬럼이라 null을 넣으면 저장이
        // 통째로 실패하고, 그 500이 채팅 서버의 완료 처리까지 끊어 세션을 남긴 채로 끝난다.
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            interview.setSessionId(request.getSessionId());
        }
        // 기록을 넘겼다는 것 자체가 면접이 끝났다는 뜻이다. 상태가 비어 와도 진행 중으로 두지 않는다.
        interview.setStatus(request.getStatus() == null ? Status.COMPLETED : request.getStatus());
        interviewRepository.save(interview);

        answerRepository.deleteAllByInterviewId(interview.getInterviewId());
        questionRepository.deleteAllByInterviewId(interview.getInterviewId());

        List<SaveInterviewRequest.QnA> qnAs = request.getQnaRequests() == null
                ? List.of()
                : request.getQnaRequests();

        Map<Long, Long> questionIdByChatId = saveQuestions(interview, qnAs);
        saveAnswers(interview, qnAs, questionIdByChatId);
    }

    /**
     * 질문을 저장하고 채팅 서버 번호 -> 우리 PK 매핑을 돌려준다.
     *
     * <p>채팅 서버 번호는 PK로 쓸 수 없다. ORIGINAL은 분석 결과 안의 지역 번호(1..N)라 면접이
     * 다르면 같은 번호가 다시 나오고, FOLLOW는 채팅 서버가 매기는 음수다.
     *
     * <p>질문 종류와 부모, 시각은 채팅 서버가 보내지 않아 여기서 되짚는다. 채팅 서버는 꼬리질문을
     * 답한 질문 바로 뒤에 끼워 넣으므로, 목록에서 직전에 나온 원질문이 곧 부모다. 부모는 늘 앞서
     * 저장되므로 한 번 훑으면서 우리 PK로 옮길 수 있다. 그래서 saveAll로 묶지 않고 한 건씩
     * 저장해 매핑을 채운다.
     */
    private Map<Long, Long> saveQuestions(InterviewEntity interview, List<SaveInterviewRequest.QnA> qnAs) {
        Map<Long, Long> questionIdByChatId = new HashMap<>();
        // 직전에 저장한 원질문의 우리 PK. 바로 뒤에 오는 꼬리질문의 부모가 된다.
        Long lastOriginalId = null;

        for (SaveInterviewRequest.QnA qnA : qnAs) {
            SaveInterviewRequest.Question question = qnA.getQuestion();
            if (question == null || question.getId() == null) {
                continue;
            }

            Type type = typeOf(question.getId());
            QuestionEntity saved = questionRepository.save(QuestionEntity.builder()
                    .interviewId(interview.getInterviewId())
                    .chatQuestionId(question.getId())
                    .parentId(type == Type.FOLLOW ? lastOriginalId : null)
                    .personaId(question.getPersonaId())
                    .type(type)
                    .intention(intentionOf(question))
                    .content(question.getQuestion())
                    // 채팅 서버는 질문마다의 시각을 보내지 않는다. 저장 순서가 곧 면접 순서라
                    // 받은 시각으로 채운다. 순서를 읽는 쪽은 question_id 오름차순을 쓴다.
                    .createdAt(LocalDateTime.now())
                    .build());

            questionIdByChatId.put(question.getId(), saved.getQuestionId());
            if (type == Type.ORIGINAL) {
                lastOriginalId = saved.getQuestionId();
            }
        }

        return questionIdByChatId;
    }

    /**
     * 질문 종류. 채팅 서버가 보내지 않아 번호로 되짚는다.
     *
     * <p>원질문 번호는 분석 결과 안의 지역 번호(1..N)라 늘 양수고, 꼬리질문 번호는 채팅 서버가
     * 음수로 매긴다. 그 부호가 둘을 가르는 유일한 단서다.
     */
    private Type typeOf(Long chatQuestionId) {
        return chatQuestionId < 0 ? Type.FOLLOW : Type.ORIGINAL;
    }

    // 답변 시각이 비어 와도 저장까지 실패하지는 않게 한다. 여기서 막히면 면접 기록이 통째로 사라진다.
    private LocalDateTime orNow(LocalDateTime createdAt) {
        return createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
     * 질문 의도. 채점은 이 값 하나를 기준으로 이뤄진다.
     *
     * <p>원질문은 면접을 열 때 우리가 넘긴 기대 답변이 그대로 돌아온다. 꼬리질문은 채팅 서버가
     * 면접 중에 만든 것이라 기대 답변이 없고, 대신 그때 정한 의도가 category로 실려 온다.
     */
    private String intentionOf(SaveInterviewRequest.Question question) {
        String expectedAnswer = question.getExpectedAnswer();
        if (expectedAnswer != null && !expectedAnswer.isBlank()) {
            return expectedAnswer;
        }
        return question.getCategory();
    }

    private void saveAnswers(InterviewEntity interview, List<SaveInterviewRequest.QnA> qnAs,
                             Map<Long, Long> questionIdByChatId) {
        List<AnswerEntity> answers = new ArrayList<>();

        for (SaveInterviewRequest.QnA qnA : qnAs) {
            SaveInterviewRequest.Answer answer = qnA.getAnswer();
            if (answer == null) {
                continue;
            }

            Long questionId = questionIdByChatId.get(answer.getQuestionId());
            if (questionId == null) {
                // 질문 없는 답변은 어디에도 매달 수 없다. 나머지는 저장해야 하므로 이 건만 건너뛴다.
                log.warn("답변에 대응하는 질문이 없어 저장하지 않습니다. interviewId={}, chatQuestionId={}",
                        interview.getInterviewId(), answer.getQuestionId());
                continue;
            }

            answers.add(AnswerEntity.builder()
                    .interviewId(interview.getInterviewId())
                    .questionId(questionId)
                    .userId(interview.getUserId())
                    .responseTime(answer.getResponseTime())
                    .content(answer.getAnswerContent())
                    .createdAt(orNow(answer.getAnswerCreatedAt()))
                    .build());
        }

        answerRepository.saveAll(answers);
    }
}
