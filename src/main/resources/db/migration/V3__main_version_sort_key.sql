-- "직전 메인버전"을 version_name 문자열 정렬로 찾으면 index가 두 자리가 되는 순간 틀린다:
--   '2026.08.05-10' < '2026.08.05-2' (문자 비교) → 직전이 -9가 아니라 -1로 잡히고
--   그 사이 버전들의 컴포넌트가 통째로 "변경됨"으로 되살아나 패키징 대상이 잘못 계산된다.
-- 구분자를 '.'과 '-' 둘 다 허용하는 것('-'=0x2D < '.'=0x2E)도 같은 계열의 뒤집힘을 만든다.
-- version_name은 PK라 형식을 바꾸면 FK 4개가 따라가야 하므로, 정렬 전용 컬럼을 따로 둔다.
--
-- 형식: <날짜 10자> '.' <index 3자리 0 패딩>  예) 2026.08.05 -> '2026.08.05.000'
--                                              2026.08.05-10 -> '2026.08.05.010'
ALTER TABLE main_version
    ADD COLUMN sort_key VARCHAR(20) NULL COMMENT '정렬·직전 버전 판정 전용 (version_name에서 유도)' AFTER version_name;

UPDATE main_version
SET sort_key = CONCAT(
        LEFT(version_name, 10),
        '.',
        LPAD(COALESCE(NULLIF(SUBSTRING(version_name, 12), ''), '0'), 3, '0'))
WHERE sort_key IS NULL;

ALTER TABLE main_version
    MODIFY COLUMN sort_key VARCHAR(20) NOT NULL COMMENT '정렬·직전 버전 판정 전용 (version_name에서 유도)';

-- 목록 정렬과 "직전 버전" 조회가 모두 이 컬럼만 훑는다.
CREATE UNIQUE INDEX uk_main_version_sort_key ON main_version (sort_key);
