package com.deployhub.job.entity;

/**
 * {@code package_job.status} — 오케스트레이터가 전이시킨다.
 * {@code DELETED}만 예외로 {@code PackageJob.markDeleted()}가 정리 시점에 찍는다.
 */
public enum JobStatus {
    PENDING,
    VALIDATING,
    DOWNLOADING,
    UPLOADING,
    DONE,
    FAILED,
    DELETED
}
