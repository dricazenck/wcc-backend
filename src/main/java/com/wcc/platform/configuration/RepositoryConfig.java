package com.wcc.platform.configuration;

import com.surrealdb.driver.SyncSurrealDriver;
import com.wcc.platform.repository.MentorshipResourcesPageRepository;
import com.wcc.platform.repository.surrealdb.SurrealDBMentorshipResourcesPageRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Repository Beans for the application */
@Configuration
public class RepositoryConfig {

  /** Create default bean SurrealDBMentorshipResourcesPageRepository. */
  @Bean
  public MentorshipResourcesPageRepository createResourcesRepository(SyncSurrealDriver driver) {
    return new SurrealDBMentorshipResourcesPageRepository(driver);
  }
}
