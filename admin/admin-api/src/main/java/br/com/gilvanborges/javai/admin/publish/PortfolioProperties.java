package br.com.gilvanborges.javai.admin.publish;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** A URL do deploy hook é um segredo: quem a tem dispara builds do portfólio. Nunca vai para log. */
@Validated
@ConfigurationProperties("admin.portfolio")
public record PortfolioProperties(@Pattern(regexp = "(https://.+)?") String deployHookUrl) {
}
