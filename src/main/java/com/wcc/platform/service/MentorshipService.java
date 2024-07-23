package com.wcc.platform.service;

import static com.wcc.platform.domain.cms.ApiResourcesFile.MENTORSHIP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wcc.platform.domain.cms.pages.PageType;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipPage;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipResourcesPage;
import com.wcc.platform.domain.exceptions.PlatformInternalException;
import com.wcc.platform.repository.MentorshipResourcesPageRepository;
import com.wcc.platform.utils.FileUtil;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Mentorship service. */
@Service
public class MentorshipService {
  private final ObjectMapper objectMapper;
  private final MentorshipResourcesPageRepository repository;

  @Autowired
  public MentorshipService(
      final ObjectMapper objectMapper, final MentorshipResourcesPageRepository repository) {
    this.objectMapper = objectMapper;
    this.repository = repository;
  }

  /**
   * API to retrieve information about mentorship overview.
   *
   * @return Mentorship overview page.
   */
  public MentorshipPage getOverview() {
    try {
      final String data = FileUtil.readFileAsString(MENTORSHIP.getFileName());
      return objectMapper.readValue(data, MentorshipPage.class);
    } catch (JsonProcessingException e) {
      throw new PlatformInternalException(e.getMessage(), e);
    }
  }

  public MentorshipResourcesPage createResourcesPage(MentorshipResourcesPage resource) {
    var data =
        new MentorshipResourcesPage(UUID.randomUUID(), resource.page(), resource.resources());

    try {

      repository.save(
          data.id(), PageType.MENTORSHIP_RESOURCES, objectMapper.writeValueAsString(data));

      return data;

    } catch (JsonProcessingException e) {
      throw new PlatformInternalException(e.getMessage(), e);
    }
  }
}
