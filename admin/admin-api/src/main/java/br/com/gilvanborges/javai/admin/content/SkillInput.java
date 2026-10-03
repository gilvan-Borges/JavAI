package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Uma habilidade declarada. Na Fase 2 o indexador liga cada uma às evidências no código; sem evidência ela
 * aparece como "declarada, sem evidência pública".
 */
public record SkillInput(
        @NotBlank @Size(max = 60) String name,
        @NotNull @Pattern(regexp = "linguagem|framework|banco|mensageria|infra|teste|front|arquitetura|outro")
        String category,
        boolean declared,
        @Size(max = 500) String notesPt,
        @Size(max = 500) String notesEn,
        @NotNull Visibility visibility) implements ContentInput {
}
