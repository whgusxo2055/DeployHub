package com.deployhub.version.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** {@link Component}의 복합키. ERD PK는 (sub_version_id, image_tag)로, 단일 대리키가 없다. */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ComponentId implements Serializable {

    private Long subVersionId;
    private String imageTag;
}
