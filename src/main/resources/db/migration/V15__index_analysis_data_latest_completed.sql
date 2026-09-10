-- 사용자의 가장 최근 완료 분석을 찾는 조회(AnalysisDataRepository.findLatestCompleted)를 위한 인덱스.
-- 면접을 시작할 때 재작성할 원질문을 집어 드는 경로라, 느려지면 사용자가 그대로 기다린다.
--
-- 그 조회는 user_id로 좁히고 coalesce(completed_at, created_at) 내림차순으로 줄을 세운다.
-- 기존 인덱스는 (user_id, created_at DESC)여서 표현식으로 세운 줄에는 쓸 수 없었고,
-- 좁혀낸 행을 따로 정렬해야 했다. 표현식을 그대로 담아 정렬까지 인덱스로 끝낸다.
--
-- result가 비어 있는 행은 이 조회의 대상이 아니다. 그 조건을 인덱스에 걸어 아직 끝나지 않은
-- 분석과 지난 실행에서 걷어낸 행은 아예 담지 않는다. 인덱스가 작아지고, 재분석을 되풀이해
-- PENDING 행이 쌓여도 크기가 따라 늘지 않는다.
CREATE INDEX analysis_data_owner_latest_completed_idx
    ON analysis_data (user_id, (coalesce(completed_at, created_at)) DESC)
    WHERE result IS NOT NULL;
