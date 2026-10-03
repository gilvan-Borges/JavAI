package br.com.gilvanborges.javai.ai.indexer.repo;

import java.util.List;
import java.util.Optional;

/** Porta: de onde vêm os arquivos de um repositório. A implementação real clona do GitHub; os testes usam uma local. */
public interface RepoSource {

    RepoRead read(String fullName);

    /**
     * O commit mais recente do branch padrão, para saber se o repositório mudou sem clonar. Vazio quando não dá para
     * saber (rede, limite da API, repositório sumiu): quem chama trata como "não mudou" e tenta de novo depois.
     */
    default Optional<String> head(String fullName) {
        return Optional.empty();
    }

    /** Um arquivo aprovado pelo filtro e limpo de segredos. */
    record RepoFile(String path, String content) {
    }

    /**
     * O que foi lido de um repositório.
     *
     * @param skippedReason preenchido quando o repositório inteiro foi pulado (fork, privado, grande demais...);
     *                      neste caso {@code files} vem vazio
     * @param notes         avisos do relatório: arquivo pulado e o motivo, nunca o conteúdo nem o valor de um segredo
     */
    record RepoRead(String fullName, String commitSha, List<RepoFile> files, String skippedReason, List<String> notes) {

        public RepoRead {
            files = List.copyOf(files);
            notes = List.copyOf(notes);
        }

        public static RepoRead skipped(String fullName, String reason) {
            return new RepoRead(fullName, null, List.of(), reason, List.of());
        }

        public boolean wasSkipped() {
            return skippedReason != null;
        }
    }
}
