package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.security.SecurityConfig;
import br.com.gilvanborges.javai.admin.security.TestJwtDecoderConfig;
import br.com.gilvanborges.javai.admin.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A camada web do admin com a segurança real (spec 03, "API do admin"): JWT do Access, CSRF, só JSON, erros sem
 * detalhes internos. O repositório é simulado, então este teste não precisa de banco nem de Docker.
 */
@WebMvcTest(ExperienceController.class)
@Import({SecurityConfig.class, TestJwtDecoderConfig.class, AdminWebTest.NoTransactions.class})
@TestPropertySource(properties = {
        "admin.access.jwks-uri=https://teste.cloudflareaccess.com/cdn-cgi/access/certs",
        "admin.access.issuer=https://teste.cloudflareaccess.com",
        "admin.access.audience=aud-de-teste",
        "admin.access.owner-email=gilvan@example.com",
})
class AdminWebTest {

    private static final String PATH = "/admin/v1/experiences";
    private static final String VALID = """
            {"company":"Vibetex","rolePt":"Desenvolvedor","roleEn":"Developer","startedOn":"2025-01-01","visibility":"public"}""";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    ExperienceRepository repository;

    @MockitoBean
    AuditLog audit;

    @TestConfiguration
    static class NoTransactions {
        @Bean
        TransactionTemplate transactionTemplate() {
            return new TransactionTemplate(mock(PlatformTransactionManager.class));
        }
    }

    // --- autenticação --------------------------------------------------------------------------------------

    @Test
    void sem_token_recebe_401() {
        assertThat(mvc.get().uri(PATH)).hasStatus(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @Test
    void dono_com_token_valido_acessa() {
        when(repository.list()).thenReturn(List.of());

        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())).hasStatusOk();
    }

    @Test
    void outro_email_recebe_401() {
        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.withEmail("intruso@example.com")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_de_outra_aplicacao_do_access_recebe_401() {
        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.withAudience("outra-aplicacao")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_de_outro_emissor_recebe_401() {
        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.withIssuer("https://falso.example.com")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_vencido_recebe_401() {
        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.expired()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_assinado_por_outra_chave_recebe_401() {
        assertThat(mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.signedByAnotherKey()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_no_authorization_nao_vale() {
        assertThat(mvc.get().uri(PATH).header("Authorization", "Bearer " + TestTokens.owner()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // --- CSRF e tipo de conteúdo ---------------------------------------------------------------------------

    @Test
    void escrita_vinda_de_outro_site_recebe_403() {
        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .header("Sec-Fetch-Site", "cross-site").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.type").isEqualTo("/problems/cross-site");
        verifyNoInteractions(repository);
    }

    @Test
    void escrita_vinda_de_outro_subdominio_recebe_403() {
        assertThat(mvc.delete().uri(PATH + "/" + UUID.randomUUID()).header(TestTokens.HEADER, TestTokens.owner())
                .header("Sec-Fetch-Site", "same-site"))
                .hasStatus(HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository);
    }

    @Test
    void escrita_do_proprio_admin_passa() {
        when(repository.create(any(), eq(TestTokens.OWNER))).thenReturn(item());

        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void corpo_que_nao_e_json_recebe_415() {
        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.TEXT_PLAIN).content("company=Vibetex"))
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(repository);
    }

    // --- validação e erros ---------------------------------------------------------------------------------

    @Test
    void campo_desconhecido_recebe_400() {
        String withExtraField = VALID.replace("}", ",\"model\":\"o-mais-caro\"}");

        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(withExtraField))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo("/problems/validation");
        verifyNoInteractions(repository);
    }

    @Test
    void validacao_diz_qual_campo_falhou() {
        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startedOn\":\"2025-01-01\",\"visibility\":\"public\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("company");
    }

    @Test
    void criar_registra_o_dono_e_a_auditoria() {
        when(repository.create(any(), eq(TestTokens.OWNER))).thenReturn(item());

        assertThat(mvc.post().uri(PATH).header(TestTokens.HEADER, TestTokens.owner())
                .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.status").isEqualTo("draft");
        verify(repository).create(any(ExperienceInput.class), eq(TestTokens.OWNER));
        verify(audit).record(eq(TestTokens.OWNER), eq(AuditAction.CREATE), eq("experience"), anyString(), isNull(), any());
    }

    @Test
    void erro_interno_nao_vaza_detalhes() {
        when(repository.list()).thenThrow(new IllegalStateException("SELECT private_notes FROM content.experience"));

        var result = mvc.get().uri(PATH).header(TestTokens.HEADER, TestTokens.owner()).exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyText().doesNotContain("private_notes").doesNotContain("SELECT");
    }

    private static ContentItem<ExperienceInput> item() {
        var data = new ExperienceInput("Vibetex", "Desenvolvedor", "Developer", java.time.LocalDate.of(2025, 1, 1), null,
                null, null, List.of(), List.of(), List.of(), 0, null, Visibility.PUBLIC);
        return new ContentItem<>(UUID.randomUUID(), data, ContentStatus.DRAFT, OffsetDateTime.now(), TestTokens.OWNER);
    }
}
