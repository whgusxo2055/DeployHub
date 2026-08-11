package com.deployhub.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 컴포넌트(Docker Image 단위). PK가 복합키라 단일 식별자가 없어 API는 {@code image_tag}로 지정한다. */
@Entity
@Table(name = "component")
@IdClass(ComponentId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Component {

    @Id
    @Column(name = "sub_version_id")
    private Long subVersionId;

    @Id
    @Column(name = "image_tag", length = 200)
    private String imageTag;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
