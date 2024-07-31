package com.wcc.platform.domain.cms.pages;

import com.wcc.platform.domain.cms.attributes.Image;
import java.util.List;

/** CMS Page attributes. */
public record Page(String title, String subtitle, String description, List<Image> images) {}
