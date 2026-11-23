package com.example.fireview.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    AI_ANALYSIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "분석 서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    // Auth
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않은 비밀번호 재설정 토큰입니다."),
    EXPIRED_RESET_TOKEN(HttpStatus.BAD_REQUEST, "만료된 비밀번호 재설정 토큰입니다. 다시 요청해주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료되었습니다. 다시 로그인해주세요."),
    AUTH_SESSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "로그인 세션을 일시적으로 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_COLLECTED(HttpStatus.CONFLICT, "상품 정보를 아직 수집하지 못했습니다. 잠시 후 다시 시도해주세요."),
    DATA_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "상품 정보 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // Review
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    FEEDBACK_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 피드백을 제출하셨습니다."),
    FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "피드백 내역을 찾을 수 없습니다."),

    // Onboarding
    PREFERENCE_ALREADY_SET(HttpStatus.CONFLICT, "이미 온보딩이 완료되었습니다."),

    // Notification
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // Report
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "신고 내역을 찾을 수 없습니다."),
    REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 신고한 리뷰입니다."),
    REPORT_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 신고만 조회할 수 있습니다."),

    // Wishlist
    WISHLIST_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 찜한 상품입니다."),
    WISHLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "찜 목록에 없는 상품입니다."),

    // Cart
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니에 없는 상품입니다."),

    // Search
    NAVER_API_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "네이버 검색 API가 설정되지 않았습니다."),

    // Chat
    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."),
    CHAT_SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 대화만 조회할 수 있습니다."),
    CHAT_LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "챗봇이 일시적으로 응답할 수 없습니다. 잠시 후 다시 시도해주세요."),
    CHAT_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 사용할 수 있는 챗봇 메시지를 모두 사용했습니다. 요금제를 올리면 더 많이 이용할 수 있습니다."),
    CHAT_PLAN_REQUIRED(HttpStatus.FORBIDDEN, "프로 요금제에서 이용할 수 있는 기능입니다."),

    // Cart schedule
    CART_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 예약을 찾을 수 없습니다."),
    CART_SCHEDULE_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 장바구니 예약만 바꿀 수 있습니다."),
    CART_SCHEDULE_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "확인할 수 없는 예약입니다. 챗봇에 다시 요청해주세요."),

    // Repurchase
    REPURCHASE_NOT_SCHEDULABLE(HttpStatus.CONFLICT, "지금 정기 예약을 제안할 수 있는 상품이 아닙니다."),

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}