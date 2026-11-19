-- PostgreSQL: 쓰지 않게 된 인기 검색어 누적 테이블 search_keywords 를 지운다(#247).
-- 인기 검색어는 이제 Redis 날짜별 집계(search:keywords:yyyy-MM-dd)로 센다.
-- ddl-auto=update 는 엔티티를 지워도 테이블을 남기므로 수동으로 정리한다.
-- 이 테이블을 참조하는 FK 는 없다. 실행 전 백업하고 아래 조회 결과를 확인한다.
-- 새 버전이 배포된 뒤에 실행한다. 이전 버전이 떠 있는 동안 지우면 그 버전의 대시보드가 500 이 된다.
-- 기본은 ROLLBACK이다. 검토 후 실제 반영할 때만 마지막 문장을 COMMIT으로 바꾼다.
BEGIN;

SELECT keyword, search_count, updated_at
FROM search_keywords
ORDER BY search_count DESC
LIMIT 20;

DROP TABLE IF EXISTS search_keywords;

ROLLBACK;
