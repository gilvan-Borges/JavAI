package br.com.gilvanborges.javai.admin;

import br.com.gilvanborges.javai.admin.migrations.DatabaseSettings;
import br.com.gilvanborges.javai.admin.migrations.TestDatabase;
import br.com.gilvanborges.javai.admin.security.TestJwtDecoderConfig;
import br.com.gilvanborges.javai.admin.security.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** O CRUD inteiro contra um PostgreSQL de verdade, criado pelo mesmo bootstrap e pelas mesmas migrações da produção. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AdminApiIntegrationTest {

    private static final String PRIVATE_MARKER = "MARCADOR-PRIVADO-7f3a";

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.newContainer();

    @TempDir
    static Path snapshots;

    private static DatabaseSettings db;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JsonMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> database().url());
        registry.add("spring.datasource.password", () -> database().app().password());
        registry.add("admin.snapshot.directory", () -> snapshots.toString());
        for (String property : TestTokens.PROPERTIES) {
            int eq = property.indexOf('=');
            registry.add(property.substring(0, eq), () -> property.substring(eq + 1));
        }
    }

    private static synchronized DatabaseSettings database() {
        if (db == null) {
            try {
                db = TestDatabase.prepare(POSTGRES);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return db;
    }

    @BeforeEach
    void limpa() throws SQLException, IOException {
        TestDatabase.truncateAll(database());
        try (var files = Files.list(snapshots)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void publica_so_o_que_e_publicado_e_publico() throws Exception {
        publishedProfile();
        String publica = create("/admin/v1/experiences", experience("Vibetex", "public"));
        publish("/admin/v1/experiences/" + publica);
        String privada = create("/admin/v1/experiences", experience("EmpresaPrivada", "private"));
        publish("/admin/v1/experiences/" + privada);
        create("/admin/v1/experiences", experience("EmpresaRascunho", "public"));

        JsonNode job = runPublication();

        assertThat(job.get("status").asString()).isEqualTo("succeeded");
        assertThat(job.at("/report/counts/experiences").asInt()).isEqualTo(1);
        String pt = Files.readString(snapshots.resolve("pt.json"));
        String en = Files.readString(snapshots.resolve("en.json"));
        assertThat(pt).contains("Vibetex").contains("Desenvolvedor");
        assertThat(en).contains("Vibetex").contains("Developer");
        for (String file : new String[] {pt, en}) {
            assertThat(file).doesNotContain("EmpresaPrivada").doesNotContain("EmpresaRascunho")
                    .doesNotContain(PRIVATE_MARKER);
        }
    }

    @Test
    void snapshot_traz_so_os_repositorios_ligados_e_nunca_os_desligados() throws Exception {
        publishedProfile();
        try (var app = TestDatabase.connect(database(), database().app()); var st = app.createStatement()) {
            st.execute("""
                    INSERT INTO content.github_repo (full_name, enabled, kind, note_pt, note_en, is_fork, is_private, updated_by) VALUES
                      ('FightConnect2025/apiSuporte', true,  'destaque', 'Exemplo público.', 'Public example.', false, false, 'teste@example.com'),
                      ('gilvan-Borges/estudo-qualquer', false, 'estudo', NULL, NULL, false, false, 'teste@example.com'),
                      ('gilvan-Borges/KaioMuniz', false, 'estudo', NULL, NULL, true, false, 'teste@example.com')""");
        }

        assertThat(runPublication().get("status").asString()).isEqualTo("succeeded");

        JsonNode pt = json.readTree(Files.readString(snapshots.resolve("pt.json")));
        JsonNode en = json.readTree(Files.readString(snapshots.resolve("en.json")));
        assertThat(pt.get("repositories")).hasSize(1);
        assertThat(pt.at("/repositories/0/fullName").asString()).isEqualTo("FightConnect2025/apiSuporte");
        assertThat(pt.at("/repositories/0/note").asString()).isEqualTo("Exemplo público.");
        assertThat(en.at("/repositories/0/note").asString()).isEqualTo("Public example.");
        assertThat(Files.readString(snapshots.resolve("pt.json"))).doesNotContain("estudo-qualquer").doesNotContain("KaioMuniz");
    }

    @Test
    void publicar_item_sem_os_dois_idiomas_e_recusado_pelo_banco() throws Exception {
        String id = create("/admin/v1/experiences", """
                {"company":"Vibetex","rolePt":"Desenvolvedor","startedOn":"2025-01-01","descriptionPt":"pt","visibility":"public"}""");

        assertThat(patchStatus("/admin/v1/experiences/" + id, "published"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.constraint").isEqualTo("experience_publicada_bilingue");
    }

    @Test
    void sem_perfil_publicado_a_publicacao_falha_com_motivo() throws Exception {
        JsonNode job = runPublication();

        assertThat(job.get("status").asString()).isEqualTo("failed");
        assertThat(job.at("/report/reason").asString()).contains("perfil");
        assertThat(snapshots.resolve("pt.json")).doesNotExist();
    }

    @Test
    void rollback_reexporta_a_versao_anterior() throws Exception {
        publishedProfile();
        long primeira = runPublication().get("version").asLong();
        String id = create("/admin/v1/experiences", experience("NovaEmpresa", "public"));
        publish("/admin/v1/experiences/" + id);
        runPublication();
        assertThat(Files.readString(snapshots.resolve("pt.json"))).contains("NovaEmpresa");

        assertThat(mvc.post().uri("/admin/v1/versions/" + primeira + "/activate").header(TestTokens.HEADER, TestTokens.owner()))
                .hasStatusOk();

        assertThat(Files.readString(snapshots.resolve("pt.json"))).doesNotContain("NovaEmpresa");
    }

    @Test
    void toda_escrita_fica_na_auditoria_com_o_email_do_dono() throws Exception {
        create("/admin/v1/faqs", """
                {"questionPt":"Aceita remoto?","questionEn":"Open to remote?","answerPt":"Sim.","answerEn":"Yes.","visibility":"public"}""");

        var audit = read(mvc.get().uri("/admin/v1/audit").header(TestTokens.HEADER, TestTokens.owner()).exchange());

        // A auditoria só cresce, então as linhas dos outros testes continuam lá: o teste olha só o que é dele.
        var faqs = new java.util.ArrayList<JsonNode>();
        for (JsonNode entry : audit) {
            assertThat(entry.get("actor").asString()).isEqualTo(TestTokens.OWNER);
            if ("faq".equals(entry.get("entity").asString())) {
                faqs.add(entry);
            }
        }
        assertThat(faqs).singleElement().satisfies(entry -> {
            assertThat(entry.get("action").asString()).isEqualTo("create");
            assertThat(entry.at("/diff/after/data/answerPt").asString()).isEqualTo("Sim.");
        });
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void publishedProfile() {
        assertThat(mvc.put().uri("/admin/v1/profile").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Gilvan Borges","headlinePt":"Engenheiro de Software","headlineEn":"Software Engineer",
                         "summaryPt":"Resumo.","summaryEn":"Summary.","privateNotes":"%s","visibility":"public"}"""
                        .formatted(PRIVATE_MARKER)))
                .hasStatusOk();
        assertThat(patchStatus("/admin/v1/profile", "published")).hasStatusOk();
    }

    private static String experience(String company, String visibility) {
        return """
                {"company":"%s","rolePt":"Desenvolvedor","roleEn":"Developer","startedOn":"2025-01-01",
                 "descriptionPt":"Descrição.","descriptionEn":"Description.","privateNotes":"%s","visibility":"%s"}"""
                .formatted(company, PRIVATE_MARKER, visibility);
    }

    private String create(String path, String body) throws Exception {
        var result = mvc.post().uri(path).header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result).get("id").asString();
    }

    private void publish(String path) {
        assertThat(patchStatus(path, "published")).hasStatusOk();
    }

    private MvcTestResult patchStatus(String path, String status) {
        return mvc.patch().uri(path + "/status").header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}").exchange();
    }

    private JsonNode runPublication() throws Exception {
        var started = mvc.post().uri("/admin/v1/publish").header(TestTokens.HEADER, TestTokens.owner()).exchange();
        assertThat(started).hasStatus(HttpStatus.ACCEPTED);
        String jobId = read(started).get("jobId").asString();
        for (int attempt = 0; attempt < 100; attempt++) {
            var job = read(mvc.get().uri("/admin/v1/jobs/" + jobId).header(TestTokens.HEADER, TestTokens.owner()).exchange());
            if (!"running".equals(job.get("status").asString())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("A publicação não terminou em 10 s.");
    }

    private JsonNode read(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
