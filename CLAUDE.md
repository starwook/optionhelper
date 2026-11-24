# optionhelper

미국 주식옵션(반도체 중심) 판단용 조회 대시보드. 매매는 토스증권 앱에서 하고, 이 앱은 IV·예상 변동폭·전략별 손익분기·실적일·계절성을 보여준다.

## 먼저 읽을 문서
- `docs/기획서.md` — 확정된 결정, 원칙, 신호 규칙표, 미결 사항
- `docs/외부API-명세.md` — 코드가 부르는 외부 API 요청·응답 필드. 사양은 추측하지 말고 여기서 확인, 비어 있으면 조사 요청
- `docs/데이터소스-조사.md` — 데이터 소스 선택 근거(무료 여부, 비교)
- `README.md` — 로컬 실행 방법

## 규칙
- 스택: Kotlin + Spring Boot + JPA + MySQL + Gradle + ktlint. 패키지는 도메인 기준(`pricing`, `strategy`, `volatility`, `scenario`, `signal`, `earnings`, `seasonality`, `marketdata`, `dashboard` ...).
- 주문 기능과 토스 화면 스크래핑은 넣지 않는다.
- 외부 데이터는 `marketdata.MarketDataSource` 인터페이스 뒤에 둔다(데이터 소스 교체가 구현체 교체로 끝나게).
- 계산 클래스(`pricing`, `strategy`, `volatility`, `scenario`, `signal`, `seasonality`, `earnings`)는 Spring·DB 없이 테스트할 수 있게 순수 함수로 둔다.

## 머지 후 앱 재기동
- main에 머지하고 로컬 main 체크아웃을 `git pull --ff-only origin main`으로 최신화한 뒤에는, 묻지 않고 앱을 8080 포트에 다시 올린다(2026-11-25 요청, 전역 "앱 실행" 규칙의 예외).
- 순서: 8080을 쓰는 기존 프로세스 종료 → 로컬 main 체크아웃에서 `./gradlew bootRun`을 백그라운드로 실행 → 8080이 응답하는지 확인하고 알린다.
