-- 무인증 API라 검증되지 않는 자체 신고 값이었다 — 실행자 식별은 감사 로그가 대신한다.
ALTER TABLE package_job DROP COLUMN created_by;
