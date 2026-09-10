package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 소유자 확인은 결과를 읽지 않고 소유자만 읽는다. 그 프로젝션이 "없는 작업"과
 * "소유자가 붙지 않은 작업"을 같은 빈 결과로 뭉개지 않는지 확인한다.
 *
 * <p>둘을 섞으면 모르는 jobId가 403으로, 소유자 없는 분석이 404로 나간다. 앞은 남의 작업이
 * 있는지 없는지를 알려주고, 뒤는 접수가 틀어진 것을 잘못된 jobId로 보이게 해 원인을 가린다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataServiceVerifyOwnerTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository);
    }

    @Test
    void 본인_작업은_통과시킨다() {
        when(analysisDataRepository.findOwner("job-1")).thenReturn(Optional.of(owner("job-1", 7L)));

        assertThatCode(() -> service.verifyOwner("job-1", 7L)).doesNotThrowAnyException();
    }

    @Test
    void 남의_작업은_막는다() {
        when(analysisDataRepository.findOwner("job-2")).thenReturn(Optional.of(owner("job-2", 7L)));

        assertThatThrownBy(() -> service.verifyOwner("job-2", 8L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // 접수 응답을 받지 못해 사용자를 붙이지 못한 분석이다. 누구 것인지 모르니 아무에게도 내주지 않는다.
    @Test
    void 소유자가_붙지_않은_작업은_아무에게도_내주지_않는다() {
        when(analysisDataRepository.findOwner("job-3")).thenReturn(Optional.of(owner("job-3", null)));

        assertThatThrownBy(() -> service.verifyOwner("job-3", 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // 소유자만 고르는 프로젝션이라 여기서 404와 403이 갈리는지가 중요하다.
    @Test
    void 모르는_작업은_찾을_수_없다고_알린다() {
        when(analysisDataRepository.findOwner("없는-job")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOwner("없는-job", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("없는-job")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static AnalysisDataRepository.AnalysisOwner owner(String jobId, Long userId) {
        return new AnalysisDataRepository.AnalysisOwner() {
            @Override
            public String getJobId() {
                return jobId;
            }

            @Override
            public Long getUserId() {
                return userId;
            }
        };
    }
}
