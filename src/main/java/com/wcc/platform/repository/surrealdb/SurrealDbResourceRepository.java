package com.wcc.platform.repository.surrealdb;

import com.wcc.platform.domain.platform.ResourceContent;
import com.wcc.platform.repository.ResourceContentRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** SurrealDB repository implementation for resources. */
@Repository
public class SurrealDbResourceRepository implements ResourceContentRepository {

  /* default */ static final String TABLE = "resource_content";
  private final SurrealDdDriver service;

  @Autowired
  public SurrealDbResourceRepository(SurrealDdDriver service) {
    this.service = service;
  }

  @Override
  public ResourceContent save(final ResourceContent entity) {
    return service.getDriver().create(TABLE, entity);
  }

  @Override
  public Collection<ResourceContent> findAll() {
    return service.getDriver().select(TABLE, ResourceContent.class);
  }

  @Override
  public Optional<ResourceContent> findById(final String id) {
    final var query =
        service
            .getDriver()
            .query(
                "SELECT * FROM " + TABLE + " WHERE id = $id",
                Map.of("id", id),
                ResourceContent.class);

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
