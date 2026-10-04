package br.com.gilvanborges.javai.ai.core.brain;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * O mapa do "cérebro" (spec 08): cada ORIGEM de conhecimento (um README, uma seção, um projeto) vira um ponto, e pontos
 * que falam de coisas parecidas ficam perto. Só entra conteúdo que já é público no índice.
 *
 * <p>Com vetores, o mapa é uma PCA (análise de componentes principais) em duas dimensões sobre a média dos vetores dos
 * trechos de cada origem: a distância no mapa é uma aproximação da distância real. Sem vetores (índice só com BM25), os
 * pontos são distribuídos por grupo, de forma determinística, para o mapa continuar existindo.
 */
public final class BrainLayout {

    /** Margem para o ponto não encostar na borda do desenho. */
    private static final double MARGIN = 0.04;
    private static final int POWER_ITERATIONS = 80;

    private BrainLayout() {
    }

    /**
     * @param id         a origem estável do trecho ({@code sourceRef}); é a chave dos contadores
     * @param group      o agrupamento (um repositório, um projeto, "profile"...), para colorir e montar as ramificações
     * @param groupLabel o nome legível do grupo (o nome do repositório, o slug do projeto, o tipo)
     * @param x          posição horizontal, de 0 a 1
     * @param y          posição vertical, de 0 a 1
     */
    public record Node(String id, String title, SourceKind kind, String group, String groupLabel, double x, double y) {
    }

    /** @param embeddings um vetor por trecho, na mesma ordem; {@code null} para o plano B sem vetores */
    public static List<Node> of(List<Chunk> chunks, List<float[]> embeddings) {
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("É preciso um vetor por trecho.");
        }
        // Uma origem pode ter vários trechos (pt e en, ou um texto longo em partes): ela vira um ponto só.
        var byRef = new LinkedHashMap<String, Source>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Source s = byRef.computeIfAbsent(c.sourceRef(), ref -> new Source(c));
            s.add(c, embeddings == null ? null : embeddings.get(i));
        }
        var sources = new ArrayList<>(byRef.values());
        sources.sort(Comparator.comparing(s -> s.ref));
        if (sources.isEmpty()) {
            return List.of();
        }
        double[][] xy;
        if (sources.size() == 1) {
            xy = new double[][]{{0.5, 0.5}};
        } else {
            xy = embeddings == null || sources.get(0).sum == null ? null : pca(sources);
            if (xy == null) {
                xy = sectors(sources);
            }
        }
        var nodes = new ArrayList<Node>();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            nodes.add(new Node(s.ref, s.title, s.kind, groupOf(s.ref, s.kind), groupLabelOf(s.ref, s.kind), xy[i][0], xy[i][1]));
        }
        return List.copyOf(nodes);
    }

    /** O grupo de uma origem: o repositório, o projeto ou, para o resto, o tipo do conteúdo. */
    public static String groupOf(String sourceRef, SourceKind kind) {
        String[] parts = sourceRef.split(":", 3);
        if ((sourceRef.startsWith("repo_readme:") || sourceRef.startsWith("repo_file:")) && parts.length >= 2) {
            return "repo:" + parts[1];
        }
        if (sourceRef.startsWith("project:") && parts.length >= 2) {
            return "project:" + parts[1];
        }
        if (sourceRef.startsWith("github-activity:") || sourceRef.startsWith("github:")) {
            return "github";
        }
        return kind.db();
    }

    public static String groupLabelOf(String sourceRef, SourceKind kind) {
        String group = groupOf(sourceRef, kind);
        if (group.startsWith("repo:")) {
            String repo = group.substring("repo:".length());
            return repo.substring(repo.indexOf('/') + 1);
        }
        if (group.startsWith("project:")) {
            return group.substring("project:".length());
        }
        return group;
    }

    // ------------------------------------------------------------------ PCA

    /** @return as posições de cada origem, na mesma ordem, ou {@code null} se os vetores não variam (nada a projetar) */
    static double[][] pca(List<Source> sources) {
        int n = sources.size();
        int d = sources.get(0).sum.length;
        double[][] x = new double[n][d];
        double[] mean = new double[d];
        for (int i = 0; i < n; i++) {
            Source s = sources.get(i);
            for (int j = 0; j < d; j++) {
                x[i][j] = s.sum[j] / s.count;
                mean[j] += x[i][j] / n;
            }
        }
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                row[j] -= mean[j];
            }
        }
        double[] v1 = principalComponent(x, null);
        double[] v2 = principalComponent(x, v1);
        if (v1 == null || v2 == null) {
            return null;
        }
        double[][] out = new double[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = dot(x[i], v1);
            out[i][1] = dot(x[i], v2);
        }
        fit(out);
        return out;
    }

    /** Iteração de potência sobre XᵀX, partindo de um vetor fixo (o resultado é sempre o mesmo para o mesmo índice). */
    private static double[] principalComponent(double[][] x, double[] orthogonalTo) {
        int d = x[0].length;
        double[] v = new double[d];
        for (int j = 0; j < d; j++) {
            v[j] = Math.sin(j + 1.0) + 1.5;
        }
        if (!unit(v, orthogonalTo)) {
            return null;
        }
        for (int it = 0; it < POWER_ITERATIONS; it++) {
            double[] w = new double[d];
            for (double[] row : x) {
                double p = dot(row, v);
                for (int j = 0; j < d; j++) {
                    w[j] += p * row[j];
                }
            }
            if (!unit(w, orthogonalTo)) {
                return null;
            }
            v = w;
        }
        return v;
    }

    /** Remove a componente na direção de {@code other}, se houver, e normaliza. Falso se não sobra direção. */
    private static boolean unit(double[] v, double[] other) {
        if (other != null) {
            double p = dot(v, other);
            for (int j = 0; j < v.length; j++) {
                v[j] -= p * other[j];
            }
        }
        double norm = Math.sqrt(dot(v, v));
        if (norm < 1e-12) {
            return false;
        }
        for (int j = 0; j < v.length; j++) {
            v[j] /= norm;
        }
        return true;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /**
     * Leva os pontos para dentro de [MARGIN, 1 - MARGIN] com a MESMA escala nos dois eixos e centrados: esticar cada eixo
     * por conta própria faria o ruído do segundo componente parecer tão grande quanto a diferença entre assuntos.
     */
    private static void fit(double[][] points) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] p : points) {
            for (int axis = 0; axis < 2; axis++) {
                min[axis] = Math.min(min[axis], p[axis]);
                max[axis] = Math.max(max[axis], p[axis]);
            }
        }
        double span = Math.max(max[0] - min[0], max[1] - min[1]);
        for (double[] p : points) {
            for (int axis = 0; axis < 2; axis++) {
                double centre = (min[axis] + max[axis]) / 2;
                p[axis] = span < 1e-12 ? 0.5 : 0.5 + (1 - 2 * MARGIN) * (p[axis] - centre) / span;
            }
        }
    }

    // ------------------------------------------------------------------ plano B: setores por grupo

    /** Cada grupo ganha uma fatia do círculo; dentro dela, a posição vem de um hash da origem (estável entre execuções). */
    static double[][] sectors(List<Source> sources) {
        var groups = new TreeMap<String, List<Integer>>();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            groups.computeIfAbsent(groupOf(s.ref, s.kind), g -> new ArrayList<>()).add(i);
        }
        double[][] out = new double[sources.size()][2];
        double width = 2 * Math.PI / groups.size();
        int g = 0;
        for (List<Integer> members : groups.values()) {
            for (int idx : members) {
                int h = stableHash(sources.get(idx).ref);
                double angle = g * width + width * (0.1 + 0.8 * ((h & 0xFFFF) / 65535.0));
                double radius = 0.18 + 0.30 * (((h >>> 16) & 0xFFFF) / 65535.0);
                out[idx][0] = 0.5 + radius * Math.cos(angle);
                out[idx][1] = 0.5 + radius * Math.sin(angle);
            }
            g++;
        }
        return out;
    }

    /** Hash que não depende da JVM (String.hashCode é fixo por especificação, mas o embaralhamento deixa a espalhada melhor). */
    private static int stableHash(String text) {
        int h = text.toLowerCase(Locale.ROOT).hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /** O que se sabe de uma origem: o título, o tipo e a soma dos vetores dos trechos dela. */
    static final class Source {
        final String ref;
        final SourceKind kind;
        String title;
        boolean titleIsPt;
        double[] sum;
        int count;

        Source(Chunk first) {
            this.ref = first.sourceRef();
            this.kind = first.kind();
        }

        void add(Chunk c, float[] vector) {
            // O título vem do trecho em português quando existe, sem o sufixo de parte "(1/2)".
            String clean = c.title().replaceAll("\\s*\\(\\d+/\\d+\\)$", "");
            boolean pt = c.lang().equals("pt");
            if (title == null || (pt && !titleIsPt)) {
                title = clean;
                titleIsPt = pt;
            }
            if (vector != null) {
                if (sum == null) {
                    sum = new double[vector.length];
                }
                for (int j = 0; j < vector.length; j++) {
                    sum[j] += vector[j];
                }
                count++;
            }
        }
    }

    /** Só para os testes: dá a posição de cada origem por id. */
    static Map<String, double[]> positions(List<Node> nodes) {
        var map = new LinkedHashMap<String, double[]>();
        nodes.forEach(n -> map.put(n.id(), new double[]{n.x(), n.y()}));
        return map;
    }
}
