package br.com.gilvanborges.javai.ai.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quando o indexador roda. A cada poucos minutos ele confere (de graça, com GET condicional) se o snapshot do CRUD
 * mudou e se algum repositório ligado recebeu commit novo; se sim, reindexa. Todo dia às 03:00 ele reindexa tudo
 * de qualquer jeito, como rede de segurança. O CRUD não avisa a IA de nada: ela mesma olha (spec 01).
 */
@Component
class IndexerScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(IndexerScheduler.class);

    private final IndexerService service;

    IndexerScheduler(IndexerService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${indexer.poll-interval:PT5M}")
    void conferirSnapshot() {
        run(IndexerService.Trigger.SNAPSHOT);
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Sao_Paulo")
    void reindexarTudo() {
        run(IndexerService.Trigger.AGENDADO);
    }

    private void run(IndexerService.Trigger trigger) {
        try {
            service.run(trigger);
        } catch (RuntimeException e) {
            // Uma falha aqui não pode matar o agendador: a próxima execução tenta de novo.
            LOG.error("Falha inesperada na execução agendada ({}).", trigger, e);
        }
    }
}
