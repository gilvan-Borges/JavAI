package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotNull;

public record StatusChange(@NotNull ContentStatus status) {
}
