package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResultResponse;
import repit.repit_api_server.domain.metadata.dto.response.GeneratedQuestionResponse;
import repit.repit_api_server.domain.metadata.dto.response.ProjectSummaryResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 면접 시작 시 도는 질문 재작성 흐름.
 *
 * <p>DB에 저장된 원질문을 페르소나와 함께 분석 서버로 보내고, 콜백으로 돌아온 재작성 질문을
 * 원질문과 함께 저장한 뒤, 그 전체를 채팅 서버로 넘긴다. 재작성이 실패해도 원질문은 유효한
 * 산출물이라 어떤 경로로 끝나든 면접에 쓸 질문이 남고 채팅 서버로도 넘어간다.
 */
@Service
@RequiredArgsConstructor
public class QuestionTailorService {

    private static final Logger log = LoggerFactory.getLogger(QuestionTailorService.class);

    private static final String CALLBACK_PATH = "/api/questions/tailor/callback";
    private static final String MULTI_CALLBACK_PATH = "/api/questions/tailor/multi/callback";
    private static final String STATUS_SUCCEEDED = "succeeded";

    /**
     * 1:1 면접의 문항 수.
     *
     * <p>분석 서버는 원질문을 몇 개 만들지 우리에게 맞춰주지 않는다. 그대로 흘려보내면 면접 길이가
     * 분석 결과에 따라 들쭉날쭉해지므로, 넘기기 전에 앞에서부터 이만큼만 고른다.
     */
    private static final int SOLO_QUESTION_COUNT = 5;

    // 기술 면접관이 맡을 문항 수. 원질문을 다 쓰면 다른 면접관 몫까지 더해져 면접이 너무 길어진다.
    private static final int TECH_QUESTION_COUNT = 2;
    // 기술 외 면접관 한 명이 맡을 문항 수. 분석 서버 기본값과 같다.
    private static final int OTHER_QUESTION_COUNT = 2;

    private final QuestionTailorRepository questionTailorRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final PersonaRepository personaRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final AiServerClient aiServerClient;
    private final AuthServerClient authServerClient;
    private final ChatInterviewHandoffService chatInterviewHandoffService;
    private final SseNotifier sseNotifier;
    private final ObjectMapper objectMapper;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 실패로 간주하고 원질문으로 진행시킨다.
    @Value("${app.question-tailor.pending-timeout:2m}")
    private Duration pendingTimeout;

    /**
     * 면접 시작 요청이 들어오면 원질문 + 페르소나를 분석 서버로 보낸다.
     *
     * <p>외부 서버 호출이 들어가므로 트랜잭션으로 감싸지 않는다. 감싸면 느린 HTTP 응답을
     * 기다리는 내내 DB 커넥션을 붙잡게 된다.
     *
     * <p>같은 면접을 두 번 시작해도 작업이 겹치지 않도록, 진행 중이거나 이미 끝난 건이 있으면
     * 새로 요청하지 않고 그 상태를 그대로 돌려준다.
     */
    public QuestionTailorEntity requestTailor(InterviewEntity interview, UserResponse user) {
        QuestionTailorEntity existing = latestTailor(interview.getInterviewId());
        if (existing != null) {
            expireIfTimedOut(existing);
            // 이미 확정된 건은 남은 뒷단만 마저 밟는다. 여기서 새 작업을 만들면 같은 면접이 두 번 준비된다.
            completePreparation(existing);
            return existing;
        }

        return startTailor(interview, user);
    }

    /**
     * 면접 준비를 다시 시도한다.
     *
     * <p>멈춘 단계에 따라 하는 일이 다르다. 질문을 만들지 못했으면 처음부터 다시 만들고,
     * 만들어둔 질문을 넘기지 못한 것뿐이면 전달만 다시 한다 — 그 경우까지 새로 만들면
     * 이미 준비됐던 질문이 까닭 없이 바뀐다.
     *
     * <p>준비 중이거나 이미 열린 면접에는 새 작업을 만들지 않는다. 만들면 채팅 서버에 같은
     * 면접을 여는 요청이 한 번 더 가고, 질문도 갈아치워진다.
     */
    public QuestionTailorEntity retryPreparation(InterviewEntity interview, UserResponse user) {
        QuestionTailorEntity existing = latestTailor(interview.getInterviewId());
        if (existing == null) {
            throw BusinessException.conflict("아직 시작하지 않은 면접입니다. 면접 시작을 먼저 요청해주세요.");
        }

        // 콜백을 기다리다 시간만 지난 건은 여기서 실패로 확정한다. 그러지 않으면 이미 가망이
        // 없는 작업 때문에 재시도가 "준비 중"에 막힌다.
        expireIfTimedOut(existing);

        PreparationState state = PreparationState.of(existing);
        if (state.status() == PreparationStatus.PREPARING) {
            throw BusinessException.conflict("질문을 준비하는 중입니다. 잠시 후 다시 시도해주세요.");
        }
        if (state.status() == PreparationStatus.READY) {
            // 이미 열려 있다. 다시 만들 것이 없으니 지금 상태를 그대로 돌려준다.
            return existing;
        }
        if (state.failureStage() == FailureStage.CHAT_DELIVERY) {
            deliverToChatServer(existing);
            return existing;
        }
        return startTailor(interview, user);
    }

    private QuestionTailorEntity latestTailor(Long interviewId) {
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    /** 분석 서버에 질문 준비를 새로 접수한다. 1:1과 N:1의 갈림길이 여기다. */
    private QuestionTailorEntity startTailor(InterviewEntity interview, UserResponse user) {
        SourceQuestions source = loadOriginalQuestions(interview.getUserId(),
                sourceQuestionCount(interview.getMode()));
        if (interview.getMode() == InterviewMode.MULTI) {
            return requestMultiTailor(interview, user, source);
        }

        // 읽어오는 자리에서 이미 쓸 만큼만 골라져 있다. 이것이 그대로 sourceQuestions로 남아,
        // 재작성이 실패해 원질문으로 되돌아가도 문항 수는 같다.
        List<TailoredQuestionResponse> sourceQuestions = source.questions();
        QuestionTailorRequest.Profile profile = resolveProfile(user, interview.getPersonaId());

        QuestionTailorAcceptedResponse accepted = aiServerClient.tailorQuestions(QuestionTailorRequest.builder()
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .profile(profile)
                .questions(sourceQuestions.stream().map(this::toRequestQuestion).toList())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .build());

        return questionTailorRepository.save(QuestionTailorEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .mode(InterviewMode.SOLO)
                .jobId(accepted == null ? null : accepted.getJobId())
                // 재작성본이 어느 분석 결과에서 나왔는지 되짚을 수 있게 남긴다.
                .analysisJobId(source.analysisJobId())
                .status(TailorStatus.PENDING)
                .sourceQuestions(sourceQuestions)
                .chatDelivered(false)
                .build());
    }

    /**
     * N:1 질문 구성 요청.
     *
     * <p>1:1과 달리 두 가지가 한 번에 돈다 — 기술 면접관이 쓸 원질문을 다시 쓰고, 나머지 면접관
     * 몫의 질문을 새로 만든다. 신규 질문의 근거는 프로젝트 요약뿐이라 그것까지 실어 보낸다.
     *
     * <p>원질문을 전부 넘기지는 않는다. 다 쓰면 다른 면접관 몫이 더해져 면접이 너무 길어진다.
     * 기술 면접관 몫인 {@link #TECH_QUESTION_COUNT}개는 {@link #loadOriginalQuestions}가 이미 골라 넘겨준다.
     * 나머지 면접관은 두 명이 {@link #OTHER_QUESTION_COUNT} 문항씩 맡아, N:1 면접은 언제나 여섯 문항이 된다 —
     * 면접관 구성은 {@code InterviewService.orderForMulti}가 고정한다.
     */
    private QuestionTailorEntity requestMultiTailor(InterviewEntity interview, UserResponse user,
                                                    SourceQuestions source) {
        List<PersonaEntity> members = orderedPersonas(interview.getInterviewId());
        PersonaEntity tech = members.getFirst();
        List<PersonaEntity> others = members.subList(1, members.size());

        List<TailoredQuestionResponse> techQuestions = source.questions();
        verifyTechQuestions(techQuestions);

        QuestionTailorAcceptedResponse accepted =
                aiServerClient.tailorQuestionsMulti(QuestionTailorMultiRequest.builder()
                        .interviewId(String.valueOf(interview.getInterviewId()))
                        .userId(String.valueOf(interview.getUserId()))
                        .jobRole(resolveJobRole(user, tech))
                        // persona.career는 면접관 설정이지 지원자 경력이 아니다. 지원자 경력은 아직 수집하지 않는다.
                        .experienceLevel(null)
                        .techPersona(toRequestPersona(tech, techQuestions.size()))
                        .otherPersonas(others.stream()
                                .map(persona -> toRequestPersona(persona, OTHER_QUESTION_COUNT))
                                .toList())
                        .questions(techQuestions.stream().map(this::toMultiRequestQuestion).toList())
                        .projectSummary(toRequestProjectSummary(source))
                        .callbackUrl(callbackBaseUrl + MULTI_CALLBACK_PATH)
                        .build());

        return questionTailorRepository.save(QuestionTailorEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .mode(InterviewMode.MULTI)
                .jobId(accepted == null ? null : accepted.getJobId())
                .analysisJobId(source.analysisJobId())
                .status(TailorStatus.PENDING)
                // 기술 면접관에게 넘긴 원질문만 남는다. 나머지 4문항은 아직 존재하지 않는다.
                .sourceQuestions(techQuestions)
                .chatDelivered(false)
                .build());
    }

    /**
     * 면접 진행 순서대로 정리한 면접관.
     *
     * <p>맨 앞은 반드시 기술 면접관이다. 원질문을 맡을 자리가 거기뿐이라, 없으면 요청을 만들 수 없다.
     * 면접 생성에서 이미 걸러지지만 그 사이에 면접관이 지워질 수 있어 여기서도 확인한다.
     */
    private List<PersonaEntity> orderedPersonas(Long interviewId) {
        List<Long> personaIds = interviewPersonaRepository
                .findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();

        Map<Long, PersonaEntity> found = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));

        List<PersonaEntity> ordered = new ArrayList<>();
        for (Long personaId : personaIds) {
            PersonaEntity persona = found.get(personaId);
            if (persona == null) {
                throw BusinessException.notFound("면접관을 찾을 수 없습니다: " + personaId);
            }
            ordered.add(persona);
        }

        if (ordered.size() < 2 || ordered.getFirst().getRole() != Role.TECH) {
            throw BusinessException.unprocessable("N:1 면접의 면접관 구성이 올바르지 않습니다.");
        }
        return ordered;
    }

    private QuestionTailorMultiRequest.Persona toRequestPersona(PersonaEntity persona, int questionCount) {
        return QuestionTailorMultiRequest.Persona.builder()
                .personaId(String.valueOf(persona.getPersonaId()))
                .role(persona.getRole() == null ? null : persona.getRole().name())
                .style(enumName(persona.getType()))
                .tone(enumName(persona.getTone()))
                .questionCount(questionCount)
                .build();
    }

    /**
     * 기술 면접관에게 넘길 원질문. 분석 서버는 넷 중 하나라도 비면 요청 전체를 422로 거부한다.
     *
     * <p>특히 기대 답변은 재작성 후에도 그대로 확인할 수 있어야 하는 기준값이라 비면 안 된다.
     * 콜백까지 갔다 오면 면접 시작이 그만큼 늦어지므로 요청 전에 막는다.
     */
    private void verifyTechQuestions(List<TailoredQuestionResponse> questions) {
        if (questions.isEmpty()) {
            throw BusinessException.unprocessable("기술 면접관에게 넘길 원질문이 없습니다.");
        }
        for (TailoredQuestionResponse question : questions) {
            if (question.getId() == null
                    || isBlank(question.getCategory())
                    || isBlank(question.getQuestion())
                    || isBlank(question.getExpectedAnswer())) {
                throw BusinessException.unprocessable(
                        "질문을 만들 재료가 모자랍니다. 포트폴리오 분석을 다시 진행해주세요.");
            }
        }
    }

    private QuestionTailorMultiRequest.Question toMultiRequestQuestion(TailoredQuestionResponse question) {
        return QuestionTailorMultiRequest.Question.builder()
                .id(question.getId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .expectedAnswer(question.getExpectedAnswer())
                .basedOn(question.getBasedOn())
                .build();
    }

    /**
     * 신규 질문의 유일한 근거.
     *
     * <p>분석 결과에 통째로 저장해둔 값이라 형태를 우리가 못 박아둘 수 없다. 여기서 해석하다
     * 실패하면 N:1을 열 수 없다는 뜻이므로 그렇게 알린다 — 500으로 나가면 사용자는 서버가
     * 고장난 것인지 분석을 다시 해야 하는 것인지 구분할 수 없다.
     *
     * <p>해석은 이 자리에서만 한다. 원질문을 읽는 경로는 1:1 면접 시작도 함께 지나므로,
     * 그쪽에서 이 값을 건드리면 N:1에만 필요한 해석 때문에 1:1까지 멈춘다.
     */
    private QuestionTailorMultiRequest.ProjectSummary toRequestProjectSummary(SourceQuestions source) {
        Object raw = source.projectSummary();
        ProjectSummaryResponse summary;
        try {
            summary = objectMapper.convertValue(raw, ProjectSummaryResponse.class);
        } catch (IllegalArgumentException | JacksonException e) {
            log.warn("분석 결과의 프로젝트 요약을 해석하지 못했습니다. analysisJobId={}, 받은 키={}",
                    source.analysisJobId(), describeShape(raw), e);
            throw BusinessException.unprocessable(
                    "프로젝트 요약을 읽지 못했습니다. 포트폴리오 분석을 다시 진행해주세요.");
        }

        if (summary == null || isBlank(summary.getOverview())) {
            // 여기까지 왔다는 것은 같은 분석 결과에서 원질문은 이미 읽혔다는 뜻이다. 분석은 끝나
            // 있고 요약만 비어 있으니, 안내도 "먼저 분석하라"가 아니라 그 사실을 가리켜야 한다.
            //
            // 어느 작업의 결과였는지와 실제로 도착한 키를 함께 남긴다. 이름이 어긋나면 값은 조용히
            // 비므로, 이 둘이 없으면 요약이 없는 것인지 이름이 다른 것인지 로그만으로는 가릴 수 없다.
            log.warn("N:1 질문을 만들 프로젝트 요약이 비어 있습니다. analysisJobId={}, 요약읽힘={}, 받은 키={}",
                    source.analysisJobId(), summary != null, describeShape(raw));
            throw BusinessException.unprocessable(
                    "분석 결과에 프로젝트 요약이 없어 N:1 면접을 열 수 없습니다. 포트폴리오 분석을 다시 진행해주세요.");
        }

        return QuestionTailorMultiRequest.ProjectSummary.builder()
                .overview(summary.getOverview())
                // 항목 하나에 빈 칸이 있으면 분석 서버가 요약 전체를 거부한다. 그 한 건만 빼고 넘긴다 —
                // 근거가 조금 줄어들 뿐이지만, 통째로 거부되면 질문이 아예 만들어지지 않는다.
                .repositories(summary.getRepositories() == null ? List.of() : summary.getRepositories().stream()
                        .filter(repository -> !isBlank(repository.getRepo())
                                && !isBlank(repository.getRole())
                                && !isBlank(repository.getDescription()))
                        .map(repository -> QuestionTailorMultiRequest.Repository.builder()
                                .repo(repository.getRepo())
                                .role(repository.getRole())
                                .description(repository.getDescription())
                                .build())
                        .toList())
                .coreFeatures(summary.getCoreFeatures() == null ? List.of() : summary.getCoreFeatures().stream()
                        .filter(feature -> !isBlank(feature.getName()) && !isBlank(feature.getDescription()))
                        .map(feature -> QuestionTailorMultiRequest.CoreFeature.builder()
                                .name(feature.getName())
                                .description(feature.getDescription())
                                .basedOn(feature.getBasedOn())
                                .build())
                        .toList())
                .techStack(summary.getTechStack() == null ? List.of() : summary.getTechStack())
                .build();
    }

    /** 재작성 개인화 축. 사용자 전공이 먼저고, 없으면 기술 면접관의 전공을 쓴다. */
    private String resolveJobRole(UserResponse user, PersonaEntity tech) {
        String jobRole = blankToNull(user.getMajor());
        if (jobRole != null) {
            return jobRole;
        }
        return tech.getMajor() == null ? null : tech.getMajor().name();
    }

    private QuestionTailorRequest.Question toRequestQuestion(TailoredQuestionResponse question) {
        return QuestionTailorRequest.Question.builder()
                .id(question.getId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .expectedAnswer(question.getExpectedAnswer())
                .basedOn(question.getBasedOn())
                .build();
    }

    /**
     * 원질문과 그 질문이 나온 분석 작업.
     * 재작성 건에 출처를 남겨두려고 jobId를, N:1 신규 질문의 근거로 쓰려고 프로젝트 요약을 함께 들고 다닌다.
     */
    private record SourceQuestions(String analysisJobId,
                                   List<TailoredQuestionResponse> questions,
                                   Object projectSummary) {

    }

    /** 면접 방식별로 원질문에서 골라 쓸 문항 수. 1:1은 이것이 곧 면접 문항 수고, N:1은 기술 면접관 몫이다. */
    private int sourceQuestionCount(InterviewMode mode) {
        return mode == InterviewMode.MULTI ? TECH_QUESTION_COUNT : SOLO_QUESTION_COUNT;
    }

    /**
     * 재작성 대상은 해당 사용자의 가장 최근 분석 결과에 담긴 원질문이다.
     *
     * <p>분석 서버가 원질문을 몇 개 만들지는 우리가 정하지 않는다. 넉넉히 만들어 보내도 면접에 쓰는 수는
     * 정해져 있으므로, 많다고 막지 않고 앞에서부터 {@code limit}개만 골라 쓴다.
     *
     * <p>검증은 고른 것에만 건다. 버릴 질문까지 훑으면, 쓰지도 않을 뒤쪽 질문의 id가 겹쳤다는 이유로
     * 멀쩡한 면접이 막힌다. 고른 질문의 id가 비었거나 겹치는 것은 그대로 막는다 — 분석 서버가
     * 그 요청을 422로 거부하므로 넘길 방법이 없다.
     *
     * @param limit 면접에 실제로 쓸 문항 수. 분석 결과가 이보다 적으면 있는 만큼만 돌려준다.
     */
    private SourceQuestions loadOriginalQuestions(Long userId, int limit) {
        AnalysisDataEntity analysisData = analysisDataRepository
                .findLatestCompleted(userId)
                .orElseThrow(() -> BusinessException.notFound(
                        "완료된 분석 결과가 없습니다. 포트폴리오 분석을 먼저 진행해주세요."));

        GenerateResultResponse parsed = objectMapper.convertValue(analysisData.getResult(), GenerateResultResponse.class);
        List<GeneratedQuestionResponse> interview = parsed.getInterview() == null
                ? List.of()
                : parsed.getInterview();

        // 질문이 하나도 없으면 분석 서버가 422로 즉시 거부한다. 요청 전에 걸러낸다.
        if (interview.isEmpty()) {
            throw BusinessException.unprocessable("다시 쓸 질문이 없습니다.");
        }

        Set<Integer> seen = new HashSet<>();
        List<TailoredQuestionResponse> questions = new ArrayList<>();
        for (GeneratedQuestionResponse question : interview.subList(0, Math.min(limit, interview.size()))) {
            if (question.getId() == null || !seen.add(question.getId())) {
                throw BusinessException.unprocessable("질문 id가 중복되어 다시 쓸 수 없습니다.");
            }
            questions.add(TailoredQuestionResponse.builder()
                    .id(question.getId())
                    .category(question.getCategory())
                    .question(question.getQuestion())
                    .expectedAnswer(question.getExpectedAnswer())
                    .basedOn(question.getBasedOn())
                    .build());
        }
        return new SourceQuestions(analysisData.getJobId(), questions, parsed.getProject_summary());
    }

    /**
     * 세 축이 모두 비면 분석 서버가 실패 콜백(422)을 보낸다.
     * 콜백까지 갔다 오면 면접 시작이 그만큼 늦어지므로 요청 전에 막는다.
     */
    private QuestionTailorRequest.Profile resolveProfile(UserResponse user, Long personaId) {
        PersonaEntity persona = personaRepository.findById(personaId).orElse(null);

        String jobRole = blankToNull(user.getMajor());
        if (jobRole == null && persona != null && persona.getMajor() != null) {
            jobRole = persona.getMajor().name();
        }
        String personaType = persona == null ? null : enumName(persona.getType());
        String personaTone = persona == null ? null : enumName(persona.getTone());

        if (jobRole == null && personaType == null) {
            throw BusinessException.unprocessable("질문을 다시 쓸 사전 정보가 없습니다.");
        }

        return QuestionTailorRequest.Profile.builder()
                .jobRole(jobRole)
                // persona.career는 면접관 설정이지 지원자 경력이 아니다. 지원자 경력은 아직 수집하지 않는다.
                .experienceLevel(null)
                .personaType(personaType)
                .personaTone(personaTone)
                .build();
    }

    /**
     * 콜백을 기다리다 시간이 지난 준비 건을 걷어낸다.
     *
     * <p>판정이 요청 스레드에만 있으면, 폴링하지 않고 SSE만 기다리는 클라이언트는 이미 가망이
     * 없는 작업을 구독 타임아웃까지 기다린다. 준비 제한 시간은 2분인데 구독은 15분이라 그
     * 차이만큼 아무 소식 없이 매달려 있게 된다. 그래서 요청과 무관하게 주기적으로도 훑는다.
     *
     * <p>한 건이 걸려 넘어져도 나머지는 정리한다. 실패한 건 하나 때문에 스윕이 통째로 멈추면
     * 그 뒤의 모든 면접이 같은 방식으로 매달린다.
     */
    @Scheduled(fixedDelayString = "${app.question-tailor.sweep-interval:30s}")
    public void sweepTimedOutPreparations() {
        List<QuestionTailorEntity> stale = questionTailorRepository.findAllByStatusAndCreatedAtBefore(
                TailorStatus.PENDING, LocalDateTime.now().minus(pendingTimeout));

        for (QuestionTailorEntity tailor : stale) {
            try {
                if (expire(tailor)) {
                    completePreparation(tailor);
                }
            } catch (RuntimeException e) {
                log.error("시간이 지난 질문 준비 건을 정리하지 못했습니다. tailorId={}, interviewId={}",
                        tailor.getTailorId(), tailor.getInterviewId(), e);
            }
        }
    }

    /**
     * 분석 서버는 콜백 전송에 실패하면 결과를 폐기한다.
     * 그 경우 콜백이 영영 오지 않으므로, 오래 걸린 PENDING은 실패로 정리한다.
     */
    private boolean expireIfTimedOut(QuestionTailorEntity tailor) {
        if (tailor.getStatus() != TailorStatus.PENDING || tailor.getCreatedAt() == null) {
            return false;
        }
        if (tailor.getCreatedAt().plus(pendingTimeout).isAfter(LocalDateTime.now())) {
            return false;
        }
        return expire(tailor);
    }

    /**
     * 시간이 지난 건을 실패로 닫는다. 차지한 쪽만 참을 돌려받는다.
     *
     * <p>스윕과 조회가 같은 건을 동시에 집어들 수 있다. 읽어둔 값만 보고 판단하면 둘 다
     * 실패로 닫고 각자 알려, 같은 실패가 두 번 나간다.
     */
    private boolean expire(QuestionTailorEntity tailor) {
        if (questionTailorRepository.claimExpiration(tailor.getTailorId()) == 0) {
            return false;
        }

        if (tailor.getMode() == InterviewMode.MULTI) {
            log.warn("N:1 질문 구성 콜백이 {} 내에 도착하지 않아 실패 처리합니다. tailorId={}, jobId={}",
                    pendingTimeout, tailor.getTailorId(), tailor.getJobId());

            failWithoutFallback(tailor, "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
            questionTailorRepository.save(tailor);
            return true;
        }

        log.warn("질문 재작성 콜백이 {} 내에 도착하지 않아 원질문으로 진행합니다. tailorId={}, jobId={}",
                pendingTimeout, tailor.getTailorId(), tailor.getJobId());

        fallbackToOriginal(tailor, "질문 재작성 결과를 제때 받지 못해 원질문으로 진행합니다.");
        questionTailorRepository.save(tailor);
        return true;
    }

    /**
     * 확정된 준비 건의 남은 뒷단을 밟는다.
     *
     * <p>넘길 질문이 있으면 채팅 서버로 넘기고, 없으면 실패를 알린다. 알리지 않고 넘어가면
     * 구독은 아무것도 받지 못한 채 남고, 웹은 준비가 끝나기를 기다리는 화면에 머문다.
     *
     * <p>같은 실패를 여러 번 알려도 안전하다. 구독은 이벤트 이름마다 한 번만 흘려보내고,
     * 흘려보낸 뒤에는 닫힌다. 뒤늦게 붙은 구독은 그때 다시 되짚어 받는다.
     */
    private void completePreparation(QuestionTailorEntity tailor) {
        if (tailor.getStatus() == TailorStatus.PENDING) {
            return;
        }
        // 폴백 없이 실패한 N:1은 넘길 질문 자체가 없다. 1:1 실패는 원질문이 들어차 있어 여기 걸리지 않는다.
        if (tailor.getQuestions() == null || tailor.getQuestions().isEmpty()) {
            notifyPreparationFailed(tailor, FailureStage.QUESTION_GENERATION);
            return;
        }
        deliverToChatServer(tailor);
    }

    /**
     * 분석 서버가 재작성을 마치고 보내는 콜백.
     * 재작성 질문을 원질문과 함께 저장한 뒤, 그 전체를 채팅 서버로 넘긴다.
     * 재전송이 있을 수 있어 두 번 받아도 안전해야 한다.
     *
     * <p>저장과 채팅 서버 호출을 한 트랜잭션으로 묶지 않는다. 외부 호출이 늦어져도 재작성 결과는
     * 이미 DB에 남아 있어야 조회와 재전달이 가능하다.
     */
    public void handleCallback(QuestionTailorCallbackRequest request) {
        QuestionTailorEntity tailor = findTarget(request);
        if (tailor == null) {
            log.warn("알 수 없는 질문 재작성 콜백을 받았습니다. jobId={}, interviewId={}",
                    request.getJobId(), request.getInterviewId());
            return;
        }

        if (tailor.getJobId() == null) {
            tailor.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applySuccess(tailor, request.getResult());
        } else {
            applyFailure(tailor, request.getError());
        }

        questionTailorRepository.save(tailor);
        completePreparation(tailor);
    }

    /**
     * 재작성이 확정된 면접 데이터를 채팅 서버로 넘긴다.
     *
     * <p>아직 PENDING이면 넘길 최종 질문이 없으므로 넘어간다. 이미 넘긴 건은 콜백이 재전송돼도
     * 다시 넘기지 않는다. 전달에 실패해도 콜백 자체는 성공 처리한다 — 여기서 예외를 던지면
     * 분석 서버가 결과를 재전송하다 폐기해버려 재작성본까지 잃는다.
     *
     * <p>이 메서드는 콜백과 면접 시작, 준비 상태 조회 세 곳에서 불리고 모두 트랜잭션 밖이다.
     * 읽어둔 값만 보고 판단하면 콜백이 넘기는 중에 들어온 조회가 한 번 더 넘겨, 채팅 서버에
     * 같은 면접을 여는 요청이 두 번 도착한다. 그래서 넘기기 전에 권리를 차지하고, 차지한
     * 쪽만 넘긴다.
     */
    private void deliverToChatServer(QuestionTailorEntity tailor) {
        if (tailor.getStatus() == TailorStatus.PENDING || Boolean.TRUE.equals(tailor.getChatDelivered())) {
            return;
        }
        // 폴백 없이 실패한 N:1은 넘길 질문 자체가 없다. 1:1 실패는 원질문이 들어차 있어 여기 걸리지 않는다.
        if (tailor.getQuestions() == null || tailor.getQuestions().isEmpty()) {
            return;
        }

        // 차지하지 못했다면 다른 쪽이 이미 넘겼거나 넘기는 중이다. 그 결과는 다음 조회에서 읽힌다.
        if (questionTailorRepository.claimChatDelivery(tailor.getTailorId()) == 0) {
            return;
        }

        boolean delivered;
        try {
            chatInterviewHandoffService.deliver(tailor);
            tailor.setChatDelivered(true);
            tailor.setChatErrorMessage(null);
            delivered = true;
        } catch (RuntimeException e) {
            log.error("면접 데이터를 채팅 서버로 넘기지 못했습니다. tailorId={}, interviewId={}",
                    tailor.getTailorId(), tailor.getInterviewId(), e);
            // 차지했던 것을 놓아준다. 그대로 두면 넘어간 적이 없는데도 넘긴 것으로 남아 다시 시도하지 못한다.
            tailor.setChatDelivered(false);
            tailor.setChatErrorMessage(e.getMessage());
            delivered = false;
        }
        questionTailorRepository.save(tailor);

        // 저장이 끝난 뒤에 알린다. 웹은 이 이벤트를 받고 곧바로 상태를 조회하므로, 먼저 보내면
        // 아직 저장되지 않은 것을 조회하게 된다.
        notifySubscriber(tailor, delivered);
    }

    /**
     * 구독 중인 웹에 면접 준비가 끝났음을 알린다.
     *
     * <p>웹은 분석 jobId 하나로 구독한 채 면접관을 고르고 면접 시작까지 진행한다. 그 구독을
     * 되찾는 열쇠가 재작성 건에 남겨둔 분석 jobId다.
     *
     * <p>1:1 재작성이 실패했어도 준비 완료로 알린다. 그때는 원질문이 폴백으로 들어가 면접이
     * 그대로 열리기 때문이다. 이 자리에서 못 여는 것은 채팅 서버에 면접을 열지 못했을 때뿐이고,
     * 만들 질문 자체가 없어 멈춘 N:1은 {@link #completePreparation}이 따로 알린다.
     */
    private void notifySubscriber(QuestionTailorEntity tailor, boolean delivered) {
        if (!delivered) {
            notifyPreparationFailed(tailor, FailureStage.CHAT_DELIVERY);
            return;
        }

        String analysisJobId = tailor.getAnalysisJobId();
        if (analysisJobId == null) {
            // 어느 분석에서 비롯됐는지 모르면 되찾을 구독도 없다. 웹은 조회로 확인해야 한다.
            log.warn("분석 작업을 알 수 없어 면접 준비 완료를 알리지 못했습니다. tailorId={}", tailor.getTailorId());
            return;
        }
        sseNotifier.sendFinal(analysisJobId, SseNotifier.INTERVIEW_READY, toReady(tailor));
    }

    /**
     * 면접을 열지 못했음을 구독에 알린다.
     *
     * <p>어느 단계에서 멈췄는지를 함께 싣는다. 단계가 없으면 웹은 질문부터 다시 만들어야
     * 하는지 전달만 다시 하면 되는지 모른 채 같은 안내를 보여주게 된다.
     */
    private void notifyPreparationFailed(QuestionTailorEntity tailor, FailureStage stage) {
        String analysisJobId = tailor.getAnalysisJobId();
        if (analysisJobId == null) {
            log.warn("분석 작업을 알 수 없어 면접 준비 실패를 알리지 못했습니다. tailorId={}, 단계={}",
                    tailor.getTailorId(), stage);
            return;
        }
        sseNotifier.sendFinal(analysisJobId, SseNotifier.INTERVIEW_PREPARATION_FAILED,
                InterviewReadyResponse.failed(tailor.getInterviewId(), stage, failureMessage(tailor, stage)));
    }

    private String failureMessage(QuestionTailorEntity tailor, FailureStage stage) {
        return stage == FailureStage.CHAT_DELIVERY ? tailor.getChatErrorMessage() : tailor.getErrorMessage();
    }

    /**
     * 되짚어 보낼 이벤트 한 건.
     *
     * @param eventName {@link SseNotifier#INTERVIEW_READY} 또는
     *                  {@link SseNotifier#INTERVIEW_PREPARATION_FAILED}
     */
    public record PreparationEvent(String eventName, InterviewReadyResponse payload) {
    }

    /**
     * 뒤늦게 붙은 구독에 되짚어줄 면접 준비 결과. 아직 준비 중이면 아무것도 돌려주지 않는다.
     *
     * <p>성공만 되짚으면 실패한 준비를 구독한 클라이언트는 아무것도 받지 못한 채 타임아웃까지
     * 매달린다. SSE가 유실되든 클라이언트가 뒤늦게 붙든 같은 최종 상태에 닿아야 한다.
     *
     * <p>넘기는 중인 건은 준비된 것으로 보지 않는다. 채팅 서버 전달은 권리를 먼저 차지하고
     * 시작하므로, 그 사이에 조회하면 아직 열리지도 않은 면접을 열렸다고 알리게 된다.
     */
    @Transactional(readOnly = true)
    public PreparationEvent findPreparationEvent(String analysisJobId) {
        if (analysisJobId == null) {
            return null;
        }
        QuestionTailorEntity tailor = questionTailorRepository
                .findTopByAnalysisJobIdOrderByCreatedAtDesc(analysisJobId)
                .orElse(null);

        PreparationState state = PreparationState.of(tailor);
        if (state.status() == PreparationStatus.READY) {
            return new PreparationEvent(SseNotifier.INTERVIEW_READY, toReady(tailor));
        }
        if (state.status() == PreparationStatus.FAILED) {
            return new PreparationEvent(SseNotifier.INTERVIEW_PREPARATION_FAILED,
                    InterviewReadyResponse.failed(tailor.getInterviewId(), state.failureStage(),
                            failureMessage(tailor, state.failureStage())));
        }
        return null;
    }

    private InterviewReadyResponse toReady(QuestionTailorEntity tailor) {
        String sessionId = interviewRepository.findById(tailor.getInterviewId())
                .map(InterviewEntity::getSessionId)
                .orElse(null);
        return InterviewReadyResponse.ready(tailor.getInterviewId(), sessionId, tailor.getTailored());
    }

    /**
     * 분석 서버가 N:1 질문 구성을 마치고 보내는 콜백.
     *
     * <p>1:1과 달리 폴백이 없다. 신규 질문 4개는 여기서 받은 값이 유일한 원본이라, 실패하면
     * 면접에 쓸 질문이 남지 않는다. 그 경우 채팅 서버로 넘기지 않고 실패로 남긴다 —
     * 반쪽짜리로 넘기면 기술 질문 2개짜리 면접이 N:1인 척 열린다.
     */
    public void handleMultiCallback(QuestionTailorMultiCallbackRequest request) {
        QuestionTailorEntity tailor = findMultiTarget(request);
        if (tailor == null) {
            log.warn("알 수 없는 N:1 질문 구성 콜백을 받았습니다. jobId={}, interviewId={}",
                    request.getJobId(), request.getInterviewId());
            return;
        }

        if (tailor.getJobId() == null) {
            tailor.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applyMultiSuccess(tailor, request.getResult());
        } else {
            QuestionTailorMultiCallbackRequest.Error error = request.getError();
            tailor.setErrorStatusCode(error == null ? null : error.getStatusCode());
            failWithoutFallback(tailor, error == null || error.getMessage() == null
                    ? "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요."
                    : error.getMessage());
        }

        questionTailorRepository.save(tailor);
        completePreparation(tailor);
    }

    private void applyMultiSuccess(QuestionTailorEntity tailor, QuestionTailorMultiCallbackRequest.Result result) {
        List<QuestionTailorMultiCallbackRequest.Question> questions =
                result.getQuestions() == null ? List.of() : result.getQuestions();

        List<TailoredQuestionResponse> prepared = new ArrayList<>();
        for (QuestionTailorMultiCallbackRequest.Question question : questions) {
            if (question.getId() == null || question.getQuestion() == null || question.getQuestion().isBlank()) {
                continue;
            }
            prepared.add(TailoredQuestionResponse.builder()
                    .id(question.getId())
                    .personaId(question.getPersonaId())
                    .category(question.getCategory())
                    .question(question.getQuestion())
                    // 신규 질문의 채점 기준은 여기서 받은 이 값뿐이다. 버리면 되찾을 데가 없다.
                    .expectedAnswer(question.getExpectedAnswer())
                    .basedOn(question.getBasedOn())
                    .build());
        }

        if (prepared.isEmpty()) {
            log.warn("N:1 질문 구성 결과에 쓸 수 있는 질문이 없습니다. tailorId={}", tailor.getTailorId());
            failWithoutFallback(tailor, "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
            return;
        }

        tailor.setStatus(TailorStatus.SUCCEEDED);
        tailor.setTailored(true);
        tailor.setErrorStatusCode(null);
        tailor.setErrorMessage(null);
        // 배열 순서가 그대로 면접 진행 순서다. 다시 정렬하지 않는다.
        tailor.setQuestions(prepared);
    }

    private QuestionTailorEntity findMultiTarget(QuestionTailorMultiCallbackRequest request) {
        if (request.getJobId() != null) {
            QuestionTailorEntity byJobId = questionTailorRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        Long interviewId = parseInterviewId(request.getInterviewId());
        if (interviewId == null) {
            return null;
        }
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    private QuestionTailorEntity findTarget(QuestionTailorCallbackRequest request) {
        if (request.getJobId() != null) {
            QuestionTailorEntity byJobId = questionTailorRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        // 세션이 아직 없는 시점이라 매칭 키는 interviewId다.
        Long interviewId = parseInterviewId(request.getInterviewId());
        if (interviewId == null) {
            return null;
        }
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    private Long parseInterviewId(String interviewId) {
        if (interviewId == null) {
            return null;
        }
        try {
            return Long.valueOf(interviewId);
        } catch (NumberFormatException e) {
            log.warn("콜백의 interviewId 형식이 올바르지 않습니다. interviewId={}", interviewId);
            return null;
        }
    }

    private void applySuccess(QuestionTailorEntity tailor, QuestionTailorCallbackRequest.Result result) {
        tailor.setStatus(TailorStatus.SUCCEEDED);
        tailor.setErrorStatusCode(null);
        tailor.setErrorMessage(null);

        List<TailoredQuestionResponse> source = originalQuestions(tailor);
        if (!Boolean.TRUE.equals(result.getTailored())) {
            // 분석 서버가 이미 원질문으로 폴백한 경우. 본문도 원문 그대로 실려온다.
            tailor.setTailored(false);
            tailor.setQuestions(source);
            return;
        }

        Map<Integer, String> rewritten = new LinkedHashMap<>();
        List<QuestionTailorCallbackRequest.Question> questions =
                result.getQuestions() == null ? List.of() : result.getQuestions();
        for (QuestionTailorCallbackRequest.Question question : questions) {
            if (question.getId() != null && question.getQuestion() != null && !question.getQuestion().isBlank()) {
                rewritten.put(question.getId(), question.getQuestion());
            }
        }

        // 재작성분과 원문이 한 면접에 섞이면 어조가 들쭉날쭉해진다. 하나라도 비면 전체를 원문으로 되돌린다.
        boolean complete = source.stream().allMatch(question -> rewritten.containsKey(question.getId()));
        if (!complete) {
            log.warn("재작성 결과에 누락된 질문이 있어 전체를 원질문으로 되돌립니다. tailorId={}, 원본={}건, 재작성={}건",
                    tailor.getTailorId(), source.size(), rewritten.size());
            tailor.setTailored(false);
            tailor.setQuestions(source);
            return;
        }

        tailor.setTailored(true);
        // 본문만 갈아끼운다. category/expectedAnswer/basedOn은 콜백에 실려오지 않아 원질문 값을 유지한다.
        tailor.setQuestions(source.stream()
                .map(question -> TailoredQuestionResponse.builder()
                        .id(question.getId())
                        .category(question.getCategory())
                        .question(rewritten.get(question.getId()))
                        .expectedAnswer(question.getExpectedAnswer())
                        .basedOn(question.getBasedOn())
                        .build())
                .toList());
    }

    private void applyFailure(QuestionTailorEntity tailor, QuestionTailorCallbackRequest.Error error) {
        tailor.setErrorStatusCode(error == null ? null : error.getStatusCode());
        fallbackToOriginal(tailor, error == null
                ? "질문 재작성에 실패해 원질문으로 진행합니다."
                : error.getMessage());
    }

    /**
     * 폴백 없이 실패로 닫는다. N:1 전용이다.
     *
     * <p>기술 원질문 2개는 남아 있지만 그것만으로 면접을 열면 N:1이 아니다. 면접관 대부분이
     * 질문 없이 앉아 있게 되고, 사용자는 왜 그런지 알 길이 없다. 열지 않는 편이 낫다.
     */
    private void failWithoutFallback(QuestionTailorEntity tailor, String errorMessage) {
        tailor.setStatus(TailorStatus.FAILED);
        tailor.setTailored(false);
        tailor.setQuestions(null);
        tailor.setErrorMessage(errorMessage);
    }

    // 재작성이 안 됐다고 면접을 못 열게 만드는 편이 더 손해다. 실패해도 원질문은 남긴다.
    private void fallbackToOriginal(QuestionTailorEntity tailor, String errorMessage) {
        tailor.setStatus(TailorStatus.FAILED);
        tailor.setTailored(false);
        tailor.setQuestions(originalQuestions(tailor));
        tailor.setErrorMessage(errorMessage);
    }

    private List<TailoredQuestionResponse> originalQuestions(QuestionTailorEntity tailor) {
        return tailor.getSourceQuestions() == null ? List.of() : tailor.getSourceQuestions();
    }

    /**
     * 면접 시작 뒤 클라이언트가 준비 상태를 확인하는 조회.
     * 재작성이 실패했어도 원질문을 돌려주므로 응답만 보고 면접을 열 수 있다.
     */
    public QuestionTailorResponse getTailorResult(String authorization, Long interviewId) {
        UserResponse user = currentUser(authorization);

        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview.getUserId(), user.getId());

        QuestionTailorEntity tailor = questionTailorRepository
                .findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElse(null);
        if (tailor == null) {
            // 미리보기도 면접에 실제로 쓸 만큼만 보여준다. 여기서 더 보여주면 시작 전과 후의 문항 수가 어긋난다.
            return QuestionTailorResponse.notRequested(interviewId,
                    loadOriginalQuestions(interview.getUserId(),
                            sourceQuestionCount(interview.getMode())).questions());
        }

        // 폴링하는 클라이언트가 PENDING에 갇히지 않도록 조회 시점에도 판정하고, 밀린 뒷단을 마저 밟는다.
        expireIfTimedOut(tailor);
        completePreparation(tailor);

        return QuestionTailorResponse.of(tailor);
    }

    private UserResponse currentUser(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user;
    }

    // 면접 질문은 본인만 볼 수 있어야 한다.
    private void verifyOwner(Long ownerId, Long requesterId) {
        if (!requesterId.equals(ownerId)) {
            throw BusinessException.forbidden("본인의 면접 질문만 다룰 수 있습니다.");
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 요약 자리에 실제로 무엇이 왔는지 한 줄로 옮긴다.
     *
     * <p>값이 아니라 키만 남긴다. 이름이 어긋나 비었는지 값 자체가 없는지를 가리는 데는 키로
     * 충분하고, 요약 본문은 사용자가 올린 포트폴리오 내용이라 로그에 흘릴 것이 아니다.
     */
    private String describeShape(Object raw) {
        if (raw == null) {
            return "없음";
        }
        if (raw instanceof Map<?, ?> map) {
            return map.keySet().toString();
        }
        return raw.getClass().getSimpleName();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
