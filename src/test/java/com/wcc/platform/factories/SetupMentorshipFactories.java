package com.wcc.platform.factories;

import static com.wcc.platform.factories.SetupFactories.OBJECT_MAPPER;
import static com.wcc.platform.factories.SetupFactories.createImageTest;
import static com.wcc.platform.factories.SetupFactories.createPageSectionTest;
import static com.wcc.platform.factories.SetupFactories.createPageTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.wcc.platform.domain.cms.pages.mentorship.FeedbackItem;
import com.wcc.platform.domain.cms.pages.mentorship.FeedbackSection;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipPage;
import com.wcc.platform.domain.cms.pages.mentorship.MentorshipResourcesPage;
import com.wcc.platform.domain.platform.ResourceContent;
import com.wcc.platform.domain.platform.ResourceType;
import com.wcc.platform.utils.FileUtil;
import java.time.Year;
import java.util.List;
import java.util.UUID;

/** Mentorship test factories. */
public class SetupMentorshipFactories {

  /** Test factory. */
  public static MentorshipPage createMentorshipPageTest(final String fileName) {
    try {
      final String content = FileUtil.readFileAsString(fileName);
      return OBJECT_MAPPER.readValue(content, MentorshipPage.class);
    } catch (JsonProcessingException e) {
      return createMentorshipPageTest();
    }
  }

  /** Test factory. */
  public static MentorshipPage createMentorshipPageTest() {
    return new MentorshipPage(
        createPageTest(),
        createPageSectionTest("Mentor"),
        createPageSectionTest("Mentee"),
        createFeedbackSectionTest());
  }

  public static FeedbackItem createFeedbackItemTest(final boolean isMentor) {
    return new FeedbackItem("Person Name", "Nice feedback", isMentor, Year.of(2023));
  }

  public static FeedbackSection createFeedbackSectionTest() {
    return new FeedbackSection(
        "Feedback1", List.of(createFeedbackItemTest(true), createFeedbackItemTest(false)));
  }

  public static MentorshipResourcesPage createMentorshipResourcesTest() {
    return new MentorshipResourcesPage(
        UUID.randomUUID(),
        createPageTest(),
        List.of(createResourceContentTest(ResourceType.DOCUMENT)));
  }

  public static ResourceContent createResourceContentTest(ResourceType resourceType) {
    return new ResourceContent(
        UUID.randomUUID(),
        resourceType,
        List.of(createImageTest(), createImageTest()),
        "Resource " + resourceType,
        "Description " + resourceType,
        "content " + resourceType);
  }
}
