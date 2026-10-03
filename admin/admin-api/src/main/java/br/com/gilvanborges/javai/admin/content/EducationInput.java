package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EducationInput(
        @NotBlank @Size(max = 160) String institution,
        @Size(max = 160) String coursePt,
        @Size(max = 160) String courseEn,
        @NotNull LocalDate startedOn,
        LocalDate endedOn,
        @NotNull @Pattern(regexp = "cursando|concluido|trancado") String completion,
        @NotNull Visibility visibility) implements ContentInput {
}
