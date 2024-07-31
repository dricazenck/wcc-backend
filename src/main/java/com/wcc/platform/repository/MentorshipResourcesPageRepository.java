package com.wcc.platform.repository;

import com.wcc.platform.domain.cms.pages.PageType;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipResourcesPage;
import java.util.List;

public interface MentorshipResourcesPageRepository {
  MentorshipResourcesPage save(MentorshipResourcesPage resourcesPage);

  MentorshipResourcesPage getResources(PageType pageType);

  List<MentorshipResourcesPage> getAll();
}
