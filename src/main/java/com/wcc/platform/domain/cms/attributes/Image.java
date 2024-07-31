package com.wcc.platform.domain.cms.attributes;


/** Record for Image CMS data. */
public record Image(String path, String alt, ImageType type) {

  /**
   * Make a copy of existent object and create a new UUID for the object.
   *
   * @return new instance of exist object with override UUID
   */
  public Image copy() {
    return new Image(path, alt, type);
  }
}
