package com.wcc.platform.repository.surrealdb;

import com.wcc.platform.domain.cms.pages.PageType;
import com.wcc.platform.repository.MentorshipResourcesPageRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

public class SurrealDBMentorshipResourcesPageRepository
    implements MentorshipResourcesPageRepository {

  private final SurrealDBService service;

  @Autowired
  public SurrealDBMentorshipResourcesPageRepository(final SurrealDBService service) {
    this.service = service;
  }

  @Override
  public Boolean save(UUID uuid, PageType pageType, String content) {
    return service.saveData(uuid, pageType, content);
  }
}
