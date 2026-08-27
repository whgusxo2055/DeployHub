-- 통합 초기 스키마. 옛 V1(최초 스키마) + V2(DELETED 상태) + V3(version_name 3자리 index)를
-- 합친 결과이고, 기존 DB에 이미 적용된 스키마와 동일하다 — 이 파일은 새 DB를 그 상태로 만든다.
-- 기존 DB는 flyway_schema_history를 지우고 baseline-version 1로 재기준선을 잡아 넘어온다.

CREATE TABLE main_version (
    version_name  VARCHAR(20)  NOT NULL COMMENT '배포일자[-index] 예: 2026.08.05, 2026.08.05-2',
    release_note  MEDIUMTEXT   NULL COMMENT '고객사 전달용 릴리즈 노트',
    sql_script    MEDIUMTEXT   NULL COMMENT '이번 배포의 DB 적용 안내 (자유 텍스트)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (version_name)
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

-- image_tag만 utf8mb4_bin이다 — 기본 대조(ai_ci)면 cc/CC·전각·ZWSP가 자바 검증을 통과하고도
-- 같은 행을 잡는다. 완화 금지.
CREATE TABLE component (
    sub_version_id  BIGINT       NOT NULL COMMENT 'FK sub_version.id',
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
        COMMENT 'PENDING→VALIDATING→DOWNLOADING→UPLOADING→DONE/FAILED, 정리 시 DELETED',
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

CREATE INDEX idx_sub_version_main   ON sub_version (main_version_name, sort_order);
CREATE INDEX idx_package_job_status ON package_job (status, finished_at);
