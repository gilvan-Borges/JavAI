package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record ExperienceInput(
        @NotBlank @Size(max = 120) String company,
        @Size(max = 120) String rolePt,
        @Size(max = 120) String roleEn,
        @NotNull LocalDate startedOn,
        LocalDate endedOn,
        @Size(max = 4000) String descriptionPt,
        @Size(max = 4000) String descriptionEn,
        @Size(max = 10) List<@NotBlank @Size(max = 300) String> highlightsPt,
        @Size(max = 10) List<@NotBlank @Size(max = 300) String> highlightsEn,
        @Size(max = 30) List<@NotBlank @Size(max = 40) String> stack,
        Integer sortOrder,
        @Size(max = 4000) String privateNotes,
        @NotNull Visibility visibility) implements ContentInput {
}
