package br.com.gilvanborges.javai.ai.core.chunk;

import java.util.ArrayList;
import java.util.List;

/** Divide um Markdown (README) em seções pelos títulos, ignorando "#" que aparecem dentro de blocos de código. */
public final class MarkdownSections {

    private MarkdownSections() {
    }

    public record Section(String heading, String text) {
    }

    public static List<Section> split(String markdown) {
        var sections = new ArrayList<Section>();
        String heading = "";
        var body = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\\R")) {
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            }
            if (!inFence && line.matches("^#{1,6}\\s+.+")) {
                add(sections, heading, body);
                heading = line.replaceFirst("^#{1,6}\\s+", "").strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        add(sections, heading, body);
        return List.copyOf(sections);
    }

    private static void add(List<Section> sections, String heading, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new Section(heading, text));
        }
    }
}
