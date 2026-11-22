package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import com.example.fireview.global.config.ExecutorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Data의 완료·진행·실패 상태와 실제 점수를 목록 캐시에 동기화한다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.data-catalog-sync.enabled", havingValue = "true")
public class DataCatalogSynchronizer {
    private final DataServerClient client;
    private final ProductTagRegistry registry;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public DataCatalogSynchronizer(DataServerClient client, ProductTagRegistry registry,
            @Qualifier(ExecutorConfig.DATA_SERVER_EXECUTOR) Executor executor) {
        this.client = client;
        this.registry = registry;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { executor.execute(this::synchronize); }

    @Scheduled(fixedDelayString = "${app.data-catalog-sync.interval-ms:300000}",
            initialDelayString = "${app.data-catalog-sync.interval-ms:300000}")
    public void scheduled() { synchronize(); }

    /** 한 번 실행에서 읽는 최대 페이지 수(페이지당 100건) */
    static final int MAX_PAGES_PER_RUN = 100;

    /**
     * 지난 실행이 페이지 한도에 걸려 멈춘 자리. 끝까지 읽었거나 중간에 끊기면 null 이라 처음부터 읽는다.
     *
     * <p>예전에는 매번 처음부터 100페이지(1만 건)만 읽어서, Data 상품이 그보다 많으면 뒤쪽 상품은
     * 이 서버에 영영 들어오지 않았다(Data 상품 약 4만 6천 건).
     */
    private volatile String resumeCursor;

    /** 외부 호출은 DB 트랜잭션 밖에서 하고 페이지 저장만 짧게 묶는다. */
    public int synchronize() {
        if (!client.isConfigured() || !running.compareAndSet(false, true)) return 0;
        int saved = 0;
        String cursor = resumeCursor;
        resumeCursor = null;
        Set<String> seen = new HashSet<>();
        try {
            for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
                Optional<DataServerCatalogPage> found = client.findCatalog(cursor);
                if (found.isEmpty() || found.get().items() == null) {
                    cursor = null;
                    break;
                }
                DataServerCatalogPage body = found.get();
                saved += registry.upsertCatalogPage(body.items());
                cursor = body.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    cursor = null;
                    break;
                }
                if (!seen.add(cursor) || body.items().isEmpty()) {
                    log.warn("[DataCatalog] 잘못된 페이지 이동을 중단한다");
                    cursor = null;
                    break;
                }
            }
            // 한도까지 읽고도 다음 페이지가 남았으면 다음 실행이 그 자리부터 이어 읽는다
            resumeCursor = cursor;
            log.info("[DataCatalog] 상품·분석 동기화 {}건{}", saved, cursor == null ? "" : ", 다음 실행에서 이어 읽는다");
            return saved;
        } catch (RuntimeException e) {
            log.warn("[DataCatalog] 동기화 실패, 다음 주기에 다시 시도한다: {}", e.getClass().getSimpleName());
            return saved;
        } finally { running.set(false); }
    }
}
