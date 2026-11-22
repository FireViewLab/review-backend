package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerCatalogPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DataCatalogSynchronizerTest {
    private final DataServerClient client = mock(DataServerClient.class);
    private final ProductTagRegistry registry = mock(ProductTagRegistry.class);
    private final DataCatalogSynchronizer sync = new DataCatalogSynchronizer(client, registry, Runnable::run);

    @Test
    void 모든_페이지를_읽고_다음_실행은_처음부터_다시_읽는다() {
        when(client.isConfigured()).thenReturn(true);
        var first = List.of(mock(DataServerCatalogPage.Entry.class));
        var second = List.of(mock(DataServerCatalogPage.Entry.class));
        when(client.findCatalog(null)).thenReturn(Optional.of(new DataServerCatalogPage(first, "next")));
        when(client.findCatalog("next")).thenReturn(Optional.of(new DataServerCatalogPage(second, null)));
        when(registry.upsertCatalogPage(anyList())).thenReturn(1);
        assertThat(sync.synchronize()).isEqualTo(2);
        assertThat(sync.synchronize()).isEqualTo(2);
        verify(client, times(2)).findCatalog(null);
        verify(registry, times(4)).upsertCatalogPage(anyList());
    }

    @Test
    void 페이지_한도에_걸리면_다음_실행은_이어서_읽는다() {
        // 매번 처음부터 1만 건만 읽으면 그 뒤 상품은 영영 들어오지 않는다
        when(client.isConfigured()).thenReturn(true);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        when(client.findCatalog(any())).thenAnswer(inv -> Optional.of(new DataServerCatalogPage(
                List.of(mock(DataServerCatalogPage.Entry.class)), "c" + counter.incrementAndGet())));

        sync.synchronize();
        verify(client, times(DataCatalogSynchronizer.MAX_PAGES_PER_RUN)).findCatalog(any());

        sync.synchronize();
        verify(client).findCatalog("c" + DataCatalogSynchronizer.MAX_PAGES_PER_RUN);
    }

    @Test
    void 외부_실패에서는_캐시를_지우지_않고_다음_실행을_허용한다() {
        when(client.isConfigured()).thenReturn(true);
        when(client.findCatalog(null)).thenReturn(Optional.empty());
        assertThat(sync.synchronize()).isZero();
        assertThat(sync.synchronize()).isZero();
        verifyNoInteractions(registry);
        verify(client, times(2)).findCatalog(null);
    }

    @Test
    void 반복_cursor는_무한히_읽지_않는다() {
        when(client.isConfigured()).thenReturn(true);
        var page = new DataServerCatalogPage(List.of(mock(DataServerCatalogPage.Entry.class)), "same");
        when(client.findCatalog(any())).thenReturn(Optional.of(page));
        when(registry.upsertCatalogPage(anyList())).thenReturn(1);
        assertThat(sync.synchronize()).isEqualTo(2);
        verify(client, times(2)).findCatalog(any());
    }

    @Test
    void 실행_중에는_중복_동기화를_하지_않는다() throws Exception {
        when(client.isConfigured()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.findCatalog(null)).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });
        Thread thread = new Thread(sync::synchronize);
        thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sync.synchronize()).isZero();
        } finally {
            release.countDown();
            thread.join(5000);
        }
        verify(client).findCatalog(null);
    }
}
