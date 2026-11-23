package com.example.fireview.domain.reviewsummary.repository;

import com.example.fireview.domain.reviewsummary.entity.ProductReviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, Long> {

    Optional<ProductReviewSummary> findByDataPlatformAndDataProductId(String dataPlatform, String dataProductId);
}
