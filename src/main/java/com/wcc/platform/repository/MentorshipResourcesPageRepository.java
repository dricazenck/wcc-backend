package com.wcc.platform.repository;

import com.wcc.platform.domain.cms.pages.PageType;
import java.util.UUID;

public interface MentorshipResourcesPageRepository {
  Boolean save(UUID uuid, PageType pageType, String content);
}
