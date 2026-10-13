-- PostgreSQL: Hibernate 가 @Enumerated(STRING) 칼럼에 붙인 enum CHECK 제약을 지운다.
--
-- 배경
--   Hibernate 6 은 @Enumerated(STRING) 칼럼을 만들 때 CHECK (col IN (...enum 값...)) 를 함께 만든다.
--   운영은 ddl-auto=update 라 칼럼이 이미 있으면 이 제약을 다시 만들지 않는다. 그래서 enum 에 값을
--   더해 배포하면 새 값 저장이 DB 에서 거부된다(500). #205 의 products.analysis_status 가 그 사례다.
--   허용 값은 Java enum 이 이미 지키므로 DB 쪽 CHECK 는 지운다.
--
-- 대상
--   현재 스키마(search_path 의 첫 스키마)에서 아래 두 조건을 모두 만족하는 CHECK 만 지운다.
--   1) 이름이 Hibernate 규칙 <테이블>_<칼럼>_check 이다.
--   2) 정의가 칼럼 하나에 대한 (col)::text = ANY (ARRAY[...]) 형태다. col IN (...) 으로 만든 제약도
--      Postgres 가 이 형태로 저장한다.
--   그 밖의 CHECK(사람이 만든 제약 등)는 목록에 "남김"으로만 보여 주고 건드리지 않는다.
--   로컬 리허설(현재 엔티티로 만든 스키마) 기준 대상은 14개다. 운영에는 #205 이전의
--   products_analysis_status_check 가 남아 있으면 15개가 나온다.
--
-- 실행 순서
--   1) 이 원본(마지막 문장 ROLLBACK)을 그대로 실행해 대상 목록과 "실행 후" 결과를 본다.
--      psql -X -v ON_ERROR_STOP=1 -f docs/sql/drop-enum-check-constraints.sql
--   2) 대상이 예상과 같으면 마지막 문장만 COMMIT 으로 바꾼 사본을 처음부터 다시 실행한다.
--   여러 번 실행해도 된다. 지울 것이 없으면 0건으로 끝난다.
--
-- 배포 순서와 무관하다. CHECK 를 지우는 것은 옛 코드·새 코드 모두에 영향이 없다.
-- 배포 후 ddl-auto=update 는 기존 칼럼에 CHECK 를 다시 붙이지 않는다(로컬 리허설로 확인).
-- 다만 새 테이블·새 enum 칼럼은 생성 시점 값으로 CHECK 가 다시 생긴다. 그 enum 에 값을 더할 때 이 파일을 다시 실행한다.
--
-- DROP CONSTRAINT 는 행을 다시 쓰지 않지만 테이블마다 ACCESS EXCLUSIVE 잠금을 잠깐 잡는다.
-- 긴 트랜잭션이 있으면 lock_timeout 으로 실패하고 전체가 롤백된다. 잠시 뒤 파일 전체를 다시 실행한다.
--
-- 되돌리기: 맨 아래 "되돌리기용 DDL" 출력을 보관해 두면 그대로 실행해 복원할 수 있다.
--   단 지운 뒤 enum 에 없는 값이 들어갔다면 복원 전에 그 행을 먼저 정리해야 한다.
--
-- 기본은 ROLLBACK이다. 검토 후 실제 반영할 때만 마지막 문장을 COMMIT으로 바꾼다.
BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- 접속 대상 확인. 이 스키마의 테이블만 본다.
SELECT current_database() AS db, current_user AS usr, current_setting('search_path') AS search_path,
       current_schema() AS target_schema;

CREATE TEMP TABLE enum_check_candidates ON COMMIT DROP AS
SELECT c.oid,
       t.relname  AS table_name,
       a.attname  AS column_name,
       c.conname,
       pg_get_constraintdef(c.oid) AS definition,
       (c.conname = t.relname || '_' || a.attname || '_check'
        AND pg_get_constraintdef(c.oid) ~ ('^CHECK \(\(\(' || a.attname || '\)::text = ANY \(+ARRAY\[.*\]\)+$'))
           AS is_target
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
LEFT JOIN pg_attribute a ON a.attrelid = c.conrelid AND array_length(c.conkey, 1) = 1 AND a.attnum = c.conkey[1]
WHERE c.contype = 'c'
  AND n.nspname = current_schema();

-- 실행 전: 대상과 남기는 CHECK
SELECT CASE WHEN is_target THEN '삭제' ELSE '남김' END AS action, table_name, conname, definition
FROM enum_check_candidates
ORDER BY is_target DESC, table_name, conname;

SELECT count(*) FILTER (WHERE is_target) AS targets, count(*) FILTER (WHERE NOT is_target) AS kept
FROM enum_check_candidates;

-- 되돌리기용 DDL. COMMIT 전에 이 출력을 보관한다.
SELECT format('ALTER TABLE %I ADD CONSTRAINT %I %s;', table_name, conname, definition) AS restore_ddl
FROM enum_check_candidates
WHERE is_target
ORDER BY table_name, conname;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT table_name, conname FROM enum_check_candidates WHERE is_target ORDER BY table_name, conname LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.table_name, r.conname);
        RAISE NOTICE '삭제: %.%', r.table_name, r.conname;
    END LOOP;
END $$;

-- 실행 후: 대상이 남아 있으면 0이 아니다
SELECT count(*) AS remaining_targets
FROM enum_check_candidates e
JOIN pg_constraint c ON c.oid = e.oid
WHERE e.is_target;

ROLLBACK;
