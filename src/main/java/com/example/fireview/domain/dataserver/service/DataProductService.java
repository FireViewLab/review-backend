package com.example.fireview.domain.dataserver.service;

import com.example.fireview.domain.dataserver.DataServerProductKey;
import com.example.fireview.domain.dataserver.client.DataServerClient;
import com.example.fireview.domain.dataserver.dto.DataServerAnalysis;
import com.example.fireview.domain.dataserver.dto.DataServerJob;
import com.example.fireview.domain.dataserver.dto.DataServerProduct;
import com.example.fireview.domain.dataserver.dto.DataServerProductResponse;
import com.example.fireview.domain.dataserver.dto.DataServerReview;
import com.example.fireview.domain.dataserver.dto.DataServerReviewAnalysis;
import com.example.fireview.domain.dataserver.dto.response.AnalysisStatus;
import com.example.fireview.domain.dataserver.dto.response.CollectionStatus;
import com.example.fireview.domain.dataserver.dto.response.DataProductResponse;
import com.example.fireview.domain.product.entity.Product;
import com.example.fireview.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data 서버의 상품·리뷰를 화면용으로 조합한다.
 *
 * <p><b>읽기 전용이다.</b> Data 서버에서 받은 값을 Spring DB 에 복제하지 않는다.
 * 두 곳에 두면 TTL 이 달라 반드시 어긋나고, "어느 쪽이 맞냐"를 매번 따져야 한다.
 *
 * <p>다만 찜·장바구니는 Spring 의 {@code Product.id} 를 FK 로 물고 있어서, 이미 번호표가
 * 있는 상품이면 그 id 를 함께 내려준다. <b>없다고 새로 만들지는 않는다.</b> 상품을 열어보기만
 * 해도 행이 생기면 Data 서버에 있는 상품 수만큼 빈 행이 쌓인다. 번호표는 찜처럼
 * 실제로 Spring 쪽 기록이 필요해질 때 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataProductService {

    private final DataServerClient dataServerClient;
    private final ProductRepository productRepository;
    private final ProductTagRegistry registry;

    /**
     * 트랜잭션을 걸지 않는다. 대부분의 시간이 Data 서버 응답을 기다리는 데 쓰이는데,
     * 그동안 DB 커넥션을 붙잡을 이유가 없다. 번호표 조회·갱신은 각자 짧게 끝난다.
     */
    public DataProductResponse getProduct(String platform, String productId, String cursor) {
        DataServerProductKey key = new DataServerProductKey(platform, productId);

        Optional<DataServerProductResponse> found = dataServerClient.findProduct(key, cursor);
        if (found.isEmpty()) {
            // Data 서버에 닿지 못했다. 404 로 바꾸면 "상품이 없다"는 뜻이 되어버린다.
            // 없는 것과 못 가져온 것은 프론트가 다르게 다뤄야 한다.
            log.warn("[DataProduct] Data 서버 조회 실패 - key={}", key.asExternalId());
            return new DataProductResponse(CollectionStatus.UNAVAILABLE,
                    springProductId(key), null, emptyPage(), null,
                    AnalysisStatus.UNAVAILABLE, null);
        }

        DataServerProductResponse body = found.get();
        CollectionStatus status = CollectionStatus.from(body.status());
        AnalysisStatus analysisStatus = analysisStatus(body.analysis());

        // 이미 번호표가 있는 상품이면 홈·검색 목록에 보이는 표시 정보(가격·이미지 등)를
        // 지금 받은 값으로 덮는다. 목록 캐시가 오래 낡지 않게 하는 지점이다.
        // 번호표가 없으면 만들지 않는다 — 열어보기만 해도 행이 생기면 안 된다.
        Product cached = null;
        if (body.hasUsableData() && registry.find(key).isPresent()) {
            cached = registry.upsertForDisplay(body.product());
        }
        // 목록에 보일 분석 상태도 같은 이유로 번호표에 적는다. 번호표가 없으면 아무것도 하지 않는다.
        // UNAVAILABLE(Data 미도달·구버전)은 적지 않는다 — 일시 장애가 마지막으로 본 상태를 덮으면 안 된다.
        registry.recordAnalysisStatus(key, analysisStatus);

        return new DataProductResponse(
                status,
                springProductId(key),
                body.hasUsableData() ? toDetail(key, body.product(), cached) : null,
                toReviewPage(body),
                toJob(body.job()),
                analysisStatus,
                toAnalysis(analysisStatus, body.analysis()));
    }

    /** 수집 진행 상황. QUEUED 를 받은 프론트가 이걸로 완료를 기다린다 */
    @Transactional(readOnly = true)
    public Optional<DataProductResponse.CollectionJobStatus> getJob(long jobId) {
        return dataServerClient.findJob(jobId).map(this::toJob);
    }

    // ────────────────────────────── 내부 ──────────────────────────────

    /**
     * 이미 만들어 둔 번호표가 있으면 돌려준다.
     * 없으면 null — 이 상품으로 찜을 걸 때 그때 만든다.
     */
    private Long springProductId(DataServerProductKey key) {
        return productRepository
                .findByDataPlatformAndDataProductId(key.platform(), key.productId())
                .map(p -> p.getId())
                .orElse(null);
    }

    /**
     * @param cached 목록 표시용으로 적어 둔 값. 없으면 null.
     *               상세 응답이 비워 보낸 리뷰 수·평점을 여기서 보충한다. 11번가·올리브영은
     *               검색 응답에만 이 값을 주고 상세 응답에서는 비우는데, 그대로 내려주면
     *               목록에서 본 수치가 상세 화면에서 사라진다.
     */
    private DataProductResponse.DataProductDetail toDetail(DataServerProductKey key,
                                                           DataServerProduct p,
                                                           Product cached) {
        return new DataProductResponse.DataProductDetail(
                p.platform() != null ? p.platform() : key.platform(),
                p.productId() != null ? p.productId() : key.productId(),
                key.asExternalId(),
                p.name(), p.url(), p.brand(), p.manufacturer(), p.seller(),
                p.price(), p.thumbnailUrl(), p.category(),
                p.reviewCount() != null ? p.reviewCount() : cachedReviewCount(cached),
                p.rating() != null ? p.rating() : cachedRating(cached),
                p.lastCollectedAt());
    }

    // 번호표를 만들 때 리뷰 수·평점이 비어 있으면 0 으로 채워진다(Product.onCreate).
    // 그 0 은 "모름"이므로 보충하지 않는다. 실제 0 개를 보여주는 것보다 비워 두는 편이 낫다.
    private static Integer cachedReviewCount(Product cached) {
        if (cached == null || cached.getReviewCount() == null || cached.getReviewCount() <= 0) return null;
        return cached.getReviewCount();
    }

    private static Double cachedRating(Product cached) {
        if (cached == null || cached.getAvgRating() == null || cached.getAvgRating() <= 0) return null;
        return cached.getAvgRating();
    }

    /** 구버전 Data 서버는 analysis 를 보내지 않는다. null 대신 UNAVAILABLE 로 내려준다 */
    private static AnalysisStatus analysisStatus(DataServerAnalysis analysis) {
        return analysis == null ? AnalysisStatus.UNAVAILABLE : AnalysisStatus.from(analysis.status());
    }

    /** 결과가 없는 상태면 null — 프론트의 {@code analysis != null} 판정을 지킨다 */
    private DataProductResponse.ProductAnalysis toAnalysis(AnalysisStatus status, DataServerAnalysis analysis) {
        if (analysis == null || !DataProductResponse.hasAnalysisResult(status)) return null;
        return new DataProductResponse.ProductAnalysis(
                status,
                analysis.modelVersion(),
                analysis.policyVersion(),
                analysis.reviewCount(),
                analysis.sampled(),
                analysis.sourceReviewCount(),
                analysis.contractVersion());
    }

    private DataProductResponse.ReviewPage toReviewPage(DataServerProductResponse body) {
        // Data 서버는 이번 페이지의 리뷰 결과만 보내므로 review_id 로 그대로 맞춰 붙인다
        Map<String, DataServerReviewAnalysis> results = body.analysis() == null
                ? Map.of()
                : body.analysis().resultsByReviewId();
        List<DataProductResponse.DataReview> items = body.reviewItems().stream()
                .map(r -> toReview(r, r.reviewId() == null ? null : results.get(r.reviewId())))
                .toList();
        String next = body.reviews() == null ? null : body.reviews().nextCursor();
        return new DataProductResponse.ReviewPage(items, next);
    }

    /**
     * @param analysis 이 리뷰의 분석 결과. 없으면 null — 점수를 0 으로 채우지 않고 비워 둔다
     */
    private DataProductResponse.DataReview toReview(DataServerReview r, DataServerReviewAnalysis analysis) {
        return new DataProductResponse.DataReview(
                r.reviewId(), r.content(), r.rating(), r.author(),
                r.writtenAt(), r.option(),
                r.images() == null ? List.of() : r.images(),
                r.helpfulCount(),
                analysis == null ? null : analysis.rti(),
                analysis == null ? null : analysis.level(),
                analysis == null ? List.of() : analysis.reasons(),
                analysis == null ? null : analysis.levelV4(),
                analysis == null ? null : analysis.policyIndexV4(),
                analysis == null ? null : analysis.textScoreV4(),
                analysis == null ? null : analysis.textProbabilityV4(),
                analysis == null ? null : analysis.modelVersionV4(),
                analysis == null ? null : analysis.policyVersionV4());
    }

    private DataProductResponse.CollectionJobStatus toJob(DataServerJob job) {
        if (job == null) return null;
        return new DataProductResponse.CollectionJobStatus(
                job.id(), job.status(), job.productStatus(), job.reviewStatus(), job.lastError());
    }

    private DataProductResponse.ReviewPage emptyPage() {
        return new DataProductResponse.ReviewPage(List.of(), null);
    }
}
