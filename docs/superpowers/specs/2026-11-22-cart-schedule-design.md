# 챗봇 장바구니 예약 설계 (#269)

## 목표
상품 대화에서 사용자가 날짜를 정해 말하면, 그날(또는 매주·매월) 상품을 장바구니에 자동으로 담는다.
같이 장바구니 담기 이력을 쌓아 정기 구매 개인화 추천(별도 이슈)의 근거로 쓴다.

## 결정 사항
| 항목 | 결정 | 이유 |
|---|---|---|
| 예약 형태 | 한 번(`ONCE`) + 매주(`WEEKLY`) + 매월(`MONTHLY`) | 반복 예약이 정기 구매의 가장 확실한 신호다 |
| 의도 해석 | 기존 응답 머리 줄에 `SCHEDULE` 줄 추가 | LLM 추가 호출 없음. 기존 ONTOPIC/RECOMMEND 방식과 같다 |
| 확정 | 챗봇은 제안(`PROPOSED`)만 만들고, 사용자가 버튼으로 확정(`ACTIVE`) | 잘못 해석해도 원치 않는 상품이 담기지 않는다 |
| 상태·유형 칼럼 | 문자열 | enum 칼럼은 `ddl-auto=update`에서 체크 제약이 갱신되지 않는다(#205) |
| 알림 | 기존 `NotificationType.SYSTEM` | 새 enum 값은 운영 DB 체크 제약에 막힌다 |
| 시간대 | 한국 시간(`Asia/Seoul`), `Clock` 빈 주입 | 테스트에서 "지금"을 고정한다 |

## 데이터
`cart_schedules`
- `user_id`, `product_id`(Spring 상품 번호), `quantity`(1~10)
- `repeat_type`: `ONCE` / `WEEKLY` / `MONTHLY`
- `run_date`(ONCE), `day_of_week`(WEEKLY, 1=월~7=일), `day_of_month`(MONTHLY, 1~31. 없는 날은 말일), `run_time`
- `next_run_at`: 다음 실행 시각(한국 시간 LocalDateTime). 실행 작업은 이 칼럼만 본다
- `status`: `PROPOSED` → `ACTIVE` → `DONE`(ONCE 완료) / `CANCELLED`
- `created_at`, `last_run_at`

`cart_events` (추가만 한다)
- `user_id`, `product_id`, `quantity`, `source`(`MANUAL` / `SCHEDULE`), `created_at`

## 챗봇 흐름
1. 시스템 프롬프트에 오늘 날짜·요일과 `[장바구니 예약]` 규칙을 넣는다.
2. 모델은 RECOMMEND 줄 다음에 `SCHEDULE:` 줄을 둔다.
   - `SCHEDULE: none`
   - `SCHEDULE: once 2026-12-01 09:00 qty=1`
   - `SCHEDULE: weekly MON 09:00 qty=1`
   - `SCHEDULE: monthly 1 09:00 qty=1`
3. `ScheduleRequest.parse`가 엄격히 검사한다. 과거·1년 초과 날짜, 수량 1~10 밖, 모르는 요일, 형식 오류면 제안하지 않는다(fail-closed). 줄이 없어도 기존처럼 동작한다.
4. 주제 안 답변이고 상품 대화이며 진행 중 예약이 20개 미만이면 `PROPOSED`로 저장하고 `ChatResponse.scheduleProposal`에 싣는다.
5. 상품 번호표가 없으면 찜과 같은 방식(`ProductTagRegistry.resolveOrCreate`)으로 만든다.

## API
- `POST /api/cart-schedules/{id}/confirm`: 본인 `PROPOSED`이고 만든 지 24시간 안이면 `ACTIVE`
- `GET /api/cart-schedules`: 본인의 `ACTIVE` 예약
- `DELETE /api/cart-schedules/{id}`: 본인 예약을 `CANCELLED`

## 자동 담기 작업
- 5분마다 `status=ACTIVE AND next_run_at <= now` 를 최대 100건 처리한다.
- 예약마다 트랜잭션 하나: `next_run_at` 이 읽은 값 그대로일 때만 다음 값으로 바꾸는 조건부 갱신으로 중복 실행을 막는다.
- 장바구니 담기(있으면 수량 더함) → `cart_events`(`SCHEDULE`) → 알림.
- ONCE는 `DONE`. 반복은 지금 이후의 가장 가까운 회차로 넘긴다. 놓친 회차를 몰아서 실행하지 않는다.
- 한 예약의 실패는 기록하고 다음 예약을 계속 처리한다.

## 장바구니 이력
- `CartService.addToCart` 가 담을 때마다 `cart_events`(`MANUAL`) 한 줄을 추가한다.

## 테스트
- 다음 실행 시각 계산(순수 단위 테스트): 요일·말일·시각 경계, 놓친 회차
- `ScheduleRequest` 파서: 정상 형식, 형식 오류, 경계값
- `LlmAnswer`: SCHEDULE 줄 읽기, 없을 때 기존 동작
- 서비스·리포지토리: 제안·확정·취소·권한·만료, 실행 작업의 담기·이력·알림·조건부 갱신
- 챗봇: 주제 안 답변에서만 제안이 실린다
