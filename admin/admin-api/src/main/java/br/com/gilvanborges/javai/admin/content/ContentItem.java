package br.com.gilvanborges.javai.admin.content;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Um item do CRUD como o admin vê: os dados enviados mais id, status e quem alterou por último. */
public record ContentItem<I extends ContentInput>(
        UUID id, I data, ContentStatus status, OffsetDateTime updatedAt, String updatedBy) {
}
