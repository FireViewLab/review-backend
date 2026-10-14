# 세션 인수인계 자료
> 최종 업데이트: 2026-06-12 | 프로젝트: FireView (review-backend)

---

## 🖥️ 인프라 정보

> ⚠️ **자격 증명은 이 문서에 적지 않습니다.**
> DB 비밀번호, API 키 등은 EC2 의 `~/review-backend/.env.prod` 에만 둡니다.
> 문서에 적으면 git 이력에 영구히 남아 삭제해도 복구됩니다.

| 항목 | 값 |
|------|-----|
| 백엔드 도메인 | `https://api.re-view.kr` |
| 프론트엔드 도메인 | `https://re-view.kr` (Vercel) |
| API 문서 | `https://api.re-view.kr/swagger-ui.html` |
| EC2 | `ssh fireview` (별칭 등록 시) / Amazon Linux 2023, t3.small |
| AI 서버 (Azure) | `http://20.249.211.171:8000` |
| DB | PostgreSQL RDS — 엔드포인트와 계정은 `.env.prod` 의 `DB_URL`, `DB_USERNAME` 참고 |
| Docker 컨테이너 | `fireview` |
| nginx | EC2에서 실행 중 (443 → 8080 프록시, Let's Encrypt 인증서 자동 갱신) |
| Redis | EC2 호스트에서 실행 중 (`redis6`, 포트 6379) |
| 컨테이너→Redis | `172.17.0.1` (Docker 브리지 게이트웨이). `localhost` 는 컨테이너 자신을 가리켜 닿지 않음 |

### EC2 서버 관리 명령어
```bash
# 로그 확인
docker logs --tail 50 fireview
docker logs --tail 50 fireview 2>&1 | grep -E "ERROR|Exception"

# OAuth2 쿠키 저장소 디버그 로그 확인
docker logs -f fireview 2>&1 | grep "CookieOAuth2Repo"

# 배포 완료 확인 (Started FireViewApplication 뜨면 완료)
docker logs --tail 5 fireview

# Redis 상태 확인
redis6-cli ping
redis6-cli keys "naver:product:*"

# DB 접속 (자격 증명은 .env.prod 에서 읽는다)
set -a && . ~/review-backend/.env.prod && set +a
psql "$(echo "$DB_URL" | sed 's|jdbc:||')" -U "$DB_USERNAME"
```

---

## 🔄 배포 플로우

```
브랜치 생성 → 작업(커밋 최대한 분리) → PR → main 머지(Merge commit) → GitHub Actions 자동 배포
```
- **main 직접 커밋 금지**
- PR 머지 시 **Squash 아닌 Merge commit** 사용 → 커밋 수 보존
- GitHub Actions CI/CD로 머지 즉시 자동 배포됨

---

## ✅ 이전 세션 완료 PR 목록 (~ 2026-06-02)

| PR | 내용 |
|----|------|
| #57 | 검색 결과 부족 개선 (멀티페이지 + DB 병합) |
| #58 | `/api/products?keyword` DB만 조회 문제 수정 |
| #59~#62 | 검색 500 오류 (LazyInit → JOIN FETCH 근본 해결) |
| #63~#64 | 비로그인 401 전역 포맷 통일 |
| #65 | AI 서버 타임아웃 + 병렬 호출 (CompletableFuture) |
| #66 | 네이버 상품에 productUrl 추가 |
| #67 | naverProductId 매핑으로 AI 분석 결과 DB 업데이트 활성화 |
| #68 | 대분류/중분류/소분류 3계층 카테고리 구조 도입 |
| #70 | NaverProductCache 인메모리 → Redis 전환 (TTL 24시간) |
| #71 | Docker 컨테이너 Redis 호스트 접근 문제 수정 (localhost → EC2 내부 IP) |
| #72 | Redis 역직렬화 오류 수정 (String 기반 JSON 직렬화로 교체) |
| #83 | AI review_id를 DB PK로 쓰던 충돌 버그 수정 |
| #84 | 실사용/광고성/반복표현 비율 통계 필드 추가 |
| #85 | 주요 판단 신호(trustSignals) 계산 및 응답 추가 |
| #86 | 네이버 API productId null 시 fallback ID로 서버 기동 오류 수정 |

---

## ✅ 이번 세션(2026-06-05 ~ 2026-06-11)에서 완료한 PR 목록

| PR | 브랜치 | 내용 |
|----|--------|------|
| #87~#95 | `fix/oauth2-*` | OAuth2 로그인 실패 원인 추적 및 다양한 수정 시도 |
| #101 | `fix/oauth2-cookie-storage` | **OAuth2 쿠키 기반 state 저장으로 전환** (세션 의존 완전 제거) |
| #102 | `debug/oauth2-cookie-trace` | OAuth2 쿠키 저장소 상세 로그 추가 (원인 추적용) |
| #103 | `feat/report-system` | **리뷰 신고 시스템 구현** (8개 커밋) |
| #104 | `feat/feedback-history` | **피드백 내역 조회 구현** (6개 커밋) |
| #105 | `feat/notification-system` | **알림 시스템 구현** (8개 커밋) |
| #106 | `feat/mypage-api` | **마이페이지 API 구현** (7개 커밋) |
| #107 | `docs/session-handover-update` | session-handover.md 업데이트 |
| #108 | `feat/settings-admin-api` | **설정 API + 관리자 API 구현** (27개 커밋) |
| #109 | `feat/full-feature-expansion` | **피그마 기반 전체 기능 확장** — 분석 피드백·통합 피드백·관리자 대시보드·신고 개선 (39개 커밋) |
| #110 | `feat/full-feature-expansion` | `GET /api/users/me/feedback` 엔드포인트 추가 |
| #124 | `feat/full-feature-expansion` | **이슈 #119 #120** — NotificationType 분리 + 모델 성능 모니터링 API (8개 커밋) |
| #126 | `fix/oauth2-callback-params` | **OAuth2 콜백 파라미터 전면 수정** — Fragment→Query Param, 파라미터 전달 규격 통일 (6개 커밋) |
| #127 | `feat/fix-functional-gaps` | **기능 구멍 수정** — 이메일 발송·알림 설정 연동·Redis TokenStore·DataInitializer prod 차단 (20개 커밋) |

---

## 🐛 이번 세션에서 해결한 주요 이슈

### 1. OAuth2 로그인 반복 실패 (PR #101)
- **확정된 원인**: JSESSIONID는 Tomcat 내부 `CoyoteResponse`로 직접 설정됨 → 서블릿 필터(`SameSiteCookieFilter.addCookie()`)가 가로챌 수 없음 → SameSite=None 미적용 → 네이버 콜백 시 JSESSIONID 전달 안 됨
- **수정**: `HttpCookieOAuth2AuthorizationRequestRepository` 도입. OAuth2 state를 세션 대신 **직접 Set-Cookie 헤더에 쿠키로 저장** → JSESSIONID 의존 완전 제거
- **핵심 코드**: `response.addHeader("Set-Cookie", "oauth2_auth_request=...; SameSite=None; Secure")`

### 2. OAuth2 콜백 파라미터 불일치 (PR #126으로 해결)
- **확인된 원인 1**: 백엔드가 URL Fragment(`#token=...`)로 전달 → 프론트 `queryParams`로 파싱 불가 → `accessToken` 항상 null
- **확인된 원인 2**: 파라미터 이름 불일치 (`token` vs `accessToken`), `email`/`nickname`/`tokenType` 미전달
- **확인된 원인 3**: 실패 시 `/login?error=oauth2`로 리다이렉트 → `OAuthCallbackPage`가 에러 처리 못함
- **수정**: `OAuth2SuccessHandler` — Fragment → Query Param, 파라미터 5개 완전 통일 / `SecurityConfig` 실패 핸들러 — 콜백 URL에 `?error=` 전달로 변경
- **프론트 잔여 수정**: `core/config/app_config.dart`의 `_defaultApiBaseUrl()` 수정 — 프론트 PR #176 에서 처리

---

## 📦 현재 구현된 API 전체 목록

### 인증 (`/api/auth/**`) — permitAll
| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/auth/signup` | 일반 회원가입 |
| POST | `/api/auth/login` | 일반 로그인 |
| GET | `/oauth2/authorization/naver` | 네이버 OAuth2 시작 |
| GET | `/oauth2/authorization/google` | 구글 OAuth2 시작 |

### 상품 / 검색 (`/api/products`, `/api/search`) — permitAll
| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/products/{productId}` | 상품 상세 |
| GET | `/api/products/{productId}/reviews` | 상품 리뷰 목록 |
| GET | `/api/search?keyword=` | 네이버 쇼핑 검색 |
| POST | `/api/analysis/product` | AI 분석 트리거 |

### 마이페이지 (`/api/users/me`) — 인증 필요
| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/users/me` | 프로필 조회 |
| GET | `/api/users/me/stats` | 이용 통계 (찜/피드백/신고/알림 수) |
| PATCH | `/api/users/me` | 프로필 수정 (닉네임, 이미지) |
| DELETE | `/api/users/me` | 회원 탈퇴 |

### 피드백 (`/api/reviews`) — 인증 필요
| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/reviews/{reviewId}/feedback` | 피드백 제출 (REAL/FAKE) |
| GET | `/api/reviews/feedbacks/me` | 내 피드백 목록 (페이징) |
| GET | `/api/reviews/feedbacks/me/{feedbackId}` | 내 피드백 단건 조회 |

### 신고 (`/api/reports`) — 인증 필요
| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/reports/reviews/{reviewId}` | 신고 제출 |
| GET | `/api/reports/me` | 내 신고 목록 (페이징) |
| GET | `/api/reports/me/{reportId}` | 내 신고 단건 조회 |

### 알림 (`/api/notifications`) — 인증 필요
| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/notifications/me` | 내 알림 목록 (페이징) |
| GET | `/api/notifications/me/unread-count` | 읽지 않은 알림 수 |
| PATCH | `/api/notifications/{id}/read` | 단건 읽음 처리 |
| PATCH | `/api/notifications/me/read-all` | 전체 읽음 처리 |

### 찜 / 장바구니 — 인증 필요
| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/wishlist` | 찜 목록 조회 |
| POST | `/api/wishlist/{productId}` | 찜 추가 |
| DELETE | `/api/wishlist/{productId}` | 찜 해제 |
| GET | `/api/cart` | 장바구니 조회 |
| POST | `/api/cart` | 장바구니 추가 |
| DELETE | `/api/cart/{itemId}` | 장바구니 삭제 |

---

## 📊 현재 DB 상태

```
products: 더미 33개(Data 서버 번호표 없음, 과거 DataInitializer 시드) + 번호표가 붙은 실제 상품
reviews: 1117개 (DataInitializer 생성 더미 데이터)
users: 자동 시드 계정 생성 없음 (개발 계정은 회원가입으로 생성)
reports: 테이블 생성됨 (데이터 없음)
notifications: 테이블 생성됨 (데이터 없음)
```

기존 운영 시드 사용자 계정 정리는 `docs/sql/remove-seed-user-account.sql`을 검토 후 수동으로 진행한다.
더미 상품·리뷰 정리는 `docs/sql/remove-dummy-products.sql`을 백업 후 검토하여 수동으로 진행한다(#195). 더미에 붙은 사용자 찜·장바구니·신고·피드백도 함께 지워진다. 실행 후에는 위 products·reviews 서술을 갱신한다.
enum 칼럼의 Hibernate CHECK 제약 정리는 `docs/sql/drop-enum-check-constraints.sql`로 한다. enum 에 값을 더하는 배포 전에 실행한다(#205 와 같은 저장 거부 방지).

### DB 전체 초기화가 필요할 때 순서
```bash
TRUNCATE TABLE notifications, reports, review_feedbacks, review_reasons, reviews,
               product_platform_links, products, users CASCADE;
# 서버 재시작. DataInitializer 는 prod·test 프로파일에서 돌지 않으므로 운영에는 더미가 다시 생기지 않는다
docker restart fireview
```

---

## 🤖 AI 서버 연동 현황

### 연결 상태: ✅ 정상

### AI 서버 엔드포인트 (모두 POST, TriggerRequest 공통 사용)
| 엔드포인트 | 설명 |
|-----------|------|
| `/api/internal/ai/products/product-list` | 상품 RTI 요약 |
| `/api/internal/ai/reviews/product-detail` | 개별 리뷰 분석 (reason code 포함) |
| `/api/internal/ai/products/rti-trend` | 30일 추이 |
| `/api/internal/ai/reviews/report` | 리뷰 상세 리포트 |
| `/api/internal/ai/products/risk-report` | 상품 위험도 리포트 + sample_reviews |

### ⚠️ 중요: AI 서버 데이터 있는 상품
- `53530143052` → AI 서버에 데이터 있음 (테스트용)
- 대부분의 상품은 AI 서버에 데이터 없음 → `product-detail: []` 반환

---

## 💾 Redis 캐시 구조

| 항목 | 값 |
|------|-----|
| 호스트 | `172.31.44.47` (EC2 내부 IP) |
| 포트 | `6379` |
| 키 패턴 | `naver:product:{naverProductId}` |
| TTL | 24시간 |
| 직렬화 | JSON String (ObjectMapper 직접 사용) |

```bash
# 캐시 전체 삭제
redis6-cli keys "naver:product:*" | xargs redis6-cli del
```

---

## 📁 주요 파일 위치

| 파일 | 역할 |
|------|------|
| `domain/auth/oauth2/HttpCookieOAuth2AuthorizationRequestRepository.java` | OAuth2 state 쿠키 저장소 |
| `domain/auth/oauth2/CustomOAuth2UserService.java` | 소셜 로그인 시 신규/기존 유저 처리 |
| `domain/auth/oauth2/OAuth2SuccessHandler.java` | 로그인 성공 시 JWT 발급 + 프론트 리다이렉트 |
| `domain/notification/` | 알림 엔티티 / 서비스 / 컨트롤러 |
| `domain/report/` | 신고 엔티티 / 서비스 / 컨트롤러 |
| `domain/user/service/UserService.java` | 프로필 조회·수정, 이용 통계, 회원 탈퇴 |
| `domain/ai/service/AiAnalysisService.java` | AI 분석 병렬 호출 + DB 동기화 + 분석 완료 알림 |
| `global/mail/EmailService.java` | 이메일 발송 인터페이스 (SMTP/Log 구현체 선택) |
| `global/mail/SmtpEmailService.java` | SMTP 실제 발송 (prod 환경, app.mail.enabled=true) |
| `global/mail/LogEmailService.java` | 콘솔 출력 폴백 (로컬/테스트 환경) |
| `global/mail/MailTemplates.java` | 비밀번호 재설정·피드백 결과 HTML 템플릿 |
| `domain/auth/service/RedisPasswordResetTokenStore.java` | Redis 기반 비밀번호 재설정 토큰 저장 (prod) |
| `domain/notification/util/NotificationSettingChecker.java` | UserSetting 알림 플래그 확인 유틸 |
| `domain/ai/dto/response/ProductAnalysisResponse.java` | 분석 결과 통합 DTO |
| `domain/product/entity/Product.java` | id = naverProductId (수동 할당) |
| `global/config/SecurityConfig.java` | Spring Security 설정, OAuth2 쿠키 저장소 연결 |
| `global/config/TomcatConfig.java` | Rfc6265CookieProcessor (SameSite=None) |
| `global/filter/SameSiteCookieFilter.java` | addCookie/addHeader 쿠키 SameSite=None 보완 |

---

## ⚠️ 남은 이슈 / 다음 세션 작업 후보

### 🔴 [최우선] 프론트 수정 요청 (백엔드 작업 불필요)

1. **OAuth2 로그인 버튼 URL 수정**
   - 네이버: `window.location.href = 'https://re-view.kr/oauth2/authorization/naver'`
   - 구글: `window.location.href = 'https://re-view.kr/oauth2/authorization/google'`

2. **Flutter 비로그인 시 인증 API 호출 차단**
   - 비로그인 상태에서 개인화 API 호출 → 401 처리 못해 앱 크래시
   - `if (!mounted) return;` 추가, `runZonedGuarded` 전역 에러 핸들러

### 🟡 [백엔드 미구현/잔여]

현재 기능 구멍은 PR #127에서 모두 해소됨. 남은 항목:

| 순번 | 기능 | 설명 |
|------|------|------|
| 1 | **EC2 환경변수 추가** | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` 추가 필요 (.env.prod) |
| 2 | **nginx OAuth2 프록시 설정** | `/oauth2/`, `/login/oauth2/` 경로를 nginx가 백엔드로 프록시하도록 추가 필요 |

### 🟠 [기존 미해결 이슈]

| # | 현상 | 원인 | 해결 방향 |
|---|------|------|-----------|
| 1 | 리뷰 인사이트 섹션 빈 칸 | AI 서버 MVP에서 NLP 키워드 추출 미구현 | AI 서버팀에 키워드 추출 API 요청 |
| 2 | reason code 한국어 매핑 불일치 | 프론트 `_codeToDescription()` 테이블 오래된 값 | `frontend-integration-guide.md` 참고하여 프론트 수정 |
| 3 | safe 리뷰 content null | AI risk-report에 warn/danger만 포함 | AI 서버팀에 safe 포함 여부 확인 |
| 4 | 대부분 상품 AI 데이터 없음 | AI 서버에 일부 상품만 데이터 존재 | AI 서버팀에 데이터 수집 범위 확대 요청 |
