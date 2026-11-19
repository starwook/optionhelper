# optionhelper

미국 주식옵션(반도체 중심) 판단용 조회 대시보드. 매매는 토스증권 앱에서 하고, 이 앱은 IV·예상 변동폭·전략별 손익분기·실적일·계절성을 보여준다.

## 먼저 읽을 문서
- `docs/기획서.md` — 확정된 결정, 원칙, 신호 규칙표, 미결 사항
- `docs/데이터소스-조사.md` — 외부 데이터 API 사양(있을 때). 사양은 추측하지 말고 여기서 확인
- `README.md` — 로컬 실행 방법

## 규칙
- 스택: Kotlin + Spring Boot + JPA + MySQL + Gradle + ktlint. 패키지는 도메인 기준(`pricing`, `strategy`, `volatility`, `signal`, `earnings`, `seasonality`, `marketdata`, `dashboard` ...).
- 주문 기능과 토스 화면 스크래핑은 넣지 않는다.
- 외부 데이터는 `marketdata.MarketDataSource` 인터페이스 뒤에 둔다(데이터 소스 교체가 구현체 교체로 끝나게).
- 계산 클래스(`pricing`, `strategy`, `volatility`, `signal`, `seasonality`, `earnings`)는 Spring·DB 없이 테스트할 수 있게 순수 함수로 둔다.
