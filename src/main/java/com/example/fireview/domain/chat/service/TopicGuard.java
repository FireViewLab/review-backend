package com.example.fireview.domain.chat.service;

import com.example.fireview.domain.chat.port.ProductAnalysisContext;
import com.example.fireview.domain.review.entity.TrustGrade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 챗봇 세이프가드.
 *
 * 4계층 중 1계층(입력 검증)과 4계층(출력 검증)을 담당한다.
 * 2계층(컨텍스트 격리)과 3계층(주제 판정)은 PromptAssembler 의 시스템 프롬프트가 맡는다.
 *
 * <p><b>주제 판정을 왜 규칙으로 하지 않는가</b><br>
 * "상품/리뷰 키워드가 없으면 차단" 같은 규칙은 "이거 살까 말까?", "믿을 만해?" 처럼
 * 주제 안에 있지만 키워드가 없는 질문을 오탐한다. 반대로 "파이썬으로 리뷰 크롤러 짜줘"는
 * 키워드가 있지만 주제 밖이다. 그래서 주제 판정은 LLM 에 맡기고,
 * 여기서는 규칙으로 확실히 판단되는 것만 막는다.
 */
@Slf4j
@Component
public class TopicGuard {

    public static final int MAX_QUESTION_LENGTH = 500;

    /**
     * 프롬프트 인젝션 패턴.
     * 정상적인 쇼핑 관련 질문에는 거의 나타나지 않는 표현만 골랐다.
     * 리뷰 본문에 심어진 지시문과 사용자가 직접 넣는 지시문을 모두 겨냥한다.
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("이전\\s*(의)?\\s*(지시|명령|규칙|프롬프트)"),
            Pattern.compile("(지시|명령|규칙|프롬프트|설정)\\s*(사항)?\\s*(을|를)?\\s*(무시|잊)"),
            Pattern.compile("(너는|당신은)\\s*이제"),
            Pattern.compile("역할\\s*(을|를)?\\s*(변경|바꿔|무시)"),
            Pattern.compile("시스템\\s*(프롬프트|메시지|지침)"),
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+"),
            Pattern.compile("(?i)system\\s*prompt"),
            Pattern.compile("(?i)\\b(jailbreak|DAN\\s+mode|developer\\s+mode)\\b"),
            Pattern.compile("(?i)^\\s*(system|assistant)\\s*[:：]"),
            Pattern.compile("(?i)<\\s*/?\\s*(system|assistant|instructions?)\\s*>")
    );

    /** 응답에서 숫자 형태의 RTI/점수 표현을 찾아낸다. 예: "RTI 87", "신뢰도 92.5점" */
    private static final Pattern SCORE_MENTION =
            Pattern.compile("(?i)(RTI|신뢰도\\s*(지수|점수)?|점수)\\s*(는|은|가|이)?\\s*:?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*(점|%)?");

    /**
     * 점수가 아니라 척도를 말하는 표현. "100점 만점에", "0~100 사이" 의 0·100 을 점수로 읽어
     * 막던 오탐을 없앤다(#219). 대조 전에 지운다.
     */
    private static final Pattern SCALE_EXPRESSION = Pattern.compile(
            "\\d{1,3}\\s*점?\\s*만점|0\\s*(?:~|-|–|에서|부터)\\s*100");

    /** 등급 경계. 답변에 기준으로 나와도 근거 있는 수치다 */
    private static final List<Double> GRADE_THRESHOLDS = List.of(
            TrustGrade.SAFE_MIN_SCORE,
            TrustGrade.SUSPICIOUS_MIN_SCORE);

    public static final String UNGROUNDED_SCORE = "UNGROUNDED_SCORE";

    /** 허용 오차. 소수점 표기 차이(87 vs 87.0)를 흡수한다. */
    private static final double SCORE_TOLERANCE = 0.5;

    public record Verdict(boolean allowed, String reason, String userMessage) {

        public static Verdict allow() {
            return new Verdict(true, null, null);
        }

        public static Verdict block(String reason, String userMessage) {
            return new Verdict(false, reason, userMessage);
        }
    }

    // ────────────────────────────── 1계층: 입력 검증 ──────────────────────────────

    /**
     * 사용자 질문을 LLM 에 보내기 전에 검사한다.
     * 여기서 막히면 토큰을 전혀 쓰지 않는다.
     */
    public Verdict inspectQuestion(String question) {
        if (question == null || question.isBlank()) {
            return Verdict.block("EMPTY", "질문을 입력해주세요.");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            return Verdict.block("TOO_LONG",
                    "질문이 너무 깁니다. " + MAX_QUESTION_LENGTH + "자 이내로 줄여주세요.");
        }
        String matched = findInjectionPattern(question);
        if (matched != null) {
            log.warn("[TopicGuard] 인젝션 패턴 차단 - pattern={}", matched);
            return Verdict.block("INJECTION",
                    "죄송합니다. 요청을 처리할 수 없습니다. 상품이나 리뷰에 대해 질문해주세요.");
        }
        return Verdict.allow();
    }

    /** 리뷰 본문에 지시문이 섞여 있는지 확인한다. 걸리면 프롬프트에서 제외한다. */
    public boolean containsInjection(String text) {
        return findInjectionPattern(text) != null;
    }

    private String findInjectionPattern(String text) {
        if (text == null) return null;
        String normalized = text.replace('​', ' ').toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(normalized).find()) {
                return p.pattern();
            }
        }
        return null;
    }

    // ────────────────────────────── 4계층: 출력 검증 ──────────────────────────────

    /**
     * 응답에 등장한 RTI/신뢰도 수치가 실제 데이터에 있는 값인지 대조한다.
     *
     * 프롬프트 인젝션이 성공했거나 모델이 환각을 일으키면 실제와 다른 점수를 말할 수 있다.
     * 수치는 LLM 이 아니라 데이터가 근거이므로, 근거 없는 숫자가 나오면 응답을 막는다.
     *
     * @param answer  LLM 응답 본문
     * @param context 이 대화에 실제로 제공된 분석 컨텍스트 (null 이면 검증 생략)
     */
    public Verdict inspectAnswer(String answer, ProductAnalysisContext context) {
        if (answer == null || answer.isBlank()) {
            return Verdict.block("EMPTY_ANSWER", "답변을 생성하지 못했습니다. 다시 시도해주세요.");
        }
        if (findUngroundedScore(answer, context).isPresent()) {
            return Verdict.block(UNGROUNDED_SCORE,
                    "답변에 확인되지 않은 수치가 포함되어 표시하지 않았습니다. 다시 질문해주세요.");
        }
        return Verdict.allow();
    }

    /**
     * 데이터에 없는 첫 점수. 없으면 비어 있다. 컨텍스트가 없으면 대조하지 않는다.
     * 다시 쓰게 할 때 어느 수치가 문제였는지 알려 주려고 따로 둔다.
     */
    public Optional<Double> findUngroundedScore(String answer, ProductAnalysisContext context) {
        if (answer == null || context == null) {
            return Optional.empty();
        }
        List<Double> known = new ArrayList<>(context.knownRtiValues());
        // 등급 기준값. 모델이 "70점 이상이면 안전"처럼 기준을 설명하면 데이터에 없는 점수로 보고 막았다(#217)
        known.addAll(GRADE_THRESHOLDS);

        Matcher m = SCORE_MENTION.matcher(SCALE_EXPRESSION.matcher(answer).replaceAll(" "));
        while (m.find()) {
            double mentioned = Double.parseDouble(m.group(4));
            boolean grounded = known.stream().anyMatch(k -> Math.abs(k - mentioned) <= SCORE_TOLERANCE);
            if (!grounded) {
                log.warn("[TopicGuard] 근거 없는 수치 - mentioned={}, known={}, at=\"{}\"",
                        mentioned, known, m.group());
                return Optional.of(mentioned);
            }
        }
        return Optional.empty();
    }
}
