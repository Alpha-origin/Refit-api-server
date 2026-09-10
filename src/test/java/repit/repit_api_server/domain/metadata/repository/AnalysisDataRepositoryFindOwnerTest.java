package repit.repit_api_server.domain.metadata.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소유자 프로젝션이 "행이 없음"과 "소유자가 비어 있음"을 실제로 가르는지 DB에 대고 확인한다.
 *
 * <p>목으로는 확인되지 않는다. {@code findOwner}를 스텁하면 Spring Data가 한 칼럼짜리 투영을
 * 어떻게 읽는지를 테스트가 전제로 깔아버리고, 서비스의 분기만 보게 된다. 정작 가려야 할 것은
 * 그 매핑 동작이다.
 *
 * <p>둘이 섞이면 모르는 작업이 403으로, 소유자가 붙지 않은 분석이 404로 나간다.
 *
 * <p>테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class AnalysisDataRepositoryFindOwnerTest {

    @Autowired
    private AnalysisDataRepository analysisDataRepository;

    @Test
    void 소유자가_있는_행은_소유자를_돌려준다() {
        String jobId = save(7L);

        Optional<AnalysisDataRepository.AnalysisOwner> owner = analysisDataRepository.findOwner(jobId);

        assertThat(owner).isPresent();
        assertThat(owner.get().getJobId()).isEqualTo(jobId);
        assertThat(owner.get().getUserId()).isEqualTo(7L);
    }

    /**
     * 이 테스트가 이 프로젝션의 존재 이유다.
     *
     * <p>소유자가 비어 있는 행은 **있는** 행이다. 빈 Optional로 돌아오면 호출자는 모르는 작업과
     * 구분할 수 없어 404를 내고, 접수가 틀어져 소유자가 붙지 않았다는 사실이 잘못된 jobId로 가려진다.
     */
    @Test
    void 소유자가_비어_있어도_행이_있다는_것은_드러난다() {
        String jobId = save(null);

        Optional<AnalysisDataRepository.AnalysisOwner> owner = analysisDataRepository.findOwner(jobId);

        assertThat(owner).as("행이 있으므로 빈 Optional이 아니어야 한다").isPresent();
        assertThat(owner.get().getJobId()).isEqualTo(jobId);
        assertThat(owner.get().getUserId()).as("소유자만 비어 있다").isNull();
    }

    @Test
    void 없는_행은_빈_결과로_돌아온다() {
        assertThat(analysisDataRepository.findOwner("없는-job-" + UUID.randomUUID())).isEmpty();
    }

    /**
     * 소유자만 읽고 result는 건드리지 않는다는 것이 이 프로젝션의 목적이다.
     * result에 값이 있어도 소유자 조회가 멀쩡히 되는지까지 본다.
     */
    @Test
    void 결과가_담긴_행도_소유자만_읽어온다() {
        AnalysisDataEntity saved = analysisDataRepository.save(AnalysisDataEntity.builder()
                .jobId("job-" + UUID.randomUUID())
                .userId(42L)
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("project_summary", "요약", "interview", java.util.List.of()))
                .build());
        analysisDataRepository.flush();

        Optional<AnalysisDataRepository.AnalysisOwner> owner = analysisDataRepository.findOwner(saved.getJobId());

        assertThat(owner).isPresent();
        assertThat(owner.get().getUserId()).isEqualTo(42L);
    }

    private String save(Long userId) {
        AnalysisDataEntity saved = analysisDataRepository.save(AnalysisDataEntity.builder()
                .jobId("job-" + UUID.randomUUID())
                .userId(userId)
                .status(AnalysisStatus.PENDING)
                .build());
        analysisDataRepository.flush();
        return saved.getJobId();
    }
}
