package com.wcc.platform.repository.surrealdb;

import com.wcc.platform.domain.cms.pages.PageType;
import com.wcc.platform.domain.exceptions.SurrealDbException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class SurrealDBService {
  private final RestTemplate restTemplate;
  private final String dbUrl;

  @Autowired
  public SurrealDBService(@Value("${surrealdb.url}") String dbUrl, RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
    this.dbUrl = dbUrl;
  }

  public Boolean saveData(UUID id, PageType pageType, String data) {

    ResponseEntity<String> response =
        restTemplate.postForEntity(
            dbUrl,
            String.format(
                "CREATE page SET id = '%s', pageType = '%s', content = '%s'",
                id, pageType.name(), data),
            String.class);

    if (response.getStatusCode() == HttpStatus.OK
        || response.getStatusCode() == HttpStatus.CREATED) {
      return Boolean.TRUE;
    } else {
      String msg = "Error to save in SurrealDB: " + response.getStatusCode();
      log.error(msg);
      throw new SurrealDbException(msg);
    }
  }
}
