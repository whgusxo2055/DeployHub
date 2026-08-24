-- 정리된 Job을 status로도 구분한다. 기존 행은 deleted_at이 유일한 판별 근거라 그걸로 채운다 —
-- 안 채우면 DONE/FAILED로 남아 매니페스트 잠금이 안 풀리고 status 필터에서도 빠진다.
UPDATE package_job SET status = 'DELETED' WHERE deleted_at IS NOT NULL;

ALTER TABLE package_job MODIFY status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING→VALIDATING→DOWNLOADING→UPLOADING→DONE/FAILED, 정리 시 DELETED';
