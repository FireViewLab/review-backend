package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.product.dto.ProductResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProductCatalogServiceTest {

    private DataServerClient client;
    private ProductTagRegistry registry;
    private ExecutorService pool;

    private static DataServerProduct p(String platform, String id) {
        return new DataServerProduct(platform, id, platform + " 상품 " + id, "https://x/" + id,
                null, null, null, 10000, null, "뷰티", 3, null, null);
    }

    private DataProductCatalogService service(String platforms, long timeoutMs) {
        return new DataProductCatalogService(client, registry, pool, platforms, 10, timeoutMs);
    }

    @BeforeEach
    void setUp() {
        client = mock(DataServerClient.class);
        registry = mock(ProductTagRegistry.class);
        pool = Executors.newFixedThreadPool(4);
        when(client.isConfigured()).thenReturn(true);
        when(registry.upsertAllForDisplay(anyList())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private List<DataServerProduct> savedList() {
        ArgumentCaptor<List<DataServerProduct>> captor = ArgumentCaptor.forClass(List.class);
        verify(registry).upsertAllForDisplay(captor.capture());
        return captor.getValue();
    }

    @Test
    void 몰별_결과를_번갈아_섞는다() {
        // 이어 붙이면 첫 몰 상품이 목록 위쪽을 다 차지한다
        when(client.searchProducts(eq("kurly"), anyString(), anyInt()))
                .thenReturn(List.of(p("kurly", "1"), p("kurly", "2"), p("kurly", "3")));
        when(client.searchProducts(eq("oliveyoung"), anyString(), anyInt()))
                .thenReturn(List.of(p("oliveyoung", "A"), p("oliveyoung", "B")));

        service("kurly,oliveyoung", 2000).search("마스크팩");

        assertThat(savedList()).extracting(d -> d.platform() + d.productId())
                .containsExactly("kurly1", "oliveyoungA", "kurly2", "oliveyoungB", "kurly3");
    }

    @Test
    void 몰당_건수를_지정하면_그_수로_요청한다() {
        // 홈 자동 채우기는 키워드 하나가 홈을 다 차지하지 않게 적게 받는다
        when(client.searchProducts(anyString(), anyString(), anyInt())).thenReturn(List.of(p("kurly", "1")));

        service("kurly,oliveyoung", 2000).search("마스크팩", 3);

        verify(client).searchProducts("kurly", "마스크팩", 3);
        verify(client).searchProducts("oliveyoung", "마스크팩", 3);
    }

    @Test
    void 같은_상품이_두_번_오면_한_번만_남긴다() {
        when(client.searchProducts(eq("kurly"), anyString(), anyInt()))
                .thenReturn(List.of(p("kurly", "1"), p("kurly", "1")));

        service("kurly", 2000).search("마스크팩");

        assertThat(savedList()).hasSize(1);
    }

    @Test
    void 느린_몰은_제한_시간이_지나면_빼고_응답한다() {
        // 옥션은 운영에서 30초 넘게 걸린 적이 있다. 한 몰 때문에 검색 전체가 묶이면 안 된다
        when(client.searchProducts(eq("kurly"), anyString(), anyInt()))
                .thenReturn(List.of(p("kurly", "1")));
        when(client.searchProducts(eq("auction"), anyString(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return List.of(p("auction", "X"));
        });

        long start = System.currentTimeMillis();
        service("kurly,auction", 300).search("마스크팩");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2000);
        assertThat(savedList()).extracting(DataServerProduct::platform).containsExactly("kurly");
    }

    @Test
    void 한_몰이_실패해도_나머지로_응답한다() {
        when(client.searchProducts(eq("kurly"), anyString(), anyInt()))
                .thenReturn(List.of(p("kurly", "1")));
        when(client.searchProducts(eq("naver"), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("브라우저가 뜨지 않음"));

        service("kurly,naver", 2000).search("마스크팩");

        assertThat(savedList()).extracting(DataServerProduct::platform).containsExactly("kurly");
    }

    @Test
    void 결과가_없으면_저장하지_않고_빈_목록이다() {
        when(client.searchProducts(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<ProductResponse> res = service("kurly,oliveyoung", 2000).search("없는상품");

        assertThat(res).isEmpty();
        verify(registry, never()).upsertAllForDisplay(anyList());
    }

    @Test
    void 동시_저장_충돌이면_한_번_더_시도한다() {
        // 같은 새 상품을 두 요청이 동시에 만들면 유니크 제약에 걸린다. 다시 하면 갱신만 일어난다
        when(client.searchProducts(eq("kurly"), anyString(), anyInt()))
                .thenReturn(List.of(p("kurly", "1")));
        when(registry.upsertAllForDisplay(anyList()))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenReturn(List.of());

        service("kurly", 2000).search("마스크팩");

        verify(registry, times(2)).upsertAllForDisplay(anyList());
    }

    @Test
    void Data_서버가_설정되지_않으면_쓰지_않는다() {
        when(client.isConfigured()).thenReturn(false);

        assertThat(service("kurly", 2000).isEnabled()).isFalse();
    }

    @Test
    void 실시간_검색이_비면_저장된_상품으로_대신한다() {
        // 수집이 멈추면 Data 는 몰 검색을 거절하지만, 이미 모은 상품은 남아 있다
        when(client.searchProducts(anyString(), anyString(), anyInt())).thenReturn(List.of());
        ProductResponse stored = ProductResponse.from(com.example.fireview.domain.product.entity.Product.builder()
                .id(1L).name("토리든 마스크팩").platform("KURLY").dataPlatform("kurly").dataProductId("1").build());
        when(registry.findStoredForDisplay("마스크팩", 20)).thenReturn(List.of(stored));

        List<ProductResponse> res = service("kurly,oliveyoung", 2000).search("마스크팩");

        assertThat(res).containsExactly(stored);
        verify(registry, never()).upsertAllForDisplay(anyList());
    }

    @Test
    void 실시간_결과가_있으면_저장된_상품을_찾지_않는다() {
        when(client.searchProducts(eq("kurly"), anyString(), anyInt())).thenReturn(List.of(p("kurly", "1")));
        when(registry.upsertAllForDisplay(anyList())).thenReturn(List.of(ProductResponse.from(
                com.example.fireview.domain.product.entity.Product.builder().id(1L).name("실시간")
                        .platform("KURLY").dataPlatform("kurly").dataProductId("1").build())));

        assertThat(service("kurly", 2000).search("마스크팩")).hasSize(1);

        verify(registry, never()).findStoredForDisplay(anyString(), anyInt());
    }

    @Test
    void 몰당_건수를_정한_검색은_저장된_상품으로_대신하지_않는다() {
        // 홈 자동 채우기가 쓰는 경로다. 새로 채운 수를 세므로 저장된 상품이 섞이면 안 된다
        when(client.searchProducts(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertThat(service("kurly", 2000).search("마스크팩", 3)).isEmpty();
        verify(registry, never()).findStoredForDisplay(anyString(), anyInt());
    }

    @Test
    void LIKE_특수_문자는_글자로_찾게_바꾼다() {
        assertThat(ProductTagRegistry.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
    }

    @Test
    void 검색_대상_몰이_비면_쓰지_않는다() {
        assertThat(service(" , ", 2000).isEnabled()).isFalse();
    }
}
