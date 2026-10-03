package br.com.gilvanborges.javai.admin.content;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileInput(
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 200) String headlinePt,
        @Size(max = 200) String headlineEn,
        @Size(max = 4000) String summaryPt,
        @Size(max = 4000) String summaryEn,
        @Size(max = 120) String location,
        @Size(max = 10) List<@Valid @NotNull Link> links,
        @Size(max = 300) String availabilityPt,
        @Size(max = 300) String availabilityEn,
        @Size(max = 4000) String privateNotes,
        @NotNull Visibility visibility) implements ContentInput {

    public record Link(
            @NotBlank @Size(max = 40) String label,
            @NotBlank @Size(max = 300) @Pattern(regexp = "https://.+") String url) {
    }
}
