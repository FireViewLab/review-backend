# Spring API 명세서 (프론트엔드 연동용)

> 대상: 프론트엔드(Flutter)
> 이 문서는 **Spring 서비스 서버**가 제공하는 API만 다룬다. Data 서버 API는 별도 문서.

## 📌 정확한 스펙은 Swagger 를 보세요

코드에서 자동 생성되는 **항상 최신** 스펙이 있다.
엔드포인트와 스키마의 정확한 정의는 아래가 기준이고, 이 문서는 배경 설명과 연동 주의사항을 담은 보조 자료다.

| 용도 | 주소 |
|------|------|
| 브라우저에서 탐색·직접 호출 | `https://api.re-view.kr/swagger-ui.html` |
| OpenAPI 3 JSON (코드 생성용) | `https://api.re-view.kr/v3/api-docs` |

Swagger UI 우측 상단 **Authorize** 에 로그인 응답의 `accessToken` 을 넣으면
인증이 필요한 API 도 그대로 호출해볼 수 있다. (`Bearer ` 접두어는 자동으로 붙는다)

OpenAPI JSON 은 클라이언트 코드 생성에 바로 쓸 수 있다.

```bash
openapi-generator generate -i https://api.re-view.kr/v3/api-docs -g dart-dio -o ./lib/api
```

> 아래 본문은 작성 시점(2026-09-30, 엔드포인트 50개) 기준이다.
> 숫자나 필드가 Swagger 와 다르면 **Swagger 가 맞다.**

---

## 1. 기본 정보

| 항목 | 값 |
|------|-----|
| Base URL | `https://api.re-view.kr` |
| 프로토콜 | HTTPS 전용 (HTTP는 301 리다이렉트) |
| 인코딩 | UTF-8 |
| Content-Type | `application/json` |

### CORS 허용 오리진

```
https://re-view.kr
https://www.re-view.kr
```

`allowCredentials: true`, 노출 헤더 `Authorization`. 목록에 없는 오리진은 브라우저가 차단한다.
`CORS_ALLOWED_ORIGINS` 에 `*` 같은 와일드카드 출처를 넣으면 서버가 기동하지 않는다(자격 증명과 함께 쓸 수 없다).

### 인증

JWT Bearer 토큰. 로그인/소셜로그인으로 받은 `accessToken`을 헤더에 싣는다.

```
Authorization: Bearer <accessToken>
```

- 액세스 토큰 만료: 환경변수 `JWT_EXPIRATION_MS` (기본 24시간, 프론트 refresh 연동 후 15분 = `900000` 목표)
- 로그인 세션: 리프레시 토큰. **30분 동안 refresh 가 없으면 만료**(유휴 만료), refresh 마다 회전
  - 웹: HttpOnly 쿠키 `review_rt` (`Path=/api/auth; Secure; SameSite=Lax`) — JS 에서 읽을 수 없다
  - 앱: 로그인·회원가입에 `X-Client-Platform: app` 을 붙이고 `Origin`·`Sec-Fetch-*` 가 없으면 쿠키 대신 본문 `refreshToken` 으로 받는다.
    refresh 는 토큰이 들어온 경로(쿠키/본문)로만 새 값을 돌려준다 — 쿠키로 온 요청에는 헤더와 무관하게 본문 토큰이 없다
  - 연동 방법·배포 순서: [`auth-session.md`](./auth-session.md)
- 토큰의 `sub` 는 사용자 이메일, `role` 클레임으로 권한 판정

---

## 2. 공통 응답 포맷

모든 응답은 아래 봉투로 감싸진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": { },
  "errorCode": null
}
```

| 필드 | 설명 |
|------|------|
| `success` | 성공 여부 |
| `message` | 사람이 읽을 안내 문구 |
| `data` | 실제 페이로드. 없으면 필드 자체가 생략됨 |
| `errorCode` | 실패 시에만. 아래 에러 코드 표 참고 |

> `null` 필드는 응답에서 제외된다(`@JsonInclude(NON_NULL)`).

### 실패 응답

```json
{
  "success": false,
  "message": "로그인이 필요합니다.",
  "errorCode": "UNAUTHORIZED"
}
```

### 페이징

Spring Data `Page` 를 그대로 직렬화한다. 요청은 쿼리 파라미터.

```
?page=0&size=10&sort=createdAt,desc
```

응답 `data` 구조:

```json
{
  "content": [ ],
  "totalElements": 42,
  "totalPages": 5,
  "number": 0,
  "size": 10,
  "first": true,
  "last": false,
  "empty": false
}
```

---

## 3. 에러 코드

| 코드 | HTTP | 의미 |
|------|------|------|
| `USER_NOT_FOUND` | 404 | 사용자를 찾을 수 없음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일 |
| `NICKNAME_ALREADY_EXISTS` | 409 | 이미 사용 중인 닉네임 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호 불일치 |
| `INVALID_RESET_TOKEN` | 400 | 유효하지 않은 재설정 토큰 |
| `EXPIRED_RESET_TOKEN` | 400 | 만료된 재설정 토큰 |
| `UNAUTHORIZED` | 401 | 로그인 필요 |
| `REFRESH_TOKEN_INVALID` | 401 | 리프레시 토큰 없음·만료·폐기·재사용. 응답이 쿠키도 지운다 → 로그아웃 처리 |
| `AUTH_SESSION_UNAVAILABLE` | 503 | 리프레시 저장소(Redis) 장애로 refresh·logout 을 처리할 수 없음. 쿠키 유지 → 로그아웃시키지 말고 나중에 재시도 |
| `PRODUCT_NOT_FOUND` | 404 | 상품 없음 |
| `PRODUCT_NOT_COLLECTED` | 409 | Data 서버가 아직 상품을 수집하지 못함 |
| `DATA_SERVER_UNAVAILABLE` | 503 | Data 서버에 연결 실패 |
| `REVIEW_NOT_FOUND` | 404 | 리뷰 없음 |
| `FEEDBACK_ALREADY_EXISTS` | 409 | 이미 피드백 제출함 |
| `FEEDBACK_NOT_FOUND` | 404 | 피드백 내역 없음 |
| `PREFERENCE_ALREADY_SET` | 409 | 이미 온보딩 완료 |
| `NOTIFICATION_NOT_FOUND` | 404 | 알림 없음 |
| `REPORT_NOT_FOUND` | 404 | 신고 내역 없음 |
| `REPORT_ALREADY_EXISTS` | 409 | 이미 신고한 리뷰 |
| `REPORT_FORBIDDEN` | 403 | 본인 신고만 조회 가능 |
| `WISHLIST_ALREADY_EXISTS` | 409 | 이미 찜한 상품 |
| `WISHLIST_NOT_FOUND` | 404 | 찜 목록에 없음 |
| `CART_ITEM_NOT_FOUND` | 404 | 장바구니에 없음 |
| `CHAT_SESSION_NOT_FOUND` | 404 | 대화 없음 |
| `CHAT_SESSION_FORBIDDEN` | 403 | 본인 대화만 조회 가능 |
| `CHAT_LLM_UNAVAILABLE` | 503 | 챗봇 일시 응답 불가 |
| `CHAT_QUOTA_EXCEEDED` | 429 | 하루 챗봇 메시지 한도 초과 |
| `CHAT_PLAN_REQUIRED` | 403 | 상위 요금제 전용 기능 |
| `NAVER_API_NOT_CONFIGURED` | 503 | 네이버 검색 API 미설정 |
| `INVALID_INPUT` | 400 | 입력값 오류 |
| `RESOURCE_NOT_FOUND` | 404 | 없는 경로 |
| `METHOD_NOT_ALLOWED` | 405 | 경로는 있으나 요청 방식이 틀림 (`Allow` 헤더로 허용 방식 안내) |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 내부 오류 |

경로변수 타입이 틀리거나(`/api/products/abc`) 필수 쿼리가 빠지면 `400` 이고 `message` 에
어느 값이 문제인지 적힌다. 이 경우 `errorCode` 는 비어 있다.

**`500` 은 서버 버그일 때만 나간다.** 예전에는 없는 경로·틀린 메서드·타입 오류도 전부
`500` 이었다. 지금 `500` 을 받으면 백엔드에 알려주면 된다.

---

## 4. 인증 (`/api/auth`) — 인증 불필요

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/refresh` | 세션 연장 — 새 액세스 토큰 + 리프레시 토큰 회전 |
| POST | `/api/auth/logout` | 로그아웃 — 리프레시 토큰 폐기 + 쿠키 삭제 (멱등 200, 저장소 장애 시만 503) |
| POST | `/api/auth/password/reset-request` | 비밀번호 재설정 메일 요청 |
| POST | `/api/auth/password/reset` | 비밀번호 재설정 (성공 시 그 사용자의 모든 세션 폐기) |

웹의 로그인·회원가입·refresh 응답에는 `Set-Cookie: review_rt=...` 가 붙고 본문 `LoginResponse` 는 기존과 같다.
리프레시 토큰은 언제나 한 경로로만 나간다 — 웹은 쿠키, 앱(`X-Client-Platform: app` + 브라우저 헤더 없음, 또는
본문 `refreshToken` 으로 refresh)은 본문 `refreshToken` 만(Set-Cookie 없음).
리프레시 저장소 장애 시 로그인·회원가입은 성공하되 리프레시 토큰이 빠진다(쿠키·본문 모두 없음).

**POST `/api/auth/signup`** — `SignupRequest`

```json
{ "email": "user@example.com", "password": "pw1234!", "nickname": "홍길동" }
```

**POST `/api/auth/login`** — `LoginRequest` → `LoginResponse`

```json
{ "email": "user@example.com", "password": "pw1234!" }
```

```json
{
  "accessToken": "eyJhbGci...",
  "tokenType": "Bearer",
  "email": "user@example.com",
  "nickname": "홍길동",
  "role": "USER",
  "onboardingCompleted": false
}
```

**POST `/api/auth/refresh`** — 본문 없음(웹, 쿠키 사용) 또는 `{ "refreshToken": "..." }`(앱) → `LoginResponse`

실패 시 401 `REFRESH_TOKEN_INVALID` + `Set-Cookie: review_rt=; Max-Age=0`. 재시도하지 말고 로그아웃 처리한다.
503 `AUTH_SESSION_UNAVAILABLE` 이면 쿠키를 지우지 않으니 로그아웃시키지 말고 나중에 다시 시도한다.

**POST `/api/auth/password/reset`** — `PasswordResetRequest`

```json
{ "token": "메일로 받은 토큰", "newPassword": "new1234!" }
```

### 소셜 로그인

브라우저를 아래 주소로 이동시킨다(XHR 아님).

```
GET https://api.re-view.kr/oauth2/authorization/google
GET https://api.re-view.kr/oauth2/authorization/naver
```

성공 시 프론트 콜백으로 **쿼리 파라미터**와 함께 리다이렉트된다(Fragment 아님).

```
https://re-view.kr/oauth2/callback
  ?accessToken=...&tokenType=Bearer&email=...&nickname=...
```

이 302 응답에 리프레시 쿠키(`review_rt`)도 함께 심긴다. 리프레시 토큰은 쿼리로 주지 않는다.

실패 시:

```
https://re-view.kr/oauth2/callback?error=access_denied
https://re-view.kr/oauth2/callback?error=server_error
```

---

## 5. 상품 · 검색 — 인증 불필요

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/products` | 상품 목록 (`?keyword=` 검색 가능) — **출처: Data 서버** |
| GET | `/api/products/{id}` | 상품 상세 |
| GET | `/api/products/{productId}/reviews` | 상품 리뷰 목록 |
| GET | `/api/search?keyword=` | 네이버 쇼핑 통합 검색 |
| GET | `/api/dashboard` | 대시보드 (추천/최근/위험 상품 + 인기 키워드) |
| GET | `/api/dashboard/keywords` | 인기 검색어 |
| GET | `/api/landing/stats` | 랜딩 통계 |

### `/api/products` 는 Data 서버 상품을 내려준다

경로와 응답 모양은 예전과 같고 **출처만 바뀌었다.**

| 요청 | 출처 |
|---|---|
| `GET /api/products?keyword=마스크팩` | Data 서버 검색 — 컬리·올리브영·무신사·11번가를 **동시에** 조회 (보통 1초 안) |
| `GET /api/products` (홈) | 검색으로 들어온 실제 상품 최근 300건 중 **대분류별로 번갈아** 100건. 하나도 없을 때만 예전 더미 |

- 검색 결과는 몰별로 **번갈아** 섞여 나온다. 한 몰이 늦거나 실패하면 그 몰만 빠진다
- 결과가 없으면 빈 배열이다. 더미로 채우지 않는다
- Data 서버에는 "전체 상품 목록" API 가 없다. 그래서 **검색한 상품이 홈에 쌓인다**
- 운영에서는 서버가 인기 키워드 14개를 6시간마다(+배포 직후) 몰당 3건씩 검색해 홈을 채운다 (`app.home-refresh.*`). 사용자 검색이 없어도 홈이 비거나 낡지 않는다

**Data 서버 상품 구분법** — `externalId` 가 있으면 Data 서버 상품이다. 이 상품은

- 상세를 `/api/v2/products/{dataPlatform}/{dataProductId}` 로 연다. 리뷰·수집 상태·신고가 거기서 동작한다
- 챗봇 `productId` 에 `externalId` 를 그대로 넣는다
- `avgRti` · `rtiGrade` 가 **null** 이다 (상품 평균 RTI 는 아직 없다). **0 이나 기본값으로 그리지 말 것**
- `analysisStatus` 는 v2 상세를 열 때 서버가 Data 서버에서 **마지막으로 본 분석 상태**다 (`DONE`·`QUEUED` 등, 상세의 `analysisStatus` 와 같은 enum). **null 이면 아직 본 적 없음(모름)** 이다. 누군가 상세를 열어야 갱신되므로 늦을 수 있다. `UNAVAILABLE` 은 적지 않아(일시 장애가 마지막 값을 지우지 않게) 목록에는 오지 않는다. `DONE` 이어도 `avgRti` 는 null 이다. 대시보드 상품도 같다
- `avgRating` 은 평점을 모르면 **null** 이다 (Data 서버가 평점을 주지 않는 몰, 예: 컬리). 예전에는 `0.0` 이었다. `reviewCount` 는 쇼핑몰 리뷰 수를 모르면(0 이하) Data 에 수집된 리뷰 수(`analysisSourceReviewCount`)로 채운다(#276). 쇼핑몰 표시 수보다 작을 수 있다. 수집된 리뷰도 없으면 실제 0 과 모름을 가를 근거가 없어 그대로 둔다
- `category` · `categoryDisplayName` 은 쇼핑몰 카테고리와 상품명으로 서버가 분류한 값이다 (예: `BEAUTY_SKINCARE` / `"스킨케어"`). 근거가 없으면 **null** 이다 (약 1할)
- 쇼핑몰 원문 카테고리는 `subCategory` 에 그대로 온다 (`"뷰티 > 스킨케어 > 마스크팩"`). 컬리·무신사·11번가는 상세를 한 번 연 뒤에야 채워진다
- `platforms[0].url` 이 쇼핑몰 상품 페이지다

**`ProductResponse`** (여러 API 공통)

```json
{
  "id": 1,
  "naverProductId": "7195971829",
  "name": "베이직 크루넥 니트",
  "imageUrl": "https://...",
  "price": 29900,
  "majorCategory": "FASHION",
  "majorCategoryDisplayName": "패션",
  "category": "TOP",
  "categoryDisplayName": "상의",
  "subCategory": "니트",
  "platform": "NAVER",
  "avgRti": 72.4,
  "rtiGrade": "WARN",
  "rtiLevel": "주의",
  "rtiColor": "#FFA500",
  "reviewCount": 128,
  "avgRating": 4.2,
  "platforms": [{ "platform": "NAVER", "price": 29900, "url": "https://..." }],
  "lowestPrice": 28500,
  "lowestPlatform": "GMARKET",
  "productUrl": "https://...",
  "dataPlatform": "kurly",
  "dataProductId": "1000146248",
  "externalId": "kurly-1000146248",
  "analysisStatus": "DONE"
}
```

`analysisStatus` 는 Data 서버 상품만 채워진다. 기존(더미·네이버) 상품은 null 이고 `avgRating` 도 예전 그대로다.

**`ReviewResponse`**

```json
{
  "id": 10, "productId": 1, "reviewerNickname": "구매자1",
  "content": "사이즈가 작아요", "rating": 4,
  "trustGrade": "SAFE", "trustGradeLabel": "안전", "trustGradeColor": "#4CAF50",
  "reasons": ["구매 인증됨"], "writtenAt": "2026-09-01T10:00:00",
  "isVerifiedPurchase": true, "reviewerAtiScore": 81.2
}
```

**`reasons` 는 절대 빈 배열로 내려가지 않습니다.** 판정 사유가 없으면 안내 문구
한 건이 대신 들어갑니다.

```json
{ "reasons": ["추가로 표시할 세부 사유가 없습니다."] }
```

Data 서버는 사유가 없으면 `"reasons": []` 를 그대로 줍니다. 그건 근거 **코드**
배열이라 안내 문장을 섞으면 저장·집계할 때 다시 갈라내야 하기 때문입니다.
문장은 Spring 이 응답을 만드는 시점에만 끼웁니다. DB 에는 빈 목록 그대로 남습니다.

프론트에서 `reasons.isEmpty()` 분기를 따로 둘 필요가 없습니다. 길이가 늘
1 이상이므로 그대로 그리면 됩니다. 단, 관리자 API(`/api/admin/reviews/suspicious`)
는 이 보정을 하지 않습니다 — 운영자는 사유가 실제로 없는 것인지 봐야 하므로
빈 배열이 그대로 내려갑니다.

**`DashboardResponse`**

```json
{
  "recommendedProducts": [ ], "recentProducts": [ ],
  "riskyProducts": [ ], "popularKeywords": ["니트", "패딩"]
}
```

---

## 6. AI 분석 — 인증 불필요

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/analysis/product` | 상품 분석 실행 |
| GET | `/api/analysis/health` | AI 서버 상태 |

**POST `/api/analysis/product`** — `ProductAnalyzeRequest` → `ProductAnalysisResponse`

```json
{ "productId": "7195971829", "productUrl": "https://..." }
```

```json
{
  "productId": "7195971829",
  "averageRti": 72.4, "level": "주의", "reviewCount": 128,
  "safeCount": 80, "warnCount": 30, "dangerCount": 18,
  "reviews": [{
    "reviewId": "r-1", "content": "...", "author": "구매자1", "date": "2026-09-01",
    "rti": 81, "level": "안전",
    "textScore": 85, "behaviorScore": 78, "networkScore": 80,
    "reasons": ["구매 인증됨"]
  }],
  "trend": [{ "date": "2026-09-01", "averageRti": 70.1, "reviewCount": 12,
              "safeCount": 8, "warnCount": 3, "dangerCount": 1 }],
  "realReviewRatio": 0.62, "adSuspicionRatio": 0.21, "repetitiveRatio": 0.17,
  "trustSignals": [{ "label": "작성일 편중", "value": "동일 날짜 32건", "isPositive": false }]
}
```

`reviews[].reasons` 도 위와 같습니다. 비어 있으면 안내 문구 한 건이 들어갑니다.

> ⚠️ 이 엔드포인트는 Data 서버 연동 후 제거될 예정이다. 신규 화면은 Data 서버 API 를 쓴다.

---

## 6-2. 상품 (Data 서버) — `/api/v2/products` — 인증 불필요

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v2/products/{platform}/{productId}` | 상품 상세 + 리뷰 (Data 서버) |
| POST | `/api/v2/products/{platform}/{productId}/tag` | 번호표 발급 (찜·장바구니 연결, **인증 필요**) |
| GET | `/api/v2/products/collection-jobs/{jobId}` | 수집 job 상태 |

기존 `/api/products/**` 와 **병행 운영**한다. 한 번에 바꾸면 되돌릴 수 없으므로 화면 단위로 옮긴다.

`platform` 은 Data 서버 수집기 이름(소문자): `naver` `kurly` `elevenst` `ably` `auction` `gmarket` `musinsa` `ohouse` `oliveyoung`.
Data 서버가 `(platform, productId)` 로 상품을 가리키므로 프론트도 둘을 함께 들고 다녀야 한다.

### `collectionStatus` 로 화면을 가른다

이 값을 무시하면 사용자가 빈 화면을 보고 고장났다고 느낀다. **전부 HTTP 200 이다.**

| 값 | `product` | 화면 |
|---|---|---|
| `FRESH` | 있음 | 그대로 표시 |
| `STALE` | 있음 | 그대로 표시 (+ "갱신 중" 정도) |
| `QUEUED` | **null** | 로딩 화면. `job.id` 로 완료를 기다린다 |
| `UNAVAILABLE` | **null** | 오류 안내. 상품이 없는 게 아니라 **못 가져온** 것 |

- **처음 보는 상품은 반드시 `QUEUED` 를 한 번 거친다.** Data 서버가 그때 수집을 시작한다. 로딩 화면 없이 바로 열면 빈 화면이 된다.
- `STALE` 은 실패가 아니다. 쓸 수 있는 데이터가 들어 있고, 최신을 기다리면 화면이 크롤링 속도에 묶인다.

```json
{
  "collectionStatus": "FRESH",
  "springProductId": 42,
  "product": {
    "platform": "kurly", "productId": "1000146248", "externalId": "kurly-1000146248",
    "name": "샘플 상품", "url": "https://kurly.com/p", "brand": "브랜드",
    "price": 29900, "thumbnailUrl": "https://img", "category": "식품 > 간편식",
    "reviewCount": 128, "rating": 4.5, "lastCollectedAt": "2026-10-05T00:00:00+09:00"
  },
  "reviews": {
    "items": [{ "reviewId": "r-1", "content": "맛있어요", "rating": 5.0,
                "author": "user**", "writtenAt": "2026-10-01T10:00:00+09:00",
                "option": "옵션", "images": [], "helpfulCount": 3,
                "rti": 82.5, "level": "safe", "reasons": ["TEXT_SPECIFIC_DETAIL"] }],
    "nextCursor": "eyJ3cml0..."
  },
  "job": null,
  "analysisStatus": "DONE",
  "analysis": { "status": "DONE", "modelVersion": "rti-model-0.5",
                "policyVersion": "rti-v0", "reviewCount": 128,
                "sampled": true, "sourceReviewCount": 1318 }
}
```

- **`springProductId` 는 null 일 수 있다.** 찜·장바구니에 쓸 Spring 쪽 번호인데, 아직 아무도 찜하지 않은 상품은 번호가 없다. 열어보기만 해도 번호를 만들면 빈 행이 계속 쌓이므로 그렇게 하지 않는다.
- **`product.reviewCount`·`rating` 은 null 일 수 있다.** 11번가·올리브영은 Data 서버 상세 응답에서 이 값을 비운다. 목록(검색)에서 받아 둔 값이 있으면 서버가 그 값으로 채우고, 그것도 없으면 null 이다. null 은 "0개"가 아니라 "모름"으로 표시한다.
- **신뢰도 분석은 리뷰마다 붙는다.** 분석 진행 상태는 `analysisStatus`(`DONE`·`QUEUED`·`RUNNING`·`NOT_ANALYZED`·`STALE`·`FAILED`·`DISABLED`·`UNAVAILABLE`)로 **늘 온다**(모르면 `UNAVAILABLE`). `analysis` 는 **결과가 있을 때(`DONE`)만** 모델·정책 버전을 담은 객체이고 그 밖에는 **null** 이다 — `analysis != null` 이 "분석 결과 있음"이라는 기존 뜻을 지킨다. 리뷰별 `rti`·`level`·`reasons` 는 `DONE` 일 때만 채워지고, 그 밖에는 `rti`·`level` 이 null, `reasons` 가 빈 배열이다. `level` 은 Data 원문 `safe`/`warn`/`danger` 그대로다(경계 70/40). **상품 단위 평균 RTI·등급은 아직 없다** — 결과가 리뷰 한 페이지 것뿐이라 화면에서 평균을 내면 안 된다. 자세한 화면 처리는 [frontend-v2-migration.md](frontend-v2-migration.md) 3절.
- **`analysis` 의 리뷰 수는 둘이다.** `reviewCount` 는 **분석 입력(표본) 수**, `sourceReviewCount` 는 **Data 서버가 가진 원본 리뷰 수**다. `sampled: true` 면 원본 중 일부만 분석했으므로 표본에 들지 않은 리뷰는 `DONE` 이어도 `rti` 가 null 이다. 둘 다 `product.reviewCount`(쇼핑몰 표시 수)와 다를 수 있다. 구버전 Data 서버(표본 정책 전)면 `sampled`·`sourceReviewCount` 가 null 이다.
- 리뷰는 **cursor 페이지네이션**이다. `reviews.nextCursor` 를 다음 요청의 `?cursor=` 에 그대로 넣는다. null 이면 마지막 페이지다.
- **리뷰마다 신호 점수 `textScore`·`behaviorScore`·`networkScore`(0~100)가 실린다.** 계산 불가면 null이다. 리뷰 상세 화면의 세부 점수가 이 값을 쓴다.
- **분석은 v4.0 하나다(#273).** Data 서버가 v0.5·v3.1 결과를 보내지 않는다. 리뷰의 `rti`·`level`·`textScore`·`behaviorScore`·`networkScore`·`policyIndexV3`·`evidenceLevelV3`·`analysisSummaryKo`·`reasonSource` 는 **늘 null**, `reasons`·`rulesReasonsKo`·`evidenceReasonsV3` 는 **늘 빈 배열**이다. 배포된 화면이 키를 읽고 있어 자리만 남겼고, 화면이 키를 지우면 뺀다. `analysis.contractVersion` 은 `v4` 로 온다.
- **`analysis.modelVersionV4`·`policyVersionV4` 는 v4.0 모델·규칙 버전이다(#276).** 이 페이지 리뷰 결과에서 읽는다. `modelVersion`·`policyVersion` 은 분석 job 버전이라 v4 로 다시 채운 상품은 v3.1 값이 남는다. v4 결과가 있는 리뷰가 페이지에 없으면 null.
- **`analysis`에는 상품 단위 v4 요약이 실린다(#249):** `avgLevelV4`(평균 지수의 등급 `SAFE` 70~100 / `WARN` 40~69 / `DANGER` 0~39, 평균이 없으면 null), `warnRatioV4`·`dangerRatioV4`(v4 결과가 있는 리뷰 중 비율 0~1, 소수 넷째 자리. 분포가 없거나 합이 0 이면 null). "의심 리뷰 비율"에 WARN 을 넣을지는 화면이 정한다 — 넣으려면 두 비율을 더한다. 상품 요약 문장은 아직 없다(v3.1 설명은 v4 등급과 맞지 않아 싣지 않는다).
- **`reviewSummary` 는 상품 AI 리뷰 요약이다(#277).** `{ "pros": [...], "cons": [...], "sourceReviewCount": 40, "generatedAt": "..." }`. 장점 2~3개, 아쉬운 점 0~3개(리뷰에 없으면 빈 배열). 사용자 채팅 한도를 쓰지 않는다.
  - 분석이 끝난(`DONE`) 상품의 첫 페이지를 처음 열 때 서버가 뒤에서 만들기 시작한다. **그 응답에는 `null` 이고 다음 조회부터 보인다.** 리뷰가 바뀌어 다시 만드는 동안은 이전 요약이 그대로 온다.
  - 의심 등급(`DANGER`) 리뷰, 10자 미만 리뷰는 요약에 넣지 않는다. 남은 리뷰가 5개 미만이면 만들지 않고 계속 `null` 이다.
  - `null` 이면 요약 영역을 숨기거나 "요약 준비 중"으로 둔다. 폴링하지 않는다.
- **`product.description`·`detailImages` 는 상품 상세 설명이다(#249).** 설명 본문(plain text)과 설명 이미지 URL 배열. 컬리·무신사만 채워지고, 없으면 `null` / 빈 배열이다.
- **`analysis`에는 상품 전체 평균이 실린다:** `avgPolicyIndexV4`, `v4LevelCounts`. 카탈로그 동기화로 저장한 값이라 이 페이지 리뷰만의 평균이 아니다. 동기화되지 않은 상품은 null. `avgPolicyIndexV3` 는 늘 null 이다.
- **v4.0 결과는 `*V4` 필드로 온다(#255).** 화면 등급·지수는 v4 를 쓴다.

  | 리뷰 필드 | 값 |
  |---|---|
  | `levelV4` | `SAFE`/`WARN`/`DANGER` (대문자) |
  | `policyIndexV4` | 0~100. SAFE 70~100, WARN 40~69, DANGER 0~39 |
  | `textScoreV4` | 0~100. 높을수록 신뢰 |
  | `textProbabilityV4` | 0~1 의심 확률 |
  | `modelVersionV4` / `policyVersionV4` | 예: `ptext-koelectra-v4.0` / `text-first-v1` |

  - v4 결과가 없는 리뷰는 v4 필드가 전부 **null** 이다. "분석 중"으로 표시한다.
  - `reasons`(v3.1 설명)는 v3.1 등급 기준이라 v4 등급과 함께 보여주지 않는다. v4 는 아직 설명 문장이 없다.
  - 문구는 "가짜 리뷰" 같은 단정 대신 "주의가 필요한 리뷰"처럼 쓴다.
  - 목록(`GET /api/products`, `/api/products/catalog`)에는 상품 단위로 `avgPolicyIndexV4`(평균 지수)와 `v4LevelCounts`(`{"SAFE": 2, "WARN": 1, "DANGER": 0}`)가 온다. 없으면 null 이고, `avgRti`(v3.1)로 대신 채우지 않는다.

**POST `/api/v2/products/{platform}/{productId}/tag`** — **인증 필요**

Data 서버 상품을 **찜·장바구니에 쓸 수 있는 Spring 상품 번호**로 바꾼다.
찜·장바구니 API 는 Spring 의 `productId`(Long)를 받으므로 그 사이를 잇는 변환점이다.

```
1. 찜 버튼 클릭
2. POST /api/v2/products/kurly/1000146248/tag  →  { "springProductId": 1234, ... }
3. POST /api/wishlist/1234                      (기존 API 그대로)
```

```json
{ "springProductId": 1234, "externalId": "kurly-1000146248", "name": "토리든 마스크팩" }
```

- **여러 번 불러도 안전하다.** 같은 상품이면 늘 같은 번호가 나온다
- 상품 상세 응답의 `springProductId` 가 이미 있으면 **이 호출을 건너뛴다**
- 이미 번호가 있으면 Data 서버를 부르지 않아 빠르다

| 응답 | 상황 |
|---|---|
| `200` | 발급 완료 |
| `409 PRODUCT_NOT_COLLECTED` | 아직 수집 전. 수집이 끝나야 번호를 줄 수 있다 |
| `503 DATA_SERVER_UNAVAILABLE` | Data 서버에 닿지 못함 |

`409` 는 **상품이 없다는 뜻이 아니다.** 상세 조회로 `job` 상태를 보고 수집이 끝난 뒤 다시 부른다.

> 찜·장바구니 목록에서 분석 전 상품은 `categoryDisplayName` · `avgRti` · `rtiGrade` · `rtiColor` 가 **null 로 내려온다.**

**GET `/api/v2/products/collection-jobs/{jobId}`**

`QUEUED` 를 받았을 때 완료를 기다린다. 폴링 간격은 2~3초를 권한다 (크롤링이라 수 초~수십 초).

```json
{ "id": 9, "status": "partial", "productStatus": "succeeded",
  "reviewStatus": "failed", "lastError": "타임아웃" }
```

`status` 가 `succeeded` 또는 `partial` 이 되면 상품 상세를 다시 호출한다.
`partial` 은 상품만 수집되고 리뷰가 실패한 상태 — 상품은 보여줄 수 있다.
`failed` 면 재시도해도 같을 가능성이 높으므로 `lastError` 를 안내한다.

---

## 6-3. Data 서버 리뷰 신고·피드백·분석 피드백 — **인증 필요**

| Method | Path |
|--------|------|
| POST | `/api/reports/external/{platform}/{productId}/reviews/{externalReviewId}` |
| POST | `/api/reviews/external/{platform}/{productId}/reviews/{externalReviewId}/feedback` |
| POST | `/api/analysis-feedbacks/external/{platform}/{productId}/reviews/{externalReviewId}` |

`/api/v2/products/**` 로 조회한 리뷰를 신고하거나 실제/가짜 피드백, 분석 결과 피드백을 남긴다.
`externalReviewId` 는 쇼핑몰이 발급한 원본 값(`reviews.items[].reviewId`)을 그대로 넣는다.
요청 본문은 기존 신고·피드백 API 와 같다.

**상품 번호표가 없으면 이 호출에서 발급된다.** 따로 `tag` 를 부를 필요가 없다.

| 응답 | 상황 |
|---|---|
| `409 PRODUCT_NOT_COLLECTED` | 아직 수집 전인 상품. 실재하지 않는 리뷰에 기록이 쌓이지 않게 막는다 |
| `409 REPORT_ALREADY_EXISTS` / `FEEDBACK_ALREADY_EXISTS` | 같은 리뷰에 두 번 (분석 피드백은 기존 경로처럼 여러 번 허용) |
| `400 INVALID_INPUT` | (분석 피드백) `externalReviewId` 가 공백이거나 200자 초과 |
| `503 DATA_SERVER_UNAVAILABLE` | Data 서버에 닿지 못함 |

### 분석 피드백은 리뷰·분석 결과의 실존을 확인하지 않는다

검증 수준은 신고·리뷰 피드백과 같다 — **상품**이 수집됐는지만 본다(번호표가 이미 있으면 Data 서버도 부르지 않는다).
그 리뷰가 실제로 그 상품에 있는지, 그 리뷰의 분석 결과가 있는지는 확인하지 않는다.
Data 서버에 리뷰(또는 분석 결과) 단건 검증 API 가 없고, 상품 분석 조회는 현재 페이지 결과만 돌려주기 때문이다.
프론트는 분석 결과가 있는 리뷰에서만 제출 버튼을 보여 준다. 단건 검증 계약이 생기면 서버에서 확인하도록 바꾼다.

### 리뷰 본문은 저장하지 않는다

Spring DB 에 그 리뷰 행이 없고, 본문을 클라이언트에게 받으면 신고 내용을 위조할 수 있다.
그래서 신고·피드백 조회에서 **Data 서버 리뷰는 아래가 null 로 내려온다.**

- `reviewId` (Long) — 대신 `externalReviewId`(String) 가 채워진다
- `reviewContent` / `reviewContentSummary`

`productName` 과 `productExternalId` 는 채워지므로, 운영자는 상품으로 들어가 해당 리뷰를 확인한다.
기존 Spring 리뷰에 대한 신고·피드백은 **전과 동일하게** 본문까지 내려온다.
분석 피드백(`AnalysisFeedbackResponse` — 내 목록·단건·관리자 목록·검수 처리)과 통합 내역(`/api/feedback/me`)도 같다.

---

## 7. 챗봇 (`/api/chat`) — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/chat/messages` | 질문 전송 (모든 요금제) |
| POST | `/api/chat/pro/messages` | 질문 전송 (`PRO` 요금제 전용) |
| GET | `/api/chat/quota` | 오늘 남은 사용량 |
| GET | `/api/chat/sessions` | 내 대화 목록 (페이징) |
| GET | `/api/chat/sessions/{sessionId}/messages` | 대화 내용 |
| DELETE | `/api/chat/sessions/{sessionId}` | 대화 삭제 (본인 것만) |
| DELETE | `/api/chat/sessions` | 내 대화 전체 삭제 |

### 요금제와 하루 한도

| 요금제 | 하루 메시지 | `/messages` | `/pro/messages` |
|---|---|---|---|
| `FREE` | 5 | O | X (403) |
| `PLUS` | 100 | O | X (403) |
| `PRO` | 300 | O | O |

- 한도 수치는 서버 설정값(`app.chat.quota.*`)이라 **바뀔 수 있다.** 화면에는 하드코딩하지 말고 응답의 `quota` 를 쓸 것.
- 한도는 **한국 시간 자정**에 초기화된다 (`quota.resetAt`).
- 두 전송 엔드포인트는 **같은 카운터**를 쓴다. 프로로 10개를 보냈으면 기본에서도 10개가 깎여 있다.
- 요금제는 JWT 가 아니라 DB 값이라 변경이 **즉시** 반영된다. 재로그인은 필요 없다.
- 만료된 유료 요금제는 자동으로 `FREE` 로 동작한다.

**쿼터를 깎는 기준**

| 상황 | 차감 |
|---|---|
| 정상 답변 | O |
| 세이프가드 1계층 차단 (`INJECTION` / `TOO_LONG` / `EMPTY`) | X — LLM 을 부르지 않아 비용이 0 |
| `OFF_TOPIC` / `UNGROUNDED_SCORE` | O — 이미 토큰을 썼다 |
| LLM 호출 실패 (503) | X — 서버 잘못으로 한도를 깎지 않는다 |

**POST `/api/chat/messages`** — `ChatRequest` → `ChatResponse`

```json
{ "sessionId": null, "productId": "7195971829", "question": "이 상품 살만해?" }
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `sessionId` | | `null` 이면 새 대화 시작. 이어가려면 응답의 값을 그대로 전달 |
| `productId` | | 대화 대상 상품. **새 대화일 때만 반영**된다 |
| `question` | ✅ | 최대 **500자** |

```json
{
  "sessionId": 10,
  "answer": "사이즈가 작게 나온다는 의견이 많습니다...",
  "blocked": false,
  "blockReason": null,
  "usedTokens": 1850,
  "quota": {
    "plan": "FREE",
    "planName": "무료",
    "dailyLimit": 5,
    "usedToday": 1,
    "remaining": 4,
    "proAvailable": false,
    "resetAt": "2026-10-01T15:00:00Z"
  },
  "recommendations": [],
  "scheduleProposal": null
}
```

`quota` 는 **이번 턴을 반영한** 값이다. 전송 직후 남은 횟수 표시를 갱신하는 데 그대로 쓸 수 있다.

**`recommendations` — 비슷한 상품 카드** (#199)

사용자가 "다른 상품 추천해줘", "비슷한 거 없어?"처럼 다른·비슷한·대체 상품을 원하면
대화 상품과 비슷한 실제 상품이 담긴다. **항상 배열이고 null 로 오지 않는다.**

```json
"recommendations": [
  { "externalId": "kurly-1001872496", "platform": "kurly", "productId": "1001872496",
    "name": "...", "price": 29900, "thumbnailUrl": "https://...", "reviewCount": 56, "rating": null }
]
```

- 모델은 추천이 필요한지만 판단하고(응답 형식의 `RECOMMEND: yes|no` 줄), **상품은 서버가 DB 에서 고른다.**
  `answer` 본문에는 상품 이름·링크·ID 가 들어가지 않는다. 추천 때문에 LLM 을 더 부르지 않으며 쿼터도 그대로다
- 선정 기준: 대화 상품과 **같은 카테고리**, 가격 **0.5~1.5배**(대화 상품 가격을 모르면 가격 조건 생략),
  Data 서버 번호표가 있는 상품(빈 문자열·공백뿐인 번호표 제외), 대화 상품 제외, **리뷰 수 많은 순 최대 3개**. RTI 가 아직 없어 신뢰도로는 정렬하지 않는다
- 빈 배열인 경우: `blocked=true`, 추천을 원하지 않은 질문, 대화에 상품이 없음, 대화 상품을 Spring 에서 못 찾음, 대화 상품의 카테고리가 없음, 조건에 맞는 후보가 없음,
  추천 조회 실패(DB 장애 등). 추천이 실패해도 답변·저장·쿼터는 정상 턴과 같고 200 으로 응답한다
- `price`·`reviewCount`·`rating`·`thumbnailUrl` 은 모르면 **null** 이다. 0 으로 채우지 않는다
- 이전 대화 불러오기(`/sessions/{id}/messages`)에는 추천이 저장되지 않는다

**`scheduleProposal` — 장바구니 예약 제안** (#269)

상품 대화에서 사용자가 "매주 월요일마다 이거 장바구니에 담아줘"처럼 날짜나 주기를 정해 담아 달라고 하면
예약 제안이 담긴다. 아니면 `null` 이다. **이 시점에는 아직 예약되지 않았다.** 사용자가 확인 버튼을 눌러야 확정된다.

```json
"scheduleProposal": { "scheduleId": 55, "summary": "매주 월요일 오전 9시 · 1개", "productName": "텀블러" }
```

- 확인 버튼 → `POST /api/cart-schedules/{scheduleId}/confirm` (10-1절)
- 지원하는 주기: 한 번(날짜), 매주(요일), 매월(날짜, 그 달에 없는 날은 말일). 매일·격주는 지원하지 않는다
- 모델은 응답 형식의 `SCHEDULE:` 줄에 예약 내용만 옮겨 적고, 서버가 형식을 엄격히 검사한다. 어긋나면 제안 없이 답변만 나간다
- 상품 없는 대화에서도 사용자의 반복 담기 상품(10-2절)이면 예약을 제안할 수 있다. 모델이 `SCHEDULE:` 줄 끝에 `product=<상품주소>` 를 붙이고, 서버는 그 상품이 이번 턴 프롬프트에 실은 반복 담기 후보이거나 대화 상품일 때만 받는다 (#271)
- `null` 인 경우: 예약 요청이 아님, `blocked=true`, 상품 없는 대화(반복 담기 상품 제외), 반복 담기 후보에도 대화에도 없는 상품, 형식 오류·지난 날짜·1년 넘는 날짜·수량 1~10 밖, 진행 중 예약 20개, 저장 실패
- 예약 때문에 LLM 을 더 부르지 않으며 쿼터도 그대로다

**POST `/api/chat/pro/messages`** — 요청·응답 형식이 `/api/chat/messages` 와 완전히 같다.
`PRO` 요금제만 호출할 수 있고, 그 외에는 `403 CHAT_PLAN_REQUIRED` 다. 상위 모델로 더 긴 답변을 받는다.

호출 버튼은 `GET /api/chat/quota` 의 `proAvailable` 로 노출 여부를 판단할 것. 403 을 받고 나서 숨기면 사용자가 실패를 한 번 겪는다.

**GET `/api/chat/quota`** → `ChatQuotaResponse`

LLM 을 부르지 않으므로 빠르고, 쿼터를 깎지 않는다. 채팅 화면 진입 시 한 번 불러
남은 횟수와 프로 기능 노출 여부를 정하는 용도다.

```json
{
  "plan": "PRO", "planName": "프로",
  "dailyLimit": 300, "usedToday": 12, "remaining": 288,
  "proAvailable": true, "resetAt": "2026-10-01T15:00:00Z"
}
```

`dailyLimit` 과 `remaining` 이 `-1` 이면 무제한을 뜻한다.

**한도 초과 응답** — `429`

```json
{ "success": false, "errorCode": "CHAT_QUOTA_EXCEEDED",
  "message": "오늘 사용할 수 있는 챗봇 메시지를 모두 사용했습니다. 요금제를 올리면 더 많이 이용할 수 있습니다." }
```

### 세이프가드 — `blocked` 처리

챗봇은 상품·리뷰·가격·카테고리·신뢰도 밖의 질문에 답하지 않는다. 차단되면 `blocked: true` 로 오고 `answer` 에 안내 문구가 담긴다. **에러가 아니라 200 응답**이므로 정상 흐름으로 처리해야 한다.

| `blockReason` | 상황 | 권장 UI |
|---|---|---|
| `INJECTION` | 프롬프트 조작 시도 감지 | 안내 문구만 표시 |
| `TOO_LONG` | 500자 초과 | 입력창에 길이 안내 |
| `EMPTY` | 빈 질문 | 전송 버튼 비활성화로 예방 |
| `OFF_TOPIC` | 주제 이탈 | 안내 문구 표시, 예시 질문 제안 |
| `UNGROUNDED_SCORE` | 근거 없는 수치 감지 | 재질문 유도 |

`usedTokens` 는 이번 턴의 LLM 토큰 소모량이다. 쿼터가 유한하므로 개발 중 모니터링에 쓸 수 있다.

**응답 지연**: LLM 호출은 수 초~수십 초가 걸린다. 서버 타임아웃은 70초이므로 클라이언트 타임아웃을 그보다 길게 잡고 로딩 UI 를 반드시 둘 것.

**GET `/api/chat/sessions`** → `Page<ChatSessionResponse>`

```json
{ "id": 10, "productId": "kurly-1000146248", "title": "이 상품 살만해?",
  "createdAt": "2026-09-30T10:00:00", "lastMessageAt": "2026-09-30T10:05:00",
  "productName": "샘플 상품", "productImageUrl": "https://img..." }
```

- `productName`·`productImageUrl` 은 상품 없는 대화이거나 서버에 번호표가 없는 상품(목록·검색에 한 번도 오르지 않은 상품)이면 **null** 이다(#275). null 이면 상세를 따로 불러 채운다.

**DELETE `/api/chat/sessions/{sessionId}`** → `ApiResponse<Void>`. 메시지까지 지운다. 남의 대화면 `403 CHAT_SESSION_FORBIDDEN`, 없으면 `404 CHAT_SESSION_NOT_FOUND`.

**DELETE `/api/chat/sessions`** → `ApiResponse<Integer>`. 내 대화를 모두 지우고 지운 대화 수를 `data` 에 준다(없으면 0).

- 두 삭제 모두 오늘 쓴 사용량은 되돌리지 않는다.

**GET `/api/chat/sessions/{sessionId}/messages`** → `List<ChatMessageResponse>`

```json
{ "id": 1, "role": "USER", "content": "이 상품 살만해?",
  "blocked": false, "blockReason": null, "createdAt": "2026-09-30T10:00:00" }
```

`role` 은 `USER` / `ASSISTANT`. 차단된 대화도 이력에 남는다.

---

## 8. 마이페이지 (`/api/users/me`) — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/users/me` | 프로필 조회 |
| PATCH | `/api/users/me` | 프로필 수정 |
| DELETE | `/api/users/me` | 회원 탈퇴 |
| GET | `/api/users/me/stats` | 이용 통계 |
| PATCH | `/api/users/me/plan` | 내 요금제 변경 |
| GET | `/api/users/me/activities` | 최근 활동 |
| GET | `/api/users/me/security` | 보안 상태 |
| GET | `/api/users/me/settings` | 설정 조회 |
| PATCH | `/api/users/me/settings` | 설정 수정 |
| GET | `/api/users/me/feedback` | 통합 피드백 내역 |

**`UserResponse`**

```json
{
  "id": 1, "email": "user@example.com", "nickname": "홍길동",
  "profileImageUrl": null, "role": "USER", "provider": "LOCAL",
  "atiScore": 76.5, "createdAt": "2026-04-01T09:00:00",
  "onboardingCompleted": true, "phone": "010-1234-5678",
  "interestCategories": ["패션", "뷰티"],
  "planTier": "PLUS", "planExpiresAt": "2026-12-31T23:59:59"
}
```

`planTier` 는 **지금 적용 중인** 챗봇 요금제다. 만료가 지난 유료 요금제는 `FREE` 로 내려온다.
남은 사용량까지 필요하면 `GET /api/chat/quota` 를 쓴다.

**PATCH `/api/users/me/plan`** — `MyPlanUpdateRequest` → `UserResponse`

```json
{ "planTier": "PLUS" }
```

- 값은 `FREE` / `PLUS` / `PRO`. 그 외는 `400`
- **결제 없이 즉시 바뀐다.** 결제 연동 전까지의 임시 동작이다. 만료 시각은 두지 않는다
- JWT 가 아니라 DB 값이라 **재로그인 없이 바로 반영**된다. 변경 직후 `GET /api/chat/quota` 를 부르면 새 한도가 보인다
- 오늘 이미 쓴 횟수는 유지된다. FREE 에서 5회 쓰고 PLUS 로 바꾸면 남은 횟수는 95회다
- 같은 요금제로 다시 바꿔도 오류가 아니다

**PATCH `/api/users/me`** — `ProfileUpdateRequest` (보낸 필드만 수정)

```json
{ "nickname": "새닉네임", "profileImageUrl": "https://...",
  "phone": "010-0000-0000", "interestCategories": ["패션"] }
```

**`UserStatsResponse`**

```json
{ "wishlistCount": 5, "feedbackCount": 12, "reportCount": 2, "unreadNotificationCount": 3 }
```

**`UserSettingResponse`** / **`UserSettingUpdateRequest`** (14개 필드, PATCH는 `null` 무시)

| 필드 | 타입 | 설명 |
|------|------|------|
| `notifyRiskyProduct` | boolean | 위험 상품 알림 |
| `notifyAnalysisComplete` | boolean | 분석 완료 알림 |
| `notifyFeedbackResult` | boolean | 피드백 결과 알림 |
| `notifyMarketing` | boolean | 마케팅 수신 |
| `rtiThreshold` | int | RTI 경고 임계값 |
| `hideRiskyReviews` | boolean | 위험 리뷰 숨김 |
| `showSuspiciousLabel` | boolean | 의심 라벨 표시 |
| `prioritizeVerifiedReviews` | boolean | 구매인증 리뷰 우선 |
| `autoOpenAnalysisPopup` | boolean | 분석 팝업 자동 열기 |
| `cardDensity` | String | 카드 밀도 |
| `reviewSortOrder` | String | 리뷰 정렬 기준 |
| `rtiLabelStyle` | String | RTI 라벨 표기 |
| `theme` | String | 테마 |
| `allowDataAnalysis` | boolean | 데이터 분석 동의 |

**`UserSecurityResponse`**

```json
{ "emailVerified": true, "twoFactorEnabled": false, "loginMethod": "LOCAL",
  "passwordLastChanged": "2026-08-01T00:00:00", "termsAgreed": true,
  "notificationPermissionGranted": true }
```

---

## 9. 온보딩 (`/api/onboarding`) — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/onboarding/categories` | 선택 가능 카테고리 |
| GET | `/api/onboarding/preferences` | 내 선호 설정 |
| POST | `/api/onboarding/preferences` | 선호 설정 저장 |

```json
{ "preferredCategories": ["FASHION", "BEAUTY"], "minTrustScore": 60 }
```

---

## 10. 찜 · 장바구니 — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/wishlist` | 찜 목록 |
| POST | `/api/wishlist/{productId}` | 찜 추가 |
| DELETE | `/api/wishlist/{productId}` | 찜 해제 |
| GET | `/api/wishlist/{productId}/check` | 찜 여부 확인 |
| GET | `/api/cart` | 장바구니 조회 |
| POST | `/api/cart/{productId}` | 장바구니 추가 |
| PUT | `/api/cart/{productId}` | 수량 변경 |
| DELETE | `/api/cart/{productId}` | 항목 삭제 |
| DELETE | `/api/cart` | 전체 비우기 |

**`CartSummaryResponse`**

```json
{ "items": [ ], "totalCount": 3, "subtotal": 89700,
  "shippingFee": 3000, "totalPrice": 92700 }
```

### 10-1. 장바구니 예약 (`/api/cart-schedules`) — **인증 필요** (#269)

챗봇이 만든 예약 제안(`scheduleProposal`)을 확정하고, 확정한 예약을 보거나 취소한다.
확정된 예약은 서버가 5분마다 확인해 정한 시각(한국 시간)이 지나면 장바구니에 담고 알림(`SYSTEM`)을 보낸다.

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/cart-schedules/{id}/confirm` | 제안 확정. 24시간이 지났거나 이미 확정·취소했거나 진행 중 예약이 20개이거나 같은 상품에 진행 중 예약이 있으면 `409 CART_SCHEDULE_NOT_CONFIRMABLE` |
| GET | `/api/cart-schedules` | 진행 중인 예약. 다음에 담을 시각이 이른 순 |
| DELETE | `/api/cart-schedules/{id}` | 예약 취소. 이미 끝났거나 취소한 예약이면 그대로 200 |

남의 예약은 `403 CART_SCHEDULE_FORBIDDEN`, 없는 예약은 `404 CART_SCHEDULE_NOT_FOUND` 다.

**`CartScheduleResponse`**

```json
{ "id": 55, "productId": 643763393047927, "productName": "텀블러", "externalId": "kurly-1000146248",
  "summary": "매주 월요일 오전 9시 · 1개", "repeatType": "WEEKLY",
  "nextRunAt": "2026-11-23T09:00:00", "status": "ACTIVE" }
```

- `repeatType`: `ONCE` / `WEEKLY` / `MONTHLY`
- `status`: `PROPOSED`(확정 전) / `ACTIVE` / `DONE`(한 번 예약 완료) / `CANCELLED`
- `nextRunAt` 은 한국 시간이다. 놓친 회차(서버 점검 등)는 몰아서 담지 않고 다음 회차로 넘긴다
- 예약이 담을 때 장바구니에 같은 상품이 있으면 수량을 더한다

### 10-2. 반복 담기 추천 (`/api/repurchase`) — **인증 필요** (#271)

사용자가 반복해서 장바구니에 담는 상품 가운데 다시 살 때가 된 상품이다. 메인 "다시 살 때 된 상품" 섹션에 쓴다.
구매 데이터가 없어 장바구니 담기 기록(직접 담기 + 예약 담기)을 근거로 쓴다. LLM 을 쓰지 않는다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/repurchase` | 다시 살 때 된 상품. 늦어진 순, 최대 10개. 없으면 `[]` (섹션을 숨긴다) |
| POST | `/api/repurchase/{productId}/schedule` | 예약 제안대로 바로 확정 예약(`ACTIVE`)을 만든다. `201`, 응답은 `CartScheduleResponse` |

판단 규칙
- 최근 180일 안에 서로 다른 날 2번 이상 담은 상품이 후보다. 같은 날 여러 번 담은 기록은 하루로 센다
- 주기 = 담은 날 간격의 중앙값(일). 마지막으로 담은 지 주기의 80% 이상 지나면 다시 살 때다
- 진행 중(`ACTIVE`) 예약이 걸린 상품은 빠진다
- 예약 제안: 주기 5~9일이면 매주(마지막으로 담은 요일), 25~35일이면 매월(마지막으로 담은 날), 오전 9시, 1개. 그 밖이면 `null`

```json
[{ "productId": 643763393047927, "externalId": "kurly-1000146248", "name": "생수 2L 6병",
   "thumbnailUrl": "https://...", "price": 3000, "intervalDays": 7, "lastAddedOn": "2026-11-14",
   "daysSinceLast": 9, "timesAdded": 4, "reason": "약 7일마다 담으셨고 마지막은 9일 전이에요",
   "scheduleSuggestion": { "repeatType": "WEEKLY", "summary": "매주 토요일 오전 9시 · 1개" } }]
```

- 예약 걸기는 화면이 보낸 값이 아니라 서버가 지금 다시 계산한 제안으로 만든다. 다시 살 때가 아니거나, 제안이 없거나, 이미 예약이 있거나, 진행 중 예약이 20개면 `409 REPURCHASE_NOT_SCHEDULABLE`
- 챗봇도 같은 후보(아직 때가 아닌 상품 포함, 최대 5개)를 프롬프트에 실어 "다시 살 거 있어?" 같은 질문에 답한다 (7절)

---

## 11. 리뷰 피드백 · 신고 — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/reviews/{reviewId}/feedback` | 리뷰 피드백 (REAL/FAKE) |
| GET | `/api/reviews/feedbacks/me` | 내 피드백 목록 |
| GET | `/api/reviews/feedbacks/me/{feedbackId}` | 내 피드백 상세 |
| POST | `/api/reports/reviews/{reviewId}` | 리뷰 신고 |
| GET | `/api/reports/me` | 내 신고 목록 |
| GET | `/api/reports/me/{reportId}` | 내 신고 상세 |
| POST | `/api/analysis-feedbacks/reviews/{reviewId}` | 분석 결과 피드백 (Spring 리뷰) |
| POST | `/api/analysis-feedbacks/external/{platform}/{productId}/reviews/{externalReviewId}` | 분석 결과 피드백 (Data 서버 리뷰, 6-3 절) |
| GET | `/api/analysis-feedbacks/me` | 내 분석 피드백 목록 |
| GET | `/api/analysis-feedbacks/me/{feedbackId}` | 내 분석 피드백 상세 |
| GET | `/api/feedback/me` | **통합** 피드백 현황 (신고 + 분석 피드백) |

**POST `/api/reports/reviews/{reviewId}`** — `ReportCreateRequest`

```json
{ "reason": "ADVERTISEMENT", "detail": "20자 이상 500자 이내 상세 사유",
  "attachmentUrl": null, "includeAiEvidence": true }
```

`detail` 은 20~500자 제약이 있다.

**GET `/api/feedback/me`** → `Page<UnifiedFeedbackResponse>` — 신고와 분석 피드백을 한 화면에 보여줄 때 사용

```json
{ "id": 1, "feedbackCategory": "REPORT", "typeLabel": "광고성 리뷰",
  "productName": "베이직 니트", "reviewContent": "...",
  "status": "UNDER_REVIEW", "statusDescription": "검토 중",
  "currentStep": 2, "totalSteps": 4, "createdAt": "2026-09-01T10:00:00" }
```

---

## 12. 알림 (`/api/notifications`) — **인증 필요**

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/notifications/me` | 내 알림 목록 (페이징) |
| GET | `/api/notifications/me/unread-count` | 안 읽은 알림 수 |
| PATCH | `/api/notifications/{notificationId}/read` | 단건 읽음 |
| PATCH | `/api/notifications/me/read-all` | 전체 읽음 |

**`NotificationResponse`**

```json
{ "notificationId": 1, "type": "ANALYSIS_COMPLETE", "typeDescription": "AI 분석 완료",
  "title": "AI 분석이 완료되었습니다", "message": "'베이직 니트'의 분석 결과를 확인하세요.",
  "isRead": false, "targetUrl": "/products/7195971829",
  "createdAt": "2026-09-30T10:00:00" }
```

`targetUrl` 은 알림 클릭 시 이동할 프론트 경로다.

**알림 타입**

| 타입 | 설명 |
|------|------|
| `REPORT_RECEIVED` / `REPORT_UNDER_REVIEW` / `REPORT_ACCEPTED` / `REPORT_REJECTED` | 신고 처리 단계 |
| `ANALYSIS_FEEDBACK_RECEIVED` / `_UNDER_REVIEW` / `_RESOLVED` / `_REJECTED` | 분석 피드백 처리 단계 |
| `ANALYSIS_COMPLETE` / `ANALYSIS_FAILED` | AI 분석 완료·실패 |
| `RISKY_PRODUCT_DETECTED` | 위험 상품 감지 |
| `SYSTEM` | 시스템 공지 |

> 알림 발송은 `UserSetting` 의 대응 항목이 꺼져 있으면 생성되지 않는다.

---

## 13. 관리자 (`/api/admin`) — **`ROLE_ADMIN` 필요**

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/admin/dashboard` | 운영 대시보드 통계 |
| GET | `/api/admin/reviews/suspicious` | 의심 리뷰 목록 |
| GET | `/api/admin/reports` | 전체 신고 목록 |
| PATCH | `/api/admin/reports/{reportId}` | 신고 상태 변경 |
| GET | `/api/admin/analysis-feedbacks` | 전체 분석 피드백 |
| PATCH | `/api/admin/analysis-feedbacks/{feedbackId}` | 피드백 검수 |
| GET | `/api/admin/users` | 회원 목록 |
| PATCH | `/api/admin/users/{userId}/plan` | 회원 챗봇 요금제 변경 |
| GET | `/api/admin/model-performance` | AI 모델 성능 통계 |

권한이 없으면 403. JWT 의 `role` 클레임이 `ADMIN` 이어야 한다.

**PATCH `/api/admin/users/{userId}/plan`** — `AdminPlanUpdateRequest` → `AdminUserResponse`

결제 연동 전까지 챗봇 요금제를 부여하는 유일한 경로다.

```json
{ "planTier": "PLUS", "expiresAt": "2026-12-31T23:59:59" }
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `planTier` | ✅ | `FREE` / `PLUS` / `PRO` |
| `expiresAt` | | 비우면 무기한. `FREE` 로 내리면 무시되고 비워진다 |

- 변경은 **즉시** 반영된다. 요금제는 JWT 가 아니라 DB 값이라 재로그인이 필요 없다.
- 응답의 `planTier` 는 **저장된 값**이다. 만료가 지나 `FREE` 로 동작 중이어도 원래 등급이 그대로 보이므로, 만료 여부는 `planExpiresAt` 으로 판단한다. (사용자용 `GET /api/users/me` 는 반대로 지금 적용 중인 등급을 준다)

---

## 14. 내부 API (`/api/internal`) — 서버 간 전용

프론트엔드는 호출하지 않는다. `X-Service-Token` 헤더로 인증하며, Data 서버가 분석 완료를 알릴 때 사용한다. 규격은 [webhook-contract.md](webhook-contract.md) 참고.

---

## 15. 알아두면 좋은 것

**비로그인 401 포맷** — 인증이 필요한 API 를 토큰 없이 호출하면 스프링 기본 401 이 아니라 위의 공통 포맷으로 응답한다.

```json
{ "success": false, "message": "로그인이 필요합니다.", "errorCode": "UNAUTHORIZED" }
```

**리버스 프록시** — nginx 가 `X-Forwarded-*` 를 넘기고 서버가 이를 신뢰하므로, OAuth2 `redirect_uri` 등이 외부 도메인 기준으로 생성된다.

**세션 쿠키** — `Secure`, `HttpOnly`. HTTPS 에서만 동작한다.

**향후 변경 예정** — Data 서버 연동이 완료되면 아래가 제거되거나 Data 서버로 이동한다. 신규 개발 시 참고할 것.

| 대상 | 이동처 |
|------|--------|
| `/api/products/**` | Data 서버 |
| `/api/reviews/**` (조회) | Data 서버 |
| `/api/search` | Data 서버 |
| `/api/analysis/**` | Data 서버 |
| `/api/dashboard/**` | Data 서버 |
