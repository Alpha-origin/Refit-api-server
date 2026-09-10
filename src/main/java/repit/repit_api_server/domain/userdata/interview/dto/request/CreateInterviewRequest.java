package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 면접 생성 요청.
 *
 * <p>면접이 고르는 것은 면접관뿐이라 그것만 받는다. 예전에는 페르소나 등록용 DTO를 그대로 써서
 * 전공·타입·경력·성별까지 받는 것처럼 보였지만, 실제로는 이름만 쓰고 나머지는 버렸다.
 *
 * <p>{@code personaId}가 있으면 그것을 쓰고, 없으면 {@code personaName}으로 찾는다. 이름은 바뀔 수 있는
 * 값이라 id 쪽이 안전하지만, 웹이 이름으로 보내던 기존 방식도 그대로 받는다.
 *
 * <p>N:1 면접은 면접관이 여럿이라 {@code personaIds}로 보낸다. 이 값이 있으면 위 두 값은 보지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateInterviewRequest {
    private Long personaId;
    private String personaName;
    // N:1 면접관 명단. 기술 면접관 한 명에 다른 직책을 한 명 이상 담는다. 직책은 겹칠 수 없고,
    // 진행 순서에서 기술 면접관을 맨 앞으로 올리는 것 말고는 담아 보낸 순서를 그대로 쓴다.
    private List<Long> personaIds;
}
