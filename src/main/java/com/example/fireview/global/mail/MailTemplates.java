package com.example.fireview.global.mail;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

/**
 * 이메일 본문 템플릿 모음.
 * HTML 문자열을 반환하는 정적 팩토리 메서드로 구성된다.
 *
 * 끼워 넣는 값은 모두 HTML 이스케이프한다(#235). 관리자 코멘트처럼 사람이 쓴 문자열에
 * 태그가 들어 있으면 메일 본문 구조가 깨지거나 엉뚱한 링크가 들어간다.
 */
public final class MailTemplates {

    private MailTemplates() {}

    /**
     * 비밀번호 재설정 이메일 HTML 본문 생성.
     *
     * @param resetUrl 비밀번호 재설정 페이지 URL (토큰 포함)
     * @return HTML 문자열
     */
    public static String passwordReset(String resetUrl) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background:#f9f9f9; padding:40px;">
                  <div style="max-width:520px; margin:0 auto; background:#fff;
                              border-radius:8px; padding:40px; box-shadow:0 2px 8px rgba(0,0,0,.08);">
                    <h2 style="color:#333; margin-top:0;">비밀번호 재설정</h2>
                    <p style="color:#555; line-height:1.6;">
                      안녕하세요.<br>
                      아래 버튼을 클릭하면 새 비밀번호를 설정할 수 있습니다.<br>
                      이 링크는 <strong>15분</strong> 동안만 유효합니다.
                    </p>
                    <a href="%s"
                       style="display:inline-block; margin-top:24px; padding:14px 32px;
                              background:#4A90E2; color:#fff; text-decoration:none;
                              border-radius:6px; font-size:15px; font-weight:bold;">
                      비밀번호 재설정하기
                    </a>
                    <p style="margin-top:32px; font-size:12px; color:#aaa;">
                      본인이 요청하지 않은 경우 이 이메일을 무시하세요.<br>
                      링크는 한 번만 사용 가능합니다.
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(htmlEscape(resetUrl));
    }

    /**
     * 분석 피드백 처리 결과 알림 이메일 HTML 본문.
     *
     * @param status  처리 상태 (검토 중 / 반영 완료 / 반려)
     * @param detail  관리자 코멘트
     * @param myPageUrl 마이페이지 URL
     */
    public static String analysisFeedbackResult(String status, String detail, String myPageUrl) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background:#f9f9f9; padding:40px;">
                  <div style="max-width:520px; margin:0 auto; background:#fff;
                              border-radius:8px; padding:40px; box-shadow:0 2px 8px rgba(0,0,0,.08);">
                    <h2 style="color:#333; margin-top:0;">피드백 처리 결과 안내</h2>
                    <p style="color:#555; line-height:1.6;">
                      제출하신 AI 분석 피드백이 <strong>%s</strong> 되었습니다.
                    </p>
                    %s
                    <a href="%s"
                       style="display:inline-block; margin-top:24px; padding:14px 32px;
                              background:#4A90E2; color:#fff; text-decoration:none;
                              border-radius:6px; font-size:15px; font-weight:bold;">
                      내 피드백 확인하기
                    </a>
                  </div>
                </body>
                </html>
                """.formatted(
                htmlEscape(status),
                detail != null && !detail.isBlank()
                        ? "<p style=\"color:#555;\"><strong>관리자 코멘트:</strong> " + htmlEscape(detail) + "</p>"
                        : "",
                htmlEscape(myPageUrl)
        );
    }
}
