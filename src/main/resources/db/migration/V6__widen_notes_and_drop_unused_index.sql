-- TEXT는 65,535 '바이트'인데 @Size(max = 20000)은 '문자'다 — 4바이트 문자가 섞이면 한도를 넘고,
-- 그 실패가 DataIntegrityViolationException으로 올라와 E-0102(중복 메인버전)로 오분류된다.
ALTER TABLE main_version MODIFY COLUMN release_note MEDIUMTEXT NULL COMMENT '고객사 전달용 릴리즈 노트';
ALTER TABLE main_version MODIFY COLUMN sql_script MEDIUMTEXT NULL COMMENT '이번 배포의 DB 적용 안내 (자유 텍스트)';

-- image_tag 단독으로 필터링하는 쿼리가 없다(컴포넌트 조회는 sub_version_id 또는 메인버전 서브쿼리).
-- 쓰기 증폭만 남아 있어 제거한다.
DROP INDEX idx_component_tag ON component;
