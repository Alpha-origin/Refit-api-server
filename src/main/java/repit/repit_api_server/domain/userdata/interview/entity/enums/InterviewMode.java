package repit.repit_api_server.domain.userdata.interview.entity.enums;

/**
 * 면접 방식.
 *
 * <p>{@link #SOLO}는 면접관 한 명이 처음부터 끝까지 진행한다.
 * {@link #MULTI}는 한 세션 안에서 여러 면접관이 차례로 교대한다. 맨 앞은 기술 면접관이고 뒤에
 * 다른 직책이 한 명 이상 붙는다 — 몇 명이 붙는지는 사용자가 면접을 만들 때 고른다.
 * 세션을 나누지 않는 이유는 면접관이 바뀐 뒤 진술이 달라지는지를 한 번에 판단하기 위해서다.
 */
public enum InterviewMode {
    SOLO,
    MULTI
}
