package com.wcc.platform.repository.surrealdb;

import com.surrealdb.driver.SyncSurrealDriver;
import com.surrealdb.driver.model.QueryResult;
import com.wcc.platform.domain.cms.pages.PageType;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipResourcesPage;
import com.wcc.platform.repository.MentorshipResourcesPageRepository;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

public class SurrealDBMentorshipResourcesPageRepository
    implements MentorshipResourcesPageRepository {

  private static final String TABLE = "resources";
  private final SyncSurrealDriver driver;

  @Autowired
  public SurrealDBMentorshipResourcesPageRepository(final SyncSurrealDriver driver) {
    this.driver = driver;
  }

  @Override
  public MentorshipResourcesPage save(MentorshipResourcesPage resourcesPage) {
    return driver.create(TABLE, resourcesPage);
  }

  @Override
  public MentorshipResourcesPage getResources(PageType pageType) {
    // TODO FOR RESOURCES
    List<QueryResult<MentorshipResourcesPage>> query =
        driver.query(
            "SELECT id, string::uppercase(value) as value FROM todo WHERE id=$id LIMIT BY 1;",
            Map.of("id", "todo:" + pageType.name()),
            MentorshipResourcesPage.class);
    if (query.size() == 0) return null;
    if (query.get(0).getResult().size() == 0) return null;
    return query.get(0).getResult().get(0);
  }

  @Override
  public List<MentorshipResourcesPage> getAll() {
    return driver.select(TABLE, MentorshipResourcesPage.class);
  }
}
