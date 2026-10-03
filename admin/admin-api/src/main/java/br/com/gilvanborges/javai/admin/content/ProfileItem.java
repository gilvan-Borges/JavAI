package br.com.gilvanborges.javai.admin.content;

import java.time.OffsetDateTime;

/** O perfil é uma linha só, então não tem id. */
public record ProfileItem(ProfileInput data, ContentStatus status, OffsetDateTime updatedAt, String updatedBy) {
}
