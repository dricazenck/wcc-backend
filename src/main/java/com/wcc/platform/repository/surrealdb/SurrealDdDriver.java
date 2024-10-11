package com.wcc.platform.repository.surrealdb;

import com.surrealdb.connection.SurrealWebSocketConnection;
import com.surrealdb.driver.SyncSurrealDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** SurrealDB Service. */
@Component
public class SurrealDdDriver {
  final SurrealDbConfig config;

  @Autowired
  public SurrealDdDriver(final SurrealDbConfig config) {
    this.config = config;
  }

  /** get Driver. */
  public SyncSurrealDriver getDriver() {
    final var conn =
        new SurrealWebSocketConnection(config.getHost(), config.getPort(), config.isTls());

    try {
      conn.connect(config.getTimeoutSeconds());
    } catch (Exception e) {
      throw new RuntimeException("Failed to connect to SurrealDB", e);
    }

    final var driver = new SyncSurrealDriver(conn);

    driver.signIn(config.getUsername(), config.getPassword());

    driver.use(config.getNamespace(), config.getDatabase());

    return driver;
  }
}
