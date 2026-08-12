-- 제출자 명시 기능 제거. 제출 시각(submitted_at)은 남긴다 — 제출 여부·시점은 계속 쓴다.
ALTER TABLE sub_version DROP COLUMN submitted_by;
