package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CertificationInput(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 120) String issuer,
        @NotNull LocalDate issuedOn,
        @Size(max = 300) @Pattern(regexp = "https://.+") String url,
        @NotNull Visibility visibility) implements ContentInput {
}
