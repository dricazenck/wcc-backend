package com.wcc.platform.configuration;

import com.surrealdb.connection.SurrealConnection;
import com.surrealdb.connection.SurrealWebSocketConnection;
import com.surrealdb.driver.SyncSurrealDriver;
import org.springframework.context.annotation.Bean;

public class SurrealDBConfig {

  @Bean
  public SyncSurrealDriver surrealDBClient() {
    SurrealConnection conn = new SurrealWebSocketConnection("localhost", 8000, false);

    conn.connect(5);

    var driver = new SyncSurrealDriver(conn);
    driver.use("wcc", "platform");
    return driver;
  }
}
