package com.wcc.platform.configuration;

import com.wcc.platform.repository.surrealdb.SurrealDbConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Database configuration to initialize Driver and connection. */
@Configuration
public class DatabaseConfig {
  
  /** SurrealDB Config. */
  @Bean
  @ConfigurationProperties(prefix = "surrealdb")
  public SurrealDbConfig getDbConfig() {
    return new SurrealDbConfig();
  }
}
