package br.com.gilvanborges.javai.core.publish;

import br.com.gilvanborges.javai.core.Fixtures;
import br.com.gilvanborges.javai.core.content.PublishedContent;
import br.com.gilvanborges.javai.core.snapshot.ContentSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishContentTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T06:00:00Z"), ZoneOffset.UTC);
    private final FakeStore store = new FakeStore();
    private final FakeNotifier notifier = new FakeNotifier(true);

    @Test
    void grava_um_snapshot_por_idioma_ativa_e_avisa_o_portfolio() {
        var outcome = publisher(Fixtures.content()).publish();

        assertThat(store.snapshots).extracting(ContentSnapshot::lang).containsExactly("pt", "en");
        assertThat(store.activated).containsExactly(1L);
        assertThat(store.failed).isEmpty();
        assertThat(notifier.calls).containsExactly(1L);
        assertThat(outcome.indexVersion()).isEqualTo(1);
        assertThat(outcome.counts()).containsEntry("projects", 1).containsEntry("faq", 1);
        assertThat(outcome.portfolioNotified()).isTrue();
    }

    @Test
    void sem_perfil_publicado_recusa_e_marca_a_versao_como_falha() {
        var publisher = publisher(Fixtures.withoutProfile());

        assertThatThrownBy(publisher::publish).isInstanceOf(PublishRejectedException.class);
        assertThat(store.activated).isEmpty();
        assertThat(store.failed).containsExactly(1L);
        assertThat(notifier.calls).isEmpty();
    }

    @Test
    void falha_na_ativacao_marca_a_versao_como_falha_e_nao_avisa() {
        store.failOnActivate = true;

        assertThatThrownBy(publisher(Fixtures.content())::publish).isInstanceOf(IllegalStateException.class);
        assertThat(store.failed).containsExactly(1L);
        assertThat(notifier.calls).isEmpty();
    }

    @Test
    void falha_no_aviso_nao_desfaz_a_ativacao() {
        var publisher = new PublishContent(() -> Fixtures.content(), store, new FakeNotifier(false), clock);

        var outcome = publisher.publish();

        assertThat(store.activated).containsExactly(1L);
        assertThat(store.failed).isEmpty();
        assertThat(outcome.portfolioNotified()).isFalse();
    }

    private PublishContent publisher(PublishedContent content) {
        return new PublishContent(() -> content, store, notifier, clock);
    }

    private static final class FakeStore implements IndexVersionStore {
        private long nextId = 1;
        private boolean failOnActivate;
        private final List<ContentSnapshot> snapshots = new ArrayList<>();
        private final List<Long> activated = new ArrayList<>();
        private final List<Long> failed = new ArrayList<>();

        @Override
        public long createVersion() {
            return nextId++;
        }

        @Override
        public void saveSnapshot(ContentSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public void activate(long indexVersion) {
            if (failOnActivate) {
                throw new IllegalStateException("banco fora do ar");
            }
            activated.add(indexVersion);
        }

        @Override
        public void markFailed(long indexVersion) {
            failed.add(indexVersion);
        }
    }

    private static final class FakeNotifier implements PortfolioNotifier {
        private final boolean result;
        private final List<Long> calls = new ArrayList<>();

        private FakeNotifier(boolean result) {
            this.result = result;
        }

        @Override
        public boolean contentPublished(long indexVersion) {
            calls.add(indexVersion);
            return result;
        }
    }
}
