-- 배포패키지 자동화 시스템 초기 스키마.

CREATE TABLE main_version (
    version_name  VARCHAR(20)  NOT NULL COMMENT '배포일자[-index] 예: 2026.08.05, 2026.08.05-2',
    -- version_name을 문자열로 비교하면 index가 두 자리가 되는 순간 뒤집힌다
    -- ('2026.08.05-10' < '2026.08.05-2'). PK라 형식을 못 바꾸므로 정렬 전용 컬럼을 따로 둔다.
    sort_key      VARCHAR(20)  NOT NULL COMMENT '정렬·직전 버전 판정 전용 (version_name에서 유도)',
    -- TEXT는 65,535 '바이트'인데 @Size(max = 20000)은 '문자'다 — 4바이트 문자가 섞이면
    -- 한도를 넘고, 그 실패가 DataIntegrityViolationException으로 올라와 E-0102로 오분류된다.
    release_note  MEDIUMTEXT   NULL COMMENT '고객사 전달용 릴리즈 노트',
    sql_script    MEDIUMTEXT   NULL COMMENT '이번 배포의 DB 적용 안내 (자유 텍스트)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (version_name),
    UNIQUE KEY uk_main_version_sort_key (sort_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '메인버전 (배포 단위)';

CREATE TABLE sub_version (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '대리키',
    main_version_name  VARCHAR(20)  NOT NULL COMMENT 'FK main_version.version_name',
    code               VARCHAR(50)  NOT NULL COMMENT '모듈 코드',
    version            VARCHAR(50)  NOT NULL COMMENT '모듈 릴리즈 버전 (예: v2.0.25)',
    note               TEXT         NULL COMMENT '이 모듈의 변경 사항',
    submit_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / UPDATED / UNCHANGED',
    submitted_at       DATETIME     NULL COMMENT '제출 시각',
    sort_order         INT          NOT NULL DEFAULT 0 COMMENT '문서 표기 순서',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_version_main_code (main_version_name, code),
    CONSTRAINT fk_sub_version_main_version
        FOREIGN KEY (main_version_name) REFERENCES main_version (version_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '서브버전 (모듈 릴리즈 · 제출 단위)';

CREATE TABLE component (
    sub_version_id  BIGINT       NOT NULL COMMENT 'FK sub_version.id',
    -- 테이블 기본 대조(ai_ci)면 DB가 'x:V1'과 'x:v1'을 같게 봐 PK가 충돌한다. @IdClass라
    -- insert가 커밋 시점에 flush돼 catch(DataIntegrityViolationException) 밖에서 터진다.
    image_tag       VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '예: sb-cc-api:v2.0.25.8612',
    sort_order      INT          NOT NULL DEFAULT 0 COMMENT '문서 표기 순서',
    PRIMARY KEY (sub_version_id, image_tag),
    CONSTRAINT fk_component_sub_version
        FOREIGN KEY (sub_version_id) REFERENCES sub_version (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '컴포넌트 (Docker Image 단위)';

CREATE TABLE package_job (
    version_name  VARCHAR(20)  NOT NULL COMMENT 'FK main_version.version_name',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING→VALIDATING→DOWNLOADING→UPLOADING→DONE/FAILED',
    sp_folder_id   VARCHAR(200) NULL COMMENT 'SharePoint 폴더 ID',
    sp_folder_url  VARCHAR(500) NULL COMMENT 'SharePoint 폴더 URL (조직 범위 공유 링크)',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '요청 시각',
    finished_at    DATETIME     NULL COMMENT '종료 시각',
    deleted_at     DATETIME     NULL COMMENT '보존 정책 정리 시각',
    PRIMARY KEY (version_name),
    CONSTRAINT fk_package_job_main_version
        FOREIGN KEY (version_name) REFERENCES main_version (version_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '패키지 Job (패키징 실행 단위 · 메인버전당 1건)';

CREATE TABLE package_item (
    version_name   VARCHAR(20)  NOT NULL COMMENT 'FK package_job.version_name',
    -- 확정 시점에 component에서 복사되므로 같은 대조여야 한다.
    image_tag      VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '패키징한 이미지 태그',
    file_size      BIGINT       NULL COMMENT '업로드 크기 대조용',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING→DOWNLOADED→UPLOADED / FAILED',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '재시도 횟수',
    error_message  TEXT         NULL COMMENT '실패 사유',
    file_url       VARCHAR(500) NULL COMMENT '업로드 파일 URL',
    PRIMARY KEY (version_name, image_tag),
    CONSTRAINT fk_package_item_package_job
        FOREIGN KEY (version_name) REFERENCES package_job (version_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '패키지 Job 내 산출물별 처리 상태';

-- 구현계획서 0.4절 성능 보조 인덱스. image_tag 단독 필터는 쿼리가 없어 인덱스를 두지 않는다.
CREATE INDEX idx_sub_version_main   ON sub_version (main_version_name, sort_order);
CREATE INDEX idx_package_job_status ON package_job (status, finished_at);
