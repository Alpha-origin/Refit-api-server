package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {

    /**
     * 소유자 확인에만 쓰는 값.
     *
     * <p>jobId까지 같이 고르는 것은 행이 없는 것과 소유자가 비어 있는 것을 가리기 위해서다.
     * userId 하나만 고르면 둘 다 빈 결과로 돌아와, 모르는 작업을 소유자 없는 작업으로 잘못 본다.
     */
    interface AnalysisOwner {
        String getJobId();

        Long getUserId();
    }

    /**
     * 소유자만 읽는다. 엔티티를 불러오면 result jsonb까지 따라온다.
     *
     * <p>그 값은 이 테이블에서 가장 무겁다 — 포트폴리오 요약과 면접 질문 전체가 들어 있어,
     * Postgres가 TOAST에서 꺼내 오고 Hibernate가 Map으로 풀어낸다. 소유자를 견주는 데는
     * Long 하나면 되는데 구독과 결과 조회가 매번 그 비용을 냈다.
     */
    @Query("select a.jobId as jobId, a.userId as userId from AnalysisDataEntity a where a.jobId = :jobId")
    Optional<AnalysisOwner> findOwner(@Param("jobId") String jobId);

    /** 분석이 끝난(result가 채워진) 가장 최근 작업. */
    default Optional<AnalysisDataEntity> findLatestCompleted(Long userId) {
        return findLatestCompleted(userId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /**
     * 최근을 가리는 기준은 접수 시각이 아니라 완료 시각이다.
     *
     * <p>같은 jobId로 분석을 다시 요청하면 행을 재사용하는데, createdAt은 처음 접수된 시각에
     * 고정되어 있어 갱신되지 않는다. 접수 시각으로 줄을 세우면 그 사이에 접수된 다른 작업이 더
     * 최근으로 보여, 방금 끝낸 분석 대신 옛 결과를 집어 든다.
     *
     * <p>completedAt이 비어 있는 행은 이 열이 생기기 전에 저장된 결과다. 접수 시각으로 대신해
     * 줄에 세운다 — 그냥 두면 Postgres가 내림차순에서 null을 맨 앞에 놓아, 가장 오래된 결과가
     * 가장 최근으로 올라선다.
     */
    @Query("""
            select a from AnalysisDataEntity a
             where a.userId = :userId
               and a.result is not null
             order by coalesce(a.completedAt, a.createdAt) desc
            """)
    List<AnalysisDataEntity> findLatestCompleted(@Param("userId") Long userId, Pageable pageable);

    // 소유자만 갱신한다. 엔티티를 통째로 저장하면 콜백이 먼저 채워둔 result를 덮어쓸 수 있다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AnalysisDataEntity a set a.userId = :userId where a.jobId = :jobId")
    int updateUserId(@Param("jobId") String jobId, @Param("userId") Long userId);

    /**
     * 같은 jobId로 분석을 다시 요청했을 때 지난 실행에 남은 결과를 걷어낸다.
     *
     * <p>걷어내지 않으면 구독이 붙는 순간 옛 결과가 완료 이벤트로 나간다. 분석 서버는 아직
     * 콜백을 보내지도 않은 시점이라, 클라이언트는 새 분석이 끝난 줄 알고 옛 결과를 집어 든다.
     *
     * <p>이번 요청보다 나중에 끝난 결과는 이번 실행의 콜백이다. 요청 접수보다 콜백이 먼저
     * 도착할 수 있어서, 그 결과까지 지우지 않도록 completedAt으로 조건을 건다.
     * 엔티티를 읽어 되저장하는 대신 조건을 담은 갱신 한 번으로 끝내, 읽고 쓰는 사이에 도착한
     * 콜백을 덮어쓰지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisDataEntity a
               set a.status = :pending,
                   a.result = null,
                   a.errorStatusCode = null,
                   a.errorMessage = null,
                   a.completedAt = null
             where a.jobId = :jobId
               and (a.completedAt is null or a.completedAt < :requestedAt)
            """)
    int clearPreviousRun(@Param("jobId") String jobId,
                         @Param("pending") AnalysisStatus pending,
                         @Param("requestedAt") LocalDateTime requestedAt);
}
