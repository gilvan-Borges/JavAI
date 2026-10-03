package br.com.gilvanborges.javai.ai.core.repo;

import java.util.Locale;
import java.util.Set;

/**
 * Decide quais arquivos de um repositório a IA pode ler (spec 01, "Indexador"). A regra é de lista permitida:
 * só entra o que está nas extensões e nomes abaixo, e mesmo assim nunca o que pode guardar segredo ou lixo de build.
 * Configuração, variáveis de ambiente, dependências baixadas e binários ficam sempre de fora.
 */
public final class RepoFileFilter {

    /** Tamanho máximo de um arquivo lido. Acima disso é quase sempre gerado ou de dados. */
    public static final int MAX_FILE_BYTES = 100 * 1024;

    private static final Set<String> DENIED_DIRS = Set.of(
            "node_modules", "target", "build", "dist", ".angular", ".mvn", "vendor", ".git", ".gradle", ".idea",
            ".dart_tool", "bin", "obj", "out", "coverage", "__pycache__");

    private static final Set<String> CODE_EXTENSIONS = Set.of(".java", ".kt", ".ts", ".dart", ".cs");

    private static final Set<String> MANIFESTS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "pubspec.yaml", "dockerfile");

    private RepoFileFilter() {
    }

    /** Pastas que nunca são lidas, em qualquer nível. */
    public static java.util.List<String> deniedDirectories() {
        return DENIED_DIRS.stream().sorted().toList();
    }

    /** Extensões de código lidas. */
    public static java.util.List<String> codeExtensions() {
        return CODE_EXTENSIONS.stream().sorted().toList();
    }

    /** Arquivos de manifesto lidos (dependências e build). */
    public static java.util.List<String> manifestNames() {
        return MANIFESTS.stream().sorted().toList();
    }

    public static boolean allowed(String path, long sizeBytes) {
        if (path == null || path.isBlank() || sizeBytes <= 0 || sizeBytes > MAX_FILE_BYTES) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../")) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String segment : lower.split("/")) {
            if (DENIED_DIRS.contains(segment)) {
                return false;
            }
        }
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (isSecretOrConfig(name) || isLockfileOrBinary(name)) {
            return false;
        }
        return name.endsWith(".md")
                || MANIFESTS.contains(name)
                || name.endsWith(".csproj")
                || CODE_EXTENSIONS.stream().anyMatch(name::endsWith)
                || name.startsWith("docker-compose") && (name.endsWith(".yml") || name.endsWith(".yaml"))
                || name.startsWith("compose.") && (name.endsWith(".yml") || name.endsWith(".yaml"))
                || lower.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml"))
                || lower.contains("db/migration/") && name.endsWith(".sql");
    }

    /** Configuração e segredos: é onde mora senha, mesmo quando o repositório é público. */
    private static boolean isSecretOrConfig(String name) {
        return name.startsWith(".env")
                || name.startsWith("application") && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"))
                || name.startsWith("appsettings") && name.endsWith(".json")
                || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx")
                || name.endsWith(".jks") || name.equals("id_rsa") || name.equals("secrets.json") || name.endsWith(".tfvars");
    }

    private static boolean isLockfileOrBinary(String name) {
        return name.endsWith(".lock") || name.equals("package-lock.json") || name.equals("pnpm-lock.yaml")
                || name.endsWith(".min.js") || name.endsWith(".map") || name.endsWith(".jar") || name.endsWith(".class")
                || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".svg") || name.endsWith(".pdf")
                || name.endsWith(".zip") || name.endsWith(".onnx");
    }
}
