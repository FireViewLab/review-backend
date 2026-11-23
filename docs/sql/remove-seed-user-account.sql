-- PostgreSQL: 과거 시드 사용자 user@fireview.com 및 해당 사용자의 종속 행만 정리한다.
-- 실행 전 백업 및 대상 DB/스키마와 아래 조회 결과를 확인한다.
-- admin@fireview.com, 상품, 리뷰는 삭제하지 않는다.
-- 스키마에 추가된 FK가 있으면 검토하여 반영한다. FK 제약은 비활성화하지 않는다.
-- 기본은 ROLLBACK이다. 검토 후 실제 반영할 때만 마지막 문장을 COMMIT으로 바꾼다.
BEGIN;

SELECT id, email, nickname, role, provider, created_at
FROM users WHERE email = 'user@fireview.com';

DO $$
DECLARE
    seed_user_id BIGINT;
BEGIN
    SELECT id INTO seed_user_id
    FROM users
    WHERE email = 'user@fireview.com' AND provider = 'LOCAL' AND role = 'USER'
    FOR UPDATE;

    IF seed_user_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM users WHERE email = 'user@fireview.com') THEN
            RAISE EXCEPTION '대상 이메일의 provider/role이 과거 시드 사용자와 다릅니다';
        END IF;
        RAISE NOTICE '삭제 대상 시드 사용자가 없습니다';
        RETURN;
    END IF;

    -- 간접 FK: 메시지 -> 세션 -> 사용자
    DELETE FROM chat_messages WHERE session_id IN
        (SELECT id FROM chat_sessions WHERE user_id = seed_user_id);
    DELETE FROM chat_sessions WHERE user_id = seed_user_id;

    -- 간접 FK: 분석 피드백 신호 -> 분석 피드백 -> 사용자
    DELETE FROM analysis_feedback_signals WHERE feedback_id IN
        (SELECT id FROM analysis_feedbacks WHERE user_id = seed_user_id);
    DELETE FROM analysis_feedbacks WHERE user_id = seed_user_id;

    -- 간접 FK: 선호 카테고리 -> 선호 설정 -> 사용자
    DELETE FROM user_preferred_categories WHERE preference_id IN
        (SELECT id FROM user_preferences WHERE user_id = seed_user_id);
    DELETE FROM user_preferences WHERE user_id = seed_user_id;

    -- 직접 FK 및 사용자 ElementCollection
    DELETE FROM wishlists WHERE user_id = seed_user_id;
    DELETE FROM cart_items WHERE user_id = seed_user_id;
    DELETE FROM cart_events WHERE user_id = seed_user_id;
    DELETE FROM cart_schedules WHERE user_id = seed_user_id;
    DELETE FROM notifications WHERE receiver_id = seed_user_id;
    DELETE FROM user_settings WHERE user_id = seed_user_id;
    DELETE FROM reports WHERE reporter_id = seed_user_id;
    DELETE FROM review_feedbacks WHERE user_id = seed_user_id;
    DELETE FROM view_histories WHERE user_id = seed_user_id;
    DELETE FROM user_interest_categories WHERE user_id = seed_user_id;
    DELETE FROM users WHERE id = seed_user_id;
END;
$$;

SELECT id, email FROM users WHERE email = 'user@fireview.com';
ROLLBACK;
