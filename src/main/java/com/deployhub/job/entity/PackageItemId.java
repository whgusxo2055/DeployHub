package com.deployhub.job.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** {@link PackageItem}의 복합키. ERD PK는 (version_name, image_tag)다. */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class PackageItemId implements Serializable {

    private String versionName;
    private String imageTag;
}
