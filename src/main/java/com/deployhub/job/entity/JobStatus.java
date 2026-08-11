package com.deployhub.job.entity;

/** {@code package_job.status} — 오케스트레이터가 전이시킨다. */
public enum JobStatus {
    PENDING,
    VALIDATING,
    DOWNLOADING,
    UPLOADING,
    DONE,
    FAILED
}
