-- version_name의 index를 3자리로 고정해('2026.08.24.001') PK 문자열 비교가 곧 배포 순서가 되게
-- 하고, 그 역할만 하던 sort_key 파생 컬럼을 없앤다.
--
-- 기존 이름은 '날짜' 또는 '날짜-N' 두 형태다. 날짜별로 (기본형=첫 릴리즈, 이후 -N 순) 다시 번호를
-- 매긴다 — 기본형을 무조건 001로 보내면 같은 날 '-1'이 있는 5개 날짜에서 PK가 충돌한다.

CREATE TABLE mv_rename_v3 (
    old_name VARCHAR(20) NOT NULL,
    new_name VARCHAR(20) NOT NULL,
    PRIMARY KEY (old_name),
    UNIQUE KEY uk_mv_rename_v3_new (new_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mv_rename_v3 (old_name, new_name)
SELECT version_name,
       CONCAT(
           SUBSTRING(version_name, 1, 10), '.',
           LPAD(
               ROW_NUMBER() OVER (
                   PARTITION BY SUBSTRING(version_name, 1, 10)
                   -- 기본형(구분자 없음)은 0으로 접혀 그날 첫 릴리즈가 된다.
                   ORDER BY CAST(COALESCE(NULLIF(SUBSTRING(version_name, 12), ''), '0') AS UNSIGNED)
               ), 3, '0'))
FROM main_version;

-- 하루 999건을 넘으면 LPAD가 4자리를 내보내 정렬이 다시 뒤집히지만, 적용 대상 데이터는
-- 85건/하루 최대 3건이라(2026-08-24 실측) 런타임 가드를 두지 않는다.

-- version_name은 로컬 작업 디렉터리명이기도 하다 — 정리(24h 유예) 전 tar가 남아 있으면
-- work-dir 아래 옛 이름의 디렉터리도 함께 rename해야 한다. 이 마이그레이션은 DB만 고친다
-- (2026-08-25 수동 처리 완료). 주석에도 달러-중괄호를 쓰지 말 것 — Flyway가 플레이스홀더로 읽어 기동이 죽는다.

-- PK를 바꾸므로 참조 FK를 먼저 떼고, 자식까지 갱신한 뒤 되건다.
ALTER TABLE sub_version  DROP FOREIGN KEY fk_sub_version_main_version;
ALTER TABLE package_job  DROP FOREIGN KEY fk_package_job_main_version;
ALTER TABLE package_item DROP FOREIGN KEY fk_package_item_package_job;

UPDATE main_version m JOIN mv_rename_v3 r ON m.version_name = r.old_name
    SET m.version_name = r.new_name;
UPDATE sub_version s JOIN mv_rename_v3 r ON s.main_version_name = r.old_name
    SET s.main_version_name = r.new_name;
UPDATE package_job j JOIN mv_rename_v3 r ON j.version_name = r.old_name
    SET j.version_name = r.new_name;
UPDATE package_item i JOIN mv_rename_v3 r ON i.version_name = r.old_name
    SET i.version_name = r.new_name;

ALTER TABLE sub_version
    ADD CONSTRAINT fk_sub_version_main_version
    FOREIGN KEY (main_version_name) REFERENCES main_version (version_name);
ALTER TABLE package_job
    ADD CONSTRAINT fk_package_job_main_version
    FOREIGN KEY (version_name) REFERENCES main_version (version_name);
ALTER TABLE package_item
    ADD CONSTRAINT fk_package_item_package_job
    FOREIGN KEY (version_name) REFERENCES package_job (version_name);

DROP TABLE mv_rename_v3;

-- 이름 자체가 정렬키가 되어 파생 컬럼과 그 UNIQUE(별칭 방어)가 모두 불필요해졌다.
ALTER TABLE main_version DROP INDEX uk_main_version_sort_key;
ALTER TABLE main_version DROP COLUMN sort_key;
