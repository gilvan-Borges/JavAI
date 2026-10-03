package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProjectInput(
        @NotBlank @Size(max = 80) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String summaryPt,
        @Size(max = 500) String summaryEn,
        @Size(max = 4000) String problemPt,
        @Size(max = 4000) String problemEn,
        @Size(max = 4000) String solutionPt,
        @Size(max = 4000) String solutionEn,
        @Size(max = 4000) String resultsPt,
        @Size(max = 4000) String resultsEn,
        @Size(max = 30) List<@NotBlank @Size(max = 40) String> stack,
        @Pattern(regexp = "[A-Za-z0-9-]+/[A-Za-z0-9._-]+") String repoFullName,
        @Size(max = 300) @Pattern(regexp = "https://.+") String url,
        boolean featured,
        Integer sortOrder,
        @Size(max = 4000) String privateNotes,
        @NotNull Visibility visibility) implements ContentInput {
}
