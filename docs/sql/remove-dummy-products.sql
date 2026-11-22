-- PostgreSQL: 과거 DataInitializer 시드로 들어간 더미 상품과 그 리뷰, 종속 행을 정리한다. (#195)
-- 실행 전에 아래 "실행 전 체크리스트"를 모두 확인한다.
--
-- 판별 조건: 아래 두 조건을 모두 만족하는 상품만 지운다.
--   1) id 가 더미 구간(fireview.dummy_id_min ~ fireview.dummy_id_max) 안에 있다.
--   2) Data 서버 번호표(products.data_platform, products.data_product_id)가 없다.
--      Product.hasDataServerAddress() 와 같은 기준이다(둘 중 하나라도 NULL 또는 공백이면 번호표 없음).
--      공백은 Java String.isBlank() 와 같게 Character.isWhitespace 문자 집합으로 판정한다.
--   번호표가 없다는 것만으로는 더미라고 볼 수 없다. ProductService.doSaveFromCache 가 네이버 캐시로
--   저장하는 실제 상품도 번호표가 없다. 그래서 id 구간을 함께 본다.
--   번호표가 없지만 구간 밖인 상품은 미리보기에 따로 보여 주고 지우지 않는다.
--   구간 안인데 번호표가 있는 상품이 있으면 중단한다.
--
-- ⚠️ 실행 전 체크리스트. 하나라도 확인하지 못했으면 실행하지 않는다.
--   1) 백업을 받고 복원할 수 있는지 확인한다. 아래 "실제 사용자 데이터도 함께 지워진다"의 행 중
--      남겨야 할 것은 따로 보관한다.
--   2) 접속 대상을 확인한다. 이 파일은 테이블 이름을 스키마 없이 쓰므로 search_path 로 대상이 정해진다.
--      current_database(), current_user, current_setting('search_path') 를 보고, 삭제 대상 11개 테이블
--      (FK 사전 점검의 targets)과 users 가 to_regclass 로 의도한 앱 스키마의 테이블로 풀리는지 확인한 뒤
--      그 search_path 로 고정해 실행한다. 상속·파티션 관계와 예상 밖 FK 는 아래 점검이 삭제 전에 중단시키지만,
--      사용자가 만든 DELETE 트리거나 RULE 은 검사하지 않으므로 있으면 따로 검토한다.
--   3) 유지보수 시간에 쓰기를 멈춘다. 앱·배치·관리자의 상품·리뷰·신고·피드백(리뷰 피드백, 분석 피드백과
--      신호)·찜·장바구니·조회 이력 쓰기와 스키마 변경(배포에 따른 ddl-auto=update, 마이그레이션 포함)을
--      멈추고, 진행 중인 트랜잭션이 끝나기를 기다린다.
--   4) 더미 id 구간과 예상 건수(아래 SET LOCAL)의 근거를 확인하고 미리보기 결과와 대조한다. 대상 상품·리뷰
--      건수, 구간 안 번호표 있는 상품 0건, 구간 밖 번호표 없는 보존 상품 목록, 종속 테이블별 삭제 건수를 본다.
--      건수가 다르면 숫자를 바로 고치지 말고 원인부터 확인한다.
--   5) 전용 세션 하나에서 psql -X -v ON_ERROR_STOP=1 -f 로 파일 전체를 실행한다. 먼저 이 원본(마지막 문장
--      ROLLBACK)으로 실행해 출력을 로그로 남기고, 검토 후 마지막 문장만 COMMIT 으로 바꾼 사본을 처음부터 다시
--      실행한다. 트랜잭션을 열어 둔 채 검토하지 않는다.
--   6) FK·상속 점검, 건수 가드, deadlock detected, lock_timeout, statement_timeout 으로 실패하면 전체가
--      롤백된 것이다. 세션을 끊거나 ROLLBACK 해 잠금이 풀렸는지 확인하고, 원인을 해결한 뒤 파일 전체를
--      처음부터 다시 실행한다. 실패한 세션에서 중간부터 이어서 실행하지 않는다.
--   7) COMMIT 후 실행 후 건수(대상 0건, 구간 밖 보존 건수·사용자 수 불변)를 확인하고 로그를 보관한 뒤
--      쓰기를 재개한다. FK 가 없는 notifications.target_url 등은 정리되지 않는다.
--   쓰기를 멈추는 이유: 아래 잠금은 대상 행이 바뀌거나 새 종속 행이 붙는 것을 막지만, 다른 트랜잭션이
--   reviews 다음 products 처럼 이 파일과 다른 순서로 잠그면 데드락이 날 수 있다. 잠금 순서를 이 파일
--   쪽에서만 맞춰서는 앱의 모든 트랜잭션과 순서가 맞는다고 보장할 수 없다. 실패해도 전체 롤백이라
--   데이터는 상하지 않지만, 쓰기를 멈추는 것이 확실한 예방이다.
--
-- ⚠️ 실행 중에는 상품·리뷰 쓰기가 막힌다.
--   대상을 고르기 전에 products, reviews 를 SHARE ROW EXCLUSIVE 로 잠근다. 읽기는 되지만
--   상품·리뷰 추가·수정·삭제는 이 트랜잭션이 끝날 때까지 기다린다. 대상을 고른 뒤 다른 세션이
--   번호표를 채운 상품이 지워지는 일을 막기 위해서다. DO 블록에서는 대상 상품·리뷰·분석 피드백 행을
--   FOR UPDATE 로 잠가 새 종속 행이 붙지 못하게 한다. 잠금을 lock_timeout 안에 못 얻으면 실패한다.
--
-- ⚠️ 실제 사용자 데이터도 함께 지워진다.
--   더미 상품에 대한 찜(wishlists), 장바구니(cart_items), 조회 이력(view_histories),
--   더미 리뷰 또는 더미 상품(외부 리뷰 대상)에 대한 신고(reports), 리뷰 피드백(review_feedbacks),
--   분석 피드백(analysis_feedbacks)은 실제 사용자가 남긴 행이어도 상품·리뷰가 사라지면 FK 때문에 남길 수 없다.
--   아래 조회 결과에서 건수를 확인하고, 필요하면 실행 전에 따로 보관한다.
--   신고·피드백 처리 결과 알림(notifications)은 FK 가 없어 남는다(target_url 이 사라진 신고를 가리킬 수 있다).
--
-- 사용자 계정(users 와 사용자 종속 테이블)과 번호표가 있는 실제 상품은 삭제하지 않는다.
-- FK 제약은 비활성화하지 않는다. 잠금 직후 FK 목록을 엔티티 기준 13개와 대조해, 다르면 삭제 전에 중단한다.
--   운영은 ddl-auto=update 라 엔티티에 없는 FK 가 남아 있을 수 있다. ON DELETE CASCADE / SET NULL 인
--   FK 는 지울 때 오류 없이 다른 행을 지우거나 바꾸므로, 건수 가드로는 잡히지 않는다.
--   삭제 대상 테이블이 상속·파티션 관계에 있어도 중단한다(엔티티 스키마에는 없다).
-- 기본은 ROLLBACK이다. 검토 후 실제 반영할 때만 마지막 문장을 COMMIT으로 바꾼다.
BEGIN;

-- 잠금 대기와 문장 실행 시간 상한. 넘으면 오류로 트랜잭션이 중단되고 아무것도 반영되지 않는다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- 더미 id 구간. DataInitializer 의 FALLBACK_ID 가 900000000000 부터 1씩 붙인 id 다.
-- 2026-10 운영 공개 GET 으로 900000000000·900000000032 는 있고 900000000033 은 없음을 확인했다(#195).
SET LOCAL fireview.dummy_id_min = '900000000000';
SET LOCAL fireview.dummy_id_max = '900000000032';

-- 예상 건수. 2026-10 운영 DB 기준 더미 상품 33건, 그 리뷰 1117건이다(#165, #195).
-- 구간 길이와 같은 33건이면 구간 안의 id 가 빠짐없이 대상이라는 뜻이다.
-- 판별 조건에 걸린 건수가 이와 다르면 데이터가 바뀐 것이므로 중단한다.
-- 다시 확인해서 맞다고 판단될 때만 숫자를 고친다.
SET LOCAL fireview.expected_dummy_products = '33';
SET LOCAL fireview.expected_dummy_reviews = '1117';

-- 대상을 고르기 전에 상품·리뷰 쓰기를 막는다. 읽기는 막지 않는다.
LOCK TABLE products, reviews IN SHARE ROW EXCLUSIVE MODE;

-- FK 사전 점검. 이 파일이 행을 지우는 테이블을 가리키는 FK 전체가 아래 13개와 정확히 같아야 한다
-- (2026-10 Hibernate 가 엔티티로 PostgreSQL 16 에 만든 스키마 기준).
-- analysis_feedbacks.product_id 는 #208(외부 리뷰 분석 피드백)에서 생겼다. 그 배포 전 스키마에는 없으므로
-- #208 배포(ddl-auto=update 로 칼럼·FK 추가) 뒤에 실행한다. 배포 전이면 이 점검이 missing 으로 중단한다.
-- 하나라도 더 있거나 없거나, 같은 FK 가 두 번 이상 있거나, 칼럼·대상이 다르거나, ON DELETE / ON UPDATE 가
-- NO ACTION·RESTRICT 가 아니거나, 지연 검사(DEFERRABLE)이거나, 검증되지 않은(NOT VALID) FK 면 중단한다.
-- 제약 이름은 Hibernate 가 해시로 만들어 환경마다 다를 수 있으므로 테이블·칼럼 번호로 비교한다.
-- 다른 스키마의 테이블이 거는 FK 도 잡힌다. 의도된 FK 라면 검토한 뒤 이 목록과 DELETE 문에 함께 반영한다.
--
-- 삭제 대상 테이블이 상속 부모·자식이거나 파티션(테이블)이면 먼저 중단한다. DELETE 는 기본으로 상속 자식
-- 테이블의 행까지 지우는데, 자식 테이블을 가리키는 FK 는 아래 FK 대조에 잡히지 않는다(FK 는 자식에
-- 상속되지 않고, 대조는 targets 의 테이블만 본다). 엔티티 스키마에는 상속·파티션이 없으므로 지원하지 않는다.
DO $$
DECLARE
    -- 이 파일이 DELETE 하는 테이블 전체. 지금은 products, reviews, analysis_feedbacks 만 FK 로 참조되지만
    -- 나머지 테이블에 새 FK 가 붙어도 간접 종속 행이 지워지거나 막히므로 함께 본다.
    targets OID[] := ARRAY[
        to_regclass('products'), to_regclass('reviews'), to_regclass('analysis_feedbacks'),
        to_regclass('analysis_feedback_signals'), to_regclass('review_feedbacks'), to_regclass('reports'),
        to_regclass('review_reasons'), to_regclass('wishlists'), to_regclass('cart_items'),
        to_regclass('cart_events'), to_regclass('view_histories'), to_regclass('product_platform_links')]::OID[];
    mismatch TEXT;
BEGIN
    SELECT string_agg(c.oid::regclass::TEXT || ' relkind=' || c.relkind::TEXT
               || CASE WHEN c.relispartition THEN ' partition' ELSE '' END
               || CASE WHEN EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)
                       THEN ' 상속 자식' ELSE '' END
               || CASE WHEN EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhparent = c.oid)
                       THEN ' 상속 부모' ELSE '' END, E'\n')
    INTO mismatch
    FROM pg_class c
    WHERE c.oid = ANY (targets)
      AND (c.relkind <> 'r' OR c.relispartition
           OR EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid OR i.inhparent = c.oid));
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION '삭제 대상 테이블에 상속·파티션 관계가 있습니다. 지원하지 않는 구조이므로 검토하세요:%',
            E'\n' || mismatch;
    END IF;

    WITH expected(child, child_col, parent) AS (VALUES
        ('reviews', 'product_id', 'products'),
        ('wishlists', 'product_id', 'products'),
        ('cart_items', 'product_id', 'products'),
        ('cart_events', 'product_id', 'products'),
        ('view_histories', 'product_id', 'products'),
        ('product_platform_links', 'product_id', 'products'),
        ('reports', 'product_id', 'products'),
        ('review_feedbacks', 'product_id', 'products'),
        ('analysis_feedbacks', 'product_id', 'products'),
        ('analysis_feedbacks', 'review_id', 'reviews'),
        ('reports', 'review_id', 'reviews'),
        ('review_feedbacks', 'review_id', 'reviews'),
        ('review_reasons', 'review_id', 'reviews'),
        ('analysis_feedback_signals', 'feedback_id', 'analysis_feedbacks')
    ), e AS (
        SELECT to_regclass(child)::oid AS child_oid,
               ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass(child)
                      AND attname = child_col AND NOT attisdropped)]::SMALLINT[] AS child_cols,
               to_regclass(parent)::oid AS parent_oid,
               ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass(parent)
                      AND attname = 'id' AND NOT attisdropped)]::SMALLINT[] AS parent_cols,
               child, child_col, parent
        FROM expected
    ), actual AS (
        -- copies: 칼럼·대상이 같은 FK 의 개수. 예상 FK 마다 정확히 1개여야 한다.
        SELECT c.*, COUNT(*) OVER (PARTITION BY c.conrelid, c.conkey, c.confrelid, c.confkey) AS copies
        FROM pg_constraint c
        WHERE c.contype = 'f' AND c.confrelid = ANY (targets)
    )
    SELECT string_agg(COALESCE(a.conrelid::regclass::TEXT, e.child) || ':' || COALESCE(a.conname, e.child_col)
               || ' -> ' || COALESCE(a.confrelid::regclass::TEXT, e.parent)
               || ' delete=' || COALESCE(a.confdeltype::TEXT, 'missing')
               || CASE WHEN a.copies > 1 THEN ' copies=' || a.copies ELSE '' END, E'\n')
    INTO mismatch
    FROM e FULL JOIN actual a
      ON a.conrelid = e.child_oid AND a.conkey = e.child_cols
     AND a.confrelid = e.parent_oid AND a.confkey = e.parent_cols
    WHERE a.oid IS NULL OR e.child_oid IS NULL OR a.copies > 1
       OR a.confdeltype NOT IN ('a', 'r') OR a.confupdtype NOT IN ('a', 'r')
       OR a.condeferrable OR NOT a.convalidated;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'FK 목록이 예상과 다릅니다. 검토 후 목록과 DELETE 문을 고치세요:%', E'\n' || mismatch;
    END IF;
END;
$$;

-- 번호표 없는 상품 전체(구간 안팎 모두). 공백 문자 집합은 Character.isWhitespace 와 같다:
-- U+0009~000D, U+001C~0020, U+1680, U+2000~2006, U+2008~200A, U+2028, U+2029, U+205F, U+3000.
CREATE TEMP TABLE no_ticket_products ON COMMIT DROP AS
SELECT p.id FROM products p
CROSS JOIN (SELECT CHR(9) || CHR(10) || CHR(11) || CHR(12) || CHR(13)
        || CHR(28) || CHR(29) || CHR(30) || CHR(31) || CHR(32) || CHR(5760)
        || CHR(8192) || CHR(8193) || CHR(8194) || CHR(8195) || CHR(8196) || CHR(8197) || CHR(8198)
        || CHR(8200) || CHR(8201) || CHR(8202) || CHR(8232) || CHR(8233) || CHR(8287) || CHR(12288)
        AS chars) ws
WHERE p.data_platform IS NULL OR BTRIM(p.data_platform, ws.chars) = ''
   OR p.data_product_id IS NULL OR BTRIM(p.data_product_id, ws.chars) = '';

-- 삭제 대상: 구간 안이면서 번호표 없음
CREATE TEMP TABLE dummy_products ON COMMIT DROP AS
SELECT id FROM no_ticket_products
WHERE id BETWEEN current_setting('fireview.dummy_id_min')::BIGINT
             AND current_setting('fireview.dummy_id_max')::BIGINT;

-- 구간 안인데 번호표가 있는 상품. 1건이라도 있으면 아래 DO 블록에서 중단한다.
CREATE TEMP TABLE ticketed_in_range ON COMMIT DROP AS
SELECT id FROM products
WHERE id BETWEEN current_setting('fireview.dummy_id_min')::BIGINT
             AND current_setting('fireview.dummy_id_max')::BIGINT
  AND id NOT IN (SELECT id FROM no_ticket_products);

CREATE TEMP TABLE dummy_reviews ON COMMIT DROP AS
SELECT id FROM reviews WHERE product_id IN (SELECT id FROM dummy_products);

-- 대상 상품 목록과 건수
SELECT p.id, p.name, p.platform, p.naver_product_id, p.data_platform, p.data_product_id, p.created_at
FROM products p WHERE p.id IN (SELECT id FROM dummy_products) ORDER BY p.id;

-- 번호표가 없지만 구간 밖이라 지우지 않는 상품(네이버 캐시로 저장된 실제 상품 등). 건수와 id 를 확인한다.
SELECT COUNT(*) OVER () AS kept_count, p.id, p.name, p.platform, p.naver_product_id, p.created_at
FROM products p
WHERE p.id IN (SELECT id FROM no_ticket_products) AND p.id NOT IN (SELECT id FROM dummy_products)
ORDER BY p.id;

-- 구간 안인데 번호표가 있는 상품. 결과가 있으면 DO 블록에서 중단된다.
SELECT p.id, p.name, p.data_platform, p.data_product_id FROM products p
WHERE p.id IN (SELECT id FROM ticketed_in_range) ORDER BY p.id;

-- 번호표 칼럼이 하나만 채워진 행. 대상에 들어가지만 정상 경로로는 생기지 않으므로 따로 확인한다.
SELECT id, name, data_platform, data_product_id FROM products
WHERE (data_platform IS NULL) <> (data_product_id IS NULL);

-- 전체 대비 대상 건수와 종속 테이블별 영향 건수
SELECT 'products (전체)' AS target, COUNT(*) AS cnt FROM products
UNION ALL SELECT 'products (대상)', COUNT(*) FROM dummy_products
UNION ALL SELECT 'products (번호표 없음, 구간 밖 보존)', COUNT(*) FROM no_ticket_products
    WHERE id NOT IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'products (구간 안 번호표 있음)', COUNT(*) FROM ticketed_in_range
UNION ALL SELECT 'reviews (전체)', COUNT(*) FROM reviews
UNION ALL SELECT 'reviews (대상)', COUNT(*) FROM dummy_reviews
UNION ALL SELECT 'review_reasons', COUNT(*) FROM review_reasons
    WHERE review_id IN (SELECT id FROM dummy_reviews)
UNION ALL SELECT 'analysis_feedbacks', COUNT(*) FROM analysis_feedbacks
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'analysis_feedback_signals', COUNT(*) FROM analysis_feedback_signals
    WHERE feedback_id IN (SELECT id FROM analysis_feedbacks
        WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products))
UNION ALL SELECT 'review_feedbacks', COUNT(*) FROM review_feedbacks
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'reports', COUNT(*) FROM reports
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'wishlists', COUNT(*) FROM wishlists WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'cart_items', COUNT(*) FROM cart_items WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'cart_events', COUNT(*) FROM cart_events WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'view_histories', COUNT(*) FROM view_histories WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'product_platform_links', COUNT(*) FROM product_platform_links
    WHERE product_id IN (SELECT id FROM dummy_products);

-- 더미 상품·리뷰에 사용자 데이터가 붙은 사용자별 건수(삭제 전 안내·보관용)
SELECT u.id AS user_id, u.email, t.kind, COUNT(*) AS cnt
FROM (
    SELECT user_id, 'wishlists' AS kind FROM wishlists WHERE product_id IN (SELECT id FROM dummy_products)
    UNION ALL SELECT user_id, 'cart_items' FROM cart_items WHERE product_id IN (SELECT id FROM dummy_products)
    UNION ALL SELECT user_id, 'cart_events' FROM cart_events WHERE product_id IN (SELECT id FROM dummy_products)
    UNION ALL SELECT reporter_id, 'reports' FROM reports
        WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
    UNION ALL SELECT user_id, 'review_feedbacks' FROM review_feedbacks
        WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
    UNION ALL SELECT user_id, 'analysis_feedbacks' FROM analysis_feedbacks
        WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
) t JOIN users u ON u.id = t.user_id
GROUP BY u.id, u.email, t.kind ORDER BY u.id, t.kind;

DO $$
DECLARE
    expected_products INTEGER := current_setting('fireview.expected_dummy_products')::INTEGER;
    expected_reviews  INTEGER := current_setting('fireview.expected_dummy_reviews')::INTEGER;
    target_products   INTEGER;
    target_reviews    INTEGER;
    ticketed          INTEGER;
    products_before   INTEGER;
    users_before      INTEGER;
    affected          INTEGER;
BEGIN
    -- 테이블 잠금은 상품·리뷰 자체의 쓰기만 막는다. 대상 행도 잠가 실행 중에 새 찜·장바구니·신고·피드백이
    -- 붙지 못하게 한다(다른 테이블에 행을 넣을 때의 FK 검사가 이 잠금을 기다린다).
    -- 분석 피드백 신호는 리뷰가 아니라 분석 피드백을 가리키므로, 대상 리뷰·상품의 분석 피드백 행도 잠근다.
    -- 상품·리뷰를 먼저 잠갔으므로 이 뒤로는 대상 리뷰나 대상 상품(외부 리뷰 대상)에 새 분석 피드백이 붙지 않는다.
    PERFORM 1 FROM products WHERE id IN (SELECT id FROM dummy_products) ORDER BY id FOR UPDATE;
    PERFORM 1 FROM reviews WHERE id IN (SELECT id FROM dummy_reviews) ORDER BY id FOR UPDATE;
    PERFORM 1 FROM analysis_feedbacks WHERE review_id IN (SELECT id FROM dummy_reviews)
        OR product_id IN (SELECT id FROM dummy_products) ORDER BY id FOR UPDATE;

    SELECT COUNT(*) INTO ticketed FROM ticketed_in_range;
    IF ticketed > 0 THEN
        RAISE EXCEPTION '더미 구간 안에 번호표가 있는 상품이 %건 있습니다. 구간 또는 데이터를 다시 확인하세요', ticketed;
    END IF;

    SELECT COUNT(*) INTO target_products FROM dummy_products;
    SELECT COUNT(*) INTO target_reviews FROM dummy_reviews;
    IF target_products <> expected_products OR target_reviews <> expected_reviews THEN
        RAISE EXCEPTION '대상 건수가 예상과 다릅니다: 상품 %건(예상 %), 리뷰 %건(예상 %)',
            target_products, expected_products, target_reviews, expected_reviews;
    END IF;

    SELECT COUNT(*) INTO products_before FROM products;
    SELECT COUNT(*) INTO users_before FROM users;

    -- 간접 FK: 분석 피드백 신호 -> 분석 피드백 -> 리뷰 또는 상품.
    -- 외부 리뷰 분석 피드백은 review_id 없이 product_id 만 가진다.
    DELETE FROM analysis_feedback_signals WHERE feedback_id IN
        (SELECT id FROM analysis_feedbacks WHERE review_id IN (SELECT id FROM dummy_reviews)
            OR product_id IN (SELECT id FROM dummy_products));
    DELETE FROM analysis_feedbacks WHERE review_id IN (SELECT id FROM dummy_reviews)
        OR product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'analysis_feedbacks: % 건 삭제', affected;

    -- 리뷰 또는 상품을 가리키는 행. 외부 리뷰 신고·피드백은 review_id 없이 product_id 만 가진다.
    DELETE FROM review_feedbacks WHERE review_id IN (SELECT id FROM dummy_reviews)
        OR product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'review_feedbacks: % 건 삭제', affected;
    DELETE FROM reports WHERE review_id IN (SELECT id FROM dummy_reviews)
        OR product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'reports: % 건 삭제', affected;

    -- 리뷰 ElementCollection -> 리뷰
    DELETE FROM review_reasons WHERE review_id IN (SELECT id FROM dummy_reviews);
    DELETE FROM reviews WHERE id IN (SELECT id FROM dummy_reviews);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'reviews: % 건 삭제', affected;

    -- 상품을 직접 가리키는 행
    DELETE FROM wishlists WHERE product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'wishlists: % 건 삭제', affected;
    DELETE FROM cart_items WHERE product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'cart_items: % 건 삭제', affected;
    DELETE FROM cart_events WHERE product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'cart_events: % 건 삭제', affected;
    DELETE FROM view_histories WHERE product_id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'view_histories: % 건 삭제', affected;

    -- 상품 ElementCollection -> 상품
    DELETE FROM product_platform_links WHERE product_id IN (SELECT id FROM dummy_products);
    DELETE FROM products WHERE id IN (SELECT id FROM dummy_products);
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE 'products: % 건 삭제', affected;

    -- 실제 상품과 사용자 계정이 그대로인지 확인한다.
    IF (SELECT COUNT(*) FROM products) <> products_before - target_products THEN
        RAISE EXCEPTION '대상 외 상품 건수가 바뀌었습니다';
    END IF;
    IF (SELECT COUNT(*) FROM users) <> users_before THEN
        RAISE EXCEPTION '사용자 건수가 바뀌었습니다';
    END IF;
END;
$$;

-- 실행 후 건수. 구간 안 상품, 대상 리뷰와 종속 행이 0건이어야 한다. 구간 밖 보존 건수는 실행 전과 같아야 한다.
SELECT 'products (전체)' AS target, COUNT(*) AS cnt FROM products
UNION ALL SELECT 'products (구간 안 남음)', COUNT(*) FROM products
    WHERE id BETWEEN current_setting('fireview.dummy_id_min')::BIGINT
                 AND current_setting('fireview.dummy_id_max')::BIGINT
UNION ALL SELECT 'products (번호표 없음, 구간 밖 보존)', COUNT(*) FROM products
    WHERE id IN (SELECT id FROM no_ticket_products) AND id NOT IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'reviews (전체)', COUNT(*) FROM reviews
UNION ALL SELECT 'reviews (대상 남음)', COUNT(*) FROM reviews WHERE id IN (SELECT id FROM dummy_reviews)
UNION ALL SELECT 'review_reasons (대상 남음)', COUNT(*) FROM review_reasons
    WHERE review_id IN (SELECT id FROM dummy_reviews)
UNION ALL SELECT 'analysis_feedbacks (대상 남음)', COUNT(*) FROM analysis_feedbacks
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'review_feedbacks (대상 남음)', COUNT(*) FROM review_feedbacks
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'reports (대상 남음)', COUNT(*) FROM reports
    WHERE review_id IN (SELECT id FROM dummy_reviews) OR product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'wishlists (대상 남음)', COUNT(*) FROM wishlists WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'cart_items (대상 남음)', COUNT(*) FROM cart_items WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'cart_events (대상 남음)', COUNT(*) FROM cart_events WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'view_histories (대상 남음)', COUNT(*) FROM view_histories
    WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'product_platform_links (대상 남음)', COUNT(*) FROM product_platform_links
    WHERE product_id IN (SELECT id FROM dummy_products)
UNION ALL SELECT 'users (전체)', COUNT(*) FROM users;

ROLLBACK;
