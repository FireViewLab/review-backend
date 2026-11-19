package com.example.fireview.domain.dashboard.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 검색어는 최근 7일 검색 횟수로 순위를 낸다(#247).
 */
class SearchKeywordCounterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T03:00:00Z"));
    private final SearchKeywordCounter counter = new SearchKeywordCounter(clock);

    private void search(String keyword, int times) {
        for (int i = 0; i < times; i++) counter.record(keyword);
    }

    @Test
    void 많이_검색한_순서로_돌려준다() {
        search("에어팟", 3);
        search("나이키", 5);
        search("홍삼", 1);

        assertThat(counter.top(10)).containsExactly("나이키", "에어팟", "홍삼");
    }

    @Test
    void 상위_개수만큼만_돌려준다() {
        search("a", 3);
        search("b", 2);
        search("c", 1);

        assertThat(counter.top(2)).containsExactly("a", "b");
    }

    @Test
    void 횟수가_같으면_가나다순이다() {
        search("홍삼", 2);
        search("가방", 2);

        assertThat(counter.top(10)).containsExactly("가방", "홍삼");
    }

    @Test
    void 칠일이_지난_검색은_순위에서_빠진다() {
        search("지난주", 10);
        clock.plusDays(7);
        search("오늘", 1);

        assertThat(counter.top(10)).containsExactly("오늘");
    }

    @Test
    void 여섯일_전_검색은_아직_센다() {
        search("엿새전", 2);
        clock.plusDays(6);
        search("오늘", 1);

        assertThat(counter.top(10)).containsExactly("엿새전", "오늘");
    }

    @Test
    void 공백과_영문_대소문자를_맞춰_같은_검색어로_센다() {
        counter.record("Nike");
        counter.record("  nike ");
        counter.record("갤럭시   s25");
        counter.record("갤럭시 S25");

        assertThat(counter.top(10)).containsExactlyInAnyOrder("nike", "갤럭시 s25");
    }

    @Test
    void 비었거나_너무_긴_검색어는_세지_않는다() {
        counter.record(null);
        counter.record("   ");
        counter.record("가".repeat(SearchKeywordCounter.MAX_LENGTH + 1));
        counter.record("가".repeat(SearchKeywordCounter.MAX_LENGTH));

        assertThat(counter.top(10)).containsExactly("가".repeat(SearchKeywordCounter.MAX_LENGTH));
    }

    @Test
    void 하루_기준은_서울_자정이다() {
        // UTC 14:59 = 서울 23:59, UTC 15:00 = 서울 다음날 00:00
        clock.set(Instant.parse("2026-11-12T14:59:00Z"));
        search("경계", 1);
        clock.set(Instant.parse("2026-11-19T15:00:00Z"));

        assertThat(counter.top(10)).isEmpty();
    }

    /** 테스트에서 날짜를 옮길 수 있는 시계 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void set(Instant now) { this.now = now; }

        void plusDays(int days) { now = now.plusSeconds(days * 86_400L); }

        @Override public ZoneId getZone() { return SEOUL; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
