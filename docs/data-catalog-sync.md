# 검색 상품·분석 카탈로그 연결

검색 결과의 신규 상품을 Data DB에 등록하고 pending 상한20건 안에서 수집을 예약한다.
상한 밖의 상품은 등록만 하고5분 스케줄러가 예약한다. 기존 상세·수집 시각을 덮지 않고
플랫폼 제외·실패6시간 대기·같은 입력 분석 재사용을 유지한다.

GET /api/v1/catalog?limit=100&cursor=... 은 platform/product_id 순의 keyset 목록이다.
내부 토큰이 필요하다. items에는 product와 analysis를 담고 next_cursor를 제공한다.
analysis에는 status/job_id/avg_rti/scored_review_count/review_count/source_review_count/
sampled/model_version/policy_version을 제공한다. avg_rti는 최신 done의 제공된 RTI만
평균한다. NULL은 제외하고0은 포함한다. 입력·모델·정책 변경 또는 미완료 상태에는
이전 점수를 내리지 않는다. 분석 건수는 실제 선택된 표본, 원본 건수는 저장된 전체 리뷰다.
단일 SQL snapshot으로 상품·작업·집계 상태를 일관되게 읽고 원본 리뷰 본문은 보내지 않는다.

Spring은 기동 시와5분마다 페이지를 읽어 표시 캐시를 동기화한다. 외부 호출은 DB
트랜잭션 밖에서 하고 페이지 저장만 묶는다. 실패 시 기존 캐시를 유지하고 다음 주기에
다시 읽는다. 진행·실패·stale는 이전 점수를 지우며0/NULL과 표본 여부를 보존한다.
표시 정보와 분석 요약은 서로의 컬럼을 덮지 않는다. 홈은 완료 상품을 우선한다.

기존 Spring HomeCatalogRefresher의6시간 분야별 검색을 사용한다. 기본26개 키워드,
4개 HTTP 기반 플랫폼, 몰당3건을 검색하고 Data 등록·수집·분석으로 이어진다.
검색 실패는 플랫폼·키워드별로 격리하고 화면 전체를 실패시키지 않는다. 별도 발굴
워커를 중복 생성하지 않는다. 네이버 브랜드스토어는 기존 지정 상품·관련 상품 시드를
유지하며 옥션·G마켓·차단 상품은 우회하지 않는다.

Flutter 홈·검색 배지는 실제 점수와 분석 대기/분석 중/실패/재분석 필요/점수 없음을
구분한다. 표본이면 표본 RTI로 표시하며 완료되지 않은 상태의 옛 점수는 표시하지 않는다.

운영은 DATA_CATALOG_SYNC_ENABLED=true(기본), 주기300000ms다. Data 배포 후 Spring을 배포한다.
목록 필드 analysisSampled/analysisReviewCount/analysisSourceReviewCount는 nullable 추가 컬럼이다.
analysis_scored_review_count(점수가 나온 리뷰 수)도 nullable 추가 컬럼이다. 응답에는 내리지 않고 대시보드가 리뷰가 충분한지 볼 때 쓴다. 기존 행은 다음 동기화(최대 5분)에서 채워지고, 그 전까지는 대시보드에 오르지 않는다.
v4.0 목록 필드 avgPolicyIndexV4·v4LevelCounts(#255)도 nullable 추가 컬럼(avg_policy_indexv4, v4safe_count, v4warn_count, v4danger_count)이다. 숫자 뒤 대문자에는 밑줄이 붙지 않는 Spring 기본 이름 규칙 때문이다.
Data 가 분석을 v4.0 하나로 바꾸며(#273) `avg_rti`·`avg_policy_index_v3` 를 보내지 않는다. `scored_review_count` 는 v4 결과가 있는 리뷰 수(`v4_level_counts` 합)다.
Spring 은 `avg_policy_index_v4` 로 평균을 검증한다(점수 매긴 리뷰가 있는데 평균이 없거나 0~100 밖이면 상품을 건너뛴다). 통과한 평균은 `avg_policy_indexv4` 와 함께 `avg_rti` 에도 적는다. `avg_rti` 는 목록 등급·대시보드 추천·챗봇 상품 평균이 읽는 상품 대표 점수이기 때문이다.
등급 분포가 이상하면 상품은 그대로 저장하고 분포만 비운다. 완료가 아닌 상태가 되면 평균·분포를 함께 지운다.
기존 행은 첫 동기화로 채우며 원본 리뷰·리뷰별 점수는 Data에 유지한다.

한 번 실행에 최대 100페이지(1만 건)를 읽는다. 다음 페이지가 남으면 다음 실행(5분 뒤)이 그 자리부터 이어 읽고, 끝까지 읽으면 다시 처음부터 읽는다. 실패하거나 페이지 이동이 이상하면 처음부터 다시 읽는다.

사용자 검색(`GET /api/products?keyword=`)은 Data 실시간 몰 검색이 0건이면 이 동기화로 저장된 상품 중 Data 에 리뷰가 있는 것(`analysisSourceReviewCount > 0`)을 이름으로 찾아 보여준다. 운영자가 수집을 멈추면 Data 는 몰 검색을 거절하지만, 이미 모은 상품은 검색되게 하려는 것이다. 홈 자동 채우기는 이 대체 결과를 쓰지 않는다.
