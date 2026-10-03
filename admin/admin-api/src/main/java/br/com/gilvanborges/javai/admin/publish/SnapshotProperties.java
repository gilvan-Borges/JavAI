package br.com.gilvanborges.javai.admin.publish;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Pasta onde ficam os arquivos públicos do snapshot. O Caddy monta a mesma pasta só para leitura (spec 01). */
@Validated
@ConfigurationProperties("admin.snapshot")
public record SnapshotProperties(@NotBlank String directory) {
}
