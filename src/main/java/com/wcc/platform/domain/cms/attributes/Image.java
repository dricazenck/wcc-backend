package com.wcc.platform.domain.cms.attributes;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Record for Image CMS data. */
@Table
public record Image(@Id UUID id, String path, String alt, ImageType type) {

  /**
   * Make a copy of existent object and create a new UUID for the object.
   *
   * @return new instance of exist object with override UUID
   */
  public Image copy() {
    return new Image(UUID.randomUUID(), path, alt, type);
  }
}
