package com.wcc.platform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wcc.platform.repository.MemberRepository;
import com.wcc.platform.repository.PageRepository;
import com.wcc.platform.repository.ResourceContentRepository;
import com.wcc.platform.repository.file.FileMemberRepository;
import com.wcc.platform.repository.surrealdb.SurrealDbPageRepository;
import com.wcc.platform.repository.surrealdb.SurrealDbResourceRepository;
import com.wcc.platform.repository.surrealdb.SurrealDdDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Repository Configurations to initialize respective implementation. */
@Configuration
public class RepositoryConfig {

  /** Default repository of resources. */
  @Bean
  public ResourceContentRepository getResourceRepository(final SurrealDdDriver service) {
    return new SurrealDbResourceRepository(service);
  }

  @Bean
  public PageRepository getPageRepository(final SurrealDdDriver service) {
    return new SurrealDbPageRepository(service);
  }

  /** Create FileMemberRepository bean. */
  @Bean
  public MemberRepository createFileRepository(
      final ObjectMapper objectMapper,
      @Value("${file.storage.directory}") final String directoryPath) {
    return new FileMemberRepository(objectMapper, directoryPath);
  }
}
