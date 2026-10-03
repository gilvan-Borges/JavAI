package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FaqInput(
        @Size(max = 300) String questionPt,
        @Size(max = 300) String questionEn,
        @Size(max = 2000) String answerPt,
        @Size(max = 2000) String answerEn,
        Integer sortOrder,
        @NotNull Visibility visibility) implements ContentInput {
}
