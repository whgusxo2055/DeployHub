package com.deployhub.job.entity;

/** ERD package_job.status. Phase 3에서 오케스트레이션이 전이시킨다 (구현계획서 0.5절). */
public enum JobStatus {
    PENDING,
    VALIDATING,
    DOWNLOADING,
    UPLOADING,
    DONE,
    FAILED
}
