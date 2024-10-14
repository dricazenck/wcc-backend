package com.wcc.platform.repository.surrealdb;

import com.wcc.platform.repository.PageRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** SurrealDB repository implementation for page. */
@Repository
public class SurrealDbPageRepository implements PageRepository {

  /* default */ static final String TABLE = "page";

  private final SurrealDdDriver service;

  @Autowired
  public SurrealDbPageRepository(final SurrealDdDriver service) {
    this.service = service;
  }

  @Override
  public Object save(final Object entity) {
    return service.getDriver().create(TABLE, entity);
  }

  @Override
  public Collection<Object> findAll() {
    return service.getDriver().select(TABLE, Object.class);
  }

  @Override
  public Optional<Object> findById(final String id) {
    final var query =
        service
            .getDriver()
            .query("SELECT * FROM " + TABLE + " WHERE id = $id", Map.of("id", id), Object.class);

    if (query.isEmpty()) {
      return Optional.empty();
    }

    final var result = query.getFirst().getResult();
    if (result.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(result.getFirst());
  }

  @Override
  public void deleteById(final String id) {
    service.getDriver().delete(id);
  }
}
