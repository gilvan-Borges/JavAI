package br.com.gilvanborges.javai.admin.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuração do Cloudflare Access (spec 03, "Autenticação"). Sem qualquer um destes valores o app não sobe:
 * melhor falhar no deploy do que aceitar requisições sem saber de quem são.
 */
@Validated
@ConfigurationProperties("admin.access")
public record AccessProperties(
        @NotBlank @Pattern(regexp = "https://.+") String jwksUri,
        @NotBlank @Pattern(regexp = "https://.+") String issuer,
        @NotBlank String audience,
        @NotBlank @Email String ownerEmail) {
}
