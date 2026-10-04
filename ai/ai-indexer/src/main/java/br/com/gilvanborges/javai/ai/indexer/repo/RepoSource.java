package br.com.gilvanborges.javai.ai.indexer.repo;

import java.time.Instant;
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
     * Um commit do mapa de commits (spec 09): só o hash curto (7 caracteres), o instante e a primeira linha da mensagem.
     * Autor e e-mail nunca entram.
     *
     * @param subject a primeira linha da mensagem (até 100 caracteres) ou {@link #OMITTED} quando alguma mensagem do
     *                repositório parecia segredo
     */
    record CommitRef(String sha, Instant at, String subject) {
        public static final String OMITTED = "(mensagem omitida)";
    }

    /**
     * O que foi lido de um repositório.
     *
     * @param skippedReason preenchido quando o repositório inteiro foi pulado (fork, privado, grande demais...);
     *                      neste caso {@code files} e {@code commits} vêm vazios
     * @param commits       o histórico recente (do mais novo para o mais antigo) para o mapa de commits; só de repositório
     *                      público e que não é fork, porque o resto nem chega a ser clonado
     * @param notes         avisos do relatório: arquivo pulado e o motivo, nunca o conteúdo nem o valor de um segredo
     */
    record RepoRead(String fullName, String commitSha, List<RepoFile> files, String skippedReason, List<String> notes,
                    List<CommitRef> commits) {

        public RepoRead {
            files = List.copyOf(files);
            notes = List.copyOf(notes);
            commits = List.copyOf(commits);
        }

        /** Leitura sem histórico de commits (os testes que não olham o mapa de commits). */
        public RepoRead(String fullName, String commitSha, List<RepoFile> files, String skippedReason, List<String> notes) {
            this(fullName, commitSha, files, skippedReason, notes, List.of());
        }

        public static RepoRead skipped(String fullName, String reason) {
            return new RepoRead(fullName, null, List.of(), reason, List.of(), List.of());
        }

        public boolean wasSkipped() {
            return skippedReason != null;
        }
    }
}
