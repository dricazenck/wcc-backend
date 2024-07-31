package com.wcc.platform.domain.cms.pages.mentorship;

import com.wcc.platform.domain.cms.pages.Page;
import com.wcc.platform.domain.platform.ResourceContent;
import java.util.List;
import java.util.UUID;

/** Mentorship Resources Page. */
public record MentorshipResourcesPage(UUID id, Page page, List<ResourceContent> resources) {}
