package com.deployhub.version.repository;

/** 메인버전별 집계 결과. 목록 조회가 항목마다 카운트 쿼리를 치지 않도록 한 번에 받아온다. */
public record VersionCount(String versionName, long count) {}
