-- 배포패키지 자동화 시스템 초기 스키마
-- 배포패키지-자동화-ERD.drawio 원본 5개 테이블을 그대로 반영한다 (컬럼 추가 없음).

CREATE TABLE main_version (
    version_name  VARCHAR(20)  NOT NULL COMMENT '배포일자.index 예: 2026.08.05',
    release_note  TEXT         NULL COMMENT '고객사 전달용 릴리즈 노트',
    sql_script    TEXT         NULL COMMENT '이번 배포의 DB 적용 안내 (자유 텍스트)',
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
    submitted_by       VARCHAR(100) NULL COMMENT '제출자',
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
    image_tag       VARCHAR(200) NOT NULL COMMENT '예: sb-cc-api:v2.0.25.8612',
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
    created_by     VARCHAR(100) NOT NULL COMMENT '실행자',
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
    image_tag      VARCHAR(200) NOT NULL COMMENT '패키징한 이미지 태그',
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

-- 구현계획서 0.4절 성능 보조 인덱스 (스키마 변경 아님)
CREATE INDEX idx_sub_version_main   ON sub_version (main_version_name, sort_order);
CREATE INDEX idx_package_job_status ON package_job (status, finished_at);
CREATE INDEX idx_component_tag      ON component (image_tag);
