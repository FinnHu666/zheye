package com.aitome.content;

import java.time.Instant;

public record ContentPostSummary(Long id, ContentPost.Type type, String title, String summary,
                                 String authorName, Instant createdAt, int likes) {}
