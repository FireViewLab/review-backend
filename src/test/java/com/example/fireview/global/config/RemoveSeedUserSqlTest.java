package com.example.fireview.global.config;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/sql/remove-seed-user-account.sql 이 사용자를 가리키는 테이블을 빠짐없이, 자식부터 지우는지 확인한다.
 *
 * <p>스크립트는 PL/pgSQL DO 블록이라 H2 에서 돌릴 수 없다. 엔티티가 만드는 FK 를 읽어 {@code users} 에
 * (직접 또는 다른 테이블을 거쳐) 매달린 테이블을 모으고, 각 FK 칼럼으로 지우는 DELETE 가 있는지와
 * 부모보다 먼저 오는지를 본다. 담기 이력·예약이 생긴 뒤 스크립트가 따라오지 않아 이 검사를 둔다.
 */
@DataJpaTest
@ActiveProfiles("test")
class RemoveSeedUserSqlTest {

    private static final Path SQL = Path.of("docs/sql/remove-seed-user-account.sql");

    @Autowired EntityManager em;

    @Test
    void 사용자에_매달린_모든_테이블을_자식부터_지운다() throws IOException {
        String sql = Files.readString(SQL, StandardCharsets.UTF_8).toLowerCase();
        @SuppressWarnings("unchecked")
        List<Object[]> fks = em.createNativeQuery("""
                SELECT LOWER(fk.TABLE_NAME), LOWER(fk.COLUMN_NAME), LOWER(pk.TABLE_NAME)
                FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE fk
                  ON fk.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA AND fk.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE pk
                  ON pk.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA
                 AND pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
                 AND pk.ORDINAL_POSITION = fk.POSITION_IN_UNIQUE_CONSTRAINT
                """).getResultList();

        // users 에 직접 또는 다른 테이블을 거쳐 매달린 테이블
        Set<String> doomed = new HashSet<>(Set.of("users"));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Object[] fk : fks) {
                if (doomed.contains((String) fk[2]) && doomed.add((String) fk[0])) grew = true;
            }
        }

        for (Object[] fk : fks) {
            String child = (String) fk[0], column = (String) fk[1], parent = (String) fk[2];
            if (!doomed.contains(parent)) continue;
            String delete = "delete from " + child + " where " + column;
            assertThat(sql).as("%s.%s -> %s 를 지우는 DELETE", child, column, parent).contains(delete);
            assertThat(sql.indexOf(delete)).as("%s 를 %s 보다 먼저 지운다", child, parent)
                    .isLessThan(sql.indexOf("delete from " + parent + " where"));
        }
    }
}
