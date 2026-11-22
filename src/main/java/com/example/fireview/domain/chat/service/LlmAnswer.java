package com.example.fireview.domain.chat.service;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 이 돌려준 구조화 응답.
 *
 * 시스템 프롬프트가 아래 형식을 요구한다.
 * <pre>
 * ONTOPIC: yes
 * RECOMMEND: no
 * ---
 * 답변 본문
 * </pre>
 *
 * <p>{@code RECOMMEND} 줄은 선택이다. 없거나 값을 알아볼 수 없으면 추천하지 않는다.
 * 모델은 추천이 필요한지만 판단하고, 어떤 상품을 보여줄지는 서버가 DB 에서 고른다.
 *
 * <p><b>JSON 이 아니라 구분자를 쓰는 이유</b><br>
 * JSON 으로 받으면 모델이 본문에 큰따옴표를 이스케이프 없이 넣는 순간 파싱이 깨진다.
 * 실제 게이트웨이 호출에서 간헐적으로 재현됐고, 실패할 때마다 호출 1회분 토큰이 버려진다.
 * 이스케이프 규칙을 더 강하게 지시하는 방식은 신뢰할 수 없어서,
 * 이스케이프 자체가 필요 없는 형식으로 바꿨다. 본문에 따옴표·줄바꿈·마크다운이 와도 안전하다.
 *
 * <p>형식을 벗어난 응답은 주제 판정을 통과시키지 않는다(fail-closed).
 * 형식을 어긴 응답을 그대로 노출하면 세이프가드 3계층을 우회하는 통로가 되기 때문이다.
 */
@Slf4j
public record LlmAnswer(boolean onTopic, String answer, boolean recommend, String scheduleValue) {

    /** 모델이 형식을 어겼을 때 사용자에게 보여줄 문구 */
    private static final String MALFORMED_FALLBACK =
            "답변을 정리하지 못했습니다. 상품이나 리뷰에 대해 다시 질문해주세요.";

    private static final Pattern HEADER =
            Pattern.compile("^\\s*ONTOPIC\\s*[:：]\\s*(yes|no|true|false)\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** ONTOPIC 다음 줄에 올 수 있는 추천 요청 줄. 값은 무엇이든 받아 두고 yes/true 만 인정한다 */
    private static final Pattern RECOMMEND_LINE =
            Pattern.compile("^\\s*RECOMMEND\\s*[:：]\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 장바구니 예약 요청 줄. 값은 {@code ScheduleRequest} 가 엄격하게 검사한다. 여기서는 받아 두기만 한다.
     * RECOMMEND 줄과 순서가 바뀌어도 읽는다.
     */
    private static final Pattern SCHEDULE_LINE =
            Pattern.compile("^\\s*SCHEDULE\\s*[:：]\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** 헤더 다음에 오는 구분자 줄 */
    private static final Pattern SEPARATOR = Pattern.compile("^\\s*-{3,}\\s*$", Pattern.MULTILINE);

    public static LlmAnswer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return malformed();
        }
        String text = stripCodeFence(raw);

        Matcher header = HEADER.matcher(text);
        if (!header.find()) {
            log.warn("[Chat] ONTOPIC 헤더가 없는 응답. 길이={}", text.length());
            return malformed();
        }
        String flag = header.group(1).toLowerCase();
        boolean onTopic = flag.equals("yes") || flag.equals("true");

        String rest = text.substring(header.end());
        Boolean recommendFlag = null;
        String schedule = null;
        // 헤더 바로 다음 두 줄까지는 RECOMMEND·SCHEDULE 줄일 수 있다. 순서는 가리지 않는다
        for (int i = 0; i < 2; i++) {
            int start = firstContentLineStart(rest);
            if (start < 0) break;
            String line = lineAt(rest, start);
            Matcher rec = RECOMMEND_LINE.matcher(line);
            Matcher sch = SCHEDULE_LINE.matcher(line);
            if (recommendFlag == null && rec.matches()) {
                String value = rec.group(1).toLowerCase();
                recommendFlag = value.equals("yes") || value.equals("true");
            } else if (schedule == null && sch.matches()) {
                schedule = sch.group(1).isBlank() ? null : sch.group(1);
            } else {
                break;
            }
            rest = rest.substring(nextLineStart(rest, start));
        }
        boolean recommend = recommendFlag != null && recommendFlag;

        String body = removeHeaderLines(extractBody(rest));
        if (body.isBlank()) {
            log.warn("[Chat] 본문이 비어 있는 응답");
            return malformed();
        }
        return new LlmAnswer(onTopic, body, recommend, schedule);
    }

    /** 주제 안 답변이고 모델이 장바구니 예약 요청을 옮겨 적었는지. 값이 맞는지는 호출자가 검사한다 */
    public boolean wantsSchedule() {
        return onTopic && scheduleValue != null && !scheduleValue.equalsIgnoreCase("none");
    }

    /** 주제 안 답변이고 모델이 비슷한 상품 추천을 원한다고 판단했는지 */
    public boolean wantsRecommendations() {
        return onTopic && recommend;
    }

    private static LlmAnswer malformed() {
        return new LlmAnswer(false, MALFORMED_FALLBACK, false, null);
    }

    /**
     * 본문에 남은 RECOMMEND·SCHEDULE 줄을 지운다.
     * 모델이 구분자 아래나 본문 끝에 줄을 잘못 두면 사용자에게 그대로 보이기 때문이다.
     * 이렇게 지운 줄은 추천·예약 판단에 쓰지 않는다(정해진 자리에 둔 줄만 인정한다).
     */
    private static String removeHeaderLines(String body) {
        StringBuilder kept = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (RECOMMEND_LINE.matcher(line).matches() || SCHEDULE_LINE.matcher(line).matches()) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return kept.toString().strip();
    }

    /** 헤더 줄 끝 다음부터 처음으로 내용이 있는 줄의 시작 위치. 없으면 -1 */
    private static int firstContentLineStart(String rest) {
        int pos = rest.startsWith("\n") ? 1 : (rest.startsWith("\r\n") ? 2 : 0);
        while (pos < rest.length()) {
            String line = lineAt(rest, pos);
            if (!line.isBlank()) {
                return pos;
            }
            pos = nextLineStart(rest, pos);
        }
        return -1;
    }

    private static String lineAt(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static int nextLineStart(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end + 1;
    }

    /**
     * 헤더 뒤의 본문을 뽑는다.
     * 구분자가 있으면 그 다음부터, 없으면 헤더 다음 줄부터를 본문으로 본다.
     * 모델이 구분자를 빠뜨리는 경우가 형식 위반 중 가장 흔하고 피해도 없어서 허용한다.
     */
    private static String extractBody(String rest) {
        Matcher sep = SEPARATOR.matcher(rest);
        String body = sep.find() ? rest.substring(sep.end()) : rest;
        return body.strip();
    }

    /** ```로 감싼 응답에서 펜스를 걷어낸다. */
    private static String stripCodeFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String inner = text.substring(firstLineEnd + 1);
        int closing = inner.lastIndexOf("```");
        return (closing >= 0 ? inner.substring(0, closing) : inner).strip();
    }
}
