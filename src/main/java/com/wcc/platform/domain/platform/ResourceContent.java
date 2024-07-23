package com.wcc.platform.domain.platform;

import com.wcc.platform.domain.cms.attributes.Image;
import java.util.List;
import java.util.UUID;

/**
 * Resource for mentorship, events and etc
 *
 * @param resourceType type of resource
 * @param images list of images for necessary formats
 * @param title resource title
 * @param description resource brief description
 * @param content related content, based on the resource type.
 */
public record ResourceContent(
    UUID id,
    ResourceType resourceType,
    List<Image> images,
    String title,
    String description,
    String content) {}
