# optionhelper

미국 주식옵션(반도체 중심) 판단용 **조회 전용** 공개 대시보드. 주가는 Hyperliquid 주식 추종 선물 가격, 옵션 호가·IV·그릭스는 모두 블랙-숄즈로 계산한 **이론가(근사값)**다. 실제 시세(Alpaca·IBKR)는 재배포가 안 돼 쓰지 않는다(소유자 로그인 + 실제 시세는 TODO). 매매는 토스증권 앱에서 하고, 이 화면은 옆 PC·태블릿에 띄워 "옵션이 비싼지, 시장이 몇 % 움직인다고 보는지, 어떤 전략이 맞는 구간인지"를 본다. 주문 기능은 없다.

기획과 결정 사항은 [docs/기획서.md](docs/기획서.md), 데이터 소스 근거는 [docs/데이터소스-조사.md](docs/데이터소스-조사.md).

## 실행

1. 로컬 MySQL에 DB를 만든다.
   ```sql
   CREATE DATABASE optionhelper CHARACTER SET utf8mb4;
   ```
2. `src/main/resources/application-local.yml.example`을 `application-local.yml`로 복사해 DB 계정과 키를 채운다(git에 올라가지 않는다).
   - 시세는 키가 필요 없다(Hyperliquid 공개 API, 기본값). 네트워크 없이 보려면 `optionhelper.market-data.source: fake`.
   - 예전 설정 `source: alpaca`가 남아 있으면 앱이 뜨지 않는다. 그 줄을 지우거나 `hyperliquid`로 바꾼다.
3. IntelliJ에서 `OptionHelperApplication`을 실행하고 http://localhost:8080 을 연다.

테스트: `./gradlew test ktlintCheck`

## 데이터 소스 설정 (`application-local.yml`)

| 설정 | 값 | 설명 |
|---|---|---|
| `optionhelper.market-data.source` | `hyperliquid`(기본) / `fake` | 기초자산 가격. 옵션은 둘 다 이론가 |
| `optionhelper.market-data.hyperliquid.dex` | `xyz` | Hyperliquid 배포자(종목 심볼 앞에 붙는 `xyz:`) |
| `optionhelper.earnings.source` | `fake` / `alphavantage` | 실적발표일 |
| `optionhelper.earnings.background-refresh` | `true` | 관심 종목 실적 일정을 백그라운드로 주 1회 갱신 |
| `optionhelper.recorder.symbols` | `NVDA` | 장중 1분마다 체인을 저장할 종목(IV 퍼센타일·추이·복기용) |
| `optionhelper.pricing.risk-free-rate` | `0.04` | 블랙-숄즈 무위험금리 |

### 키 발급
- **Alpha Vantage(무료, 하루 25회)**: https://www.alphavantage.co/support/#api-key 에서 이메일로 발급 → `api-key`에 넣는다. 앱은 관심 종목 실적 일정을 백그라운드로 종목당 일주일에 한 번만 부른다(10종목 × 2회).

### IBKR (TODO: 소유자 로그인 시에만 쓰는 실제 시세)
실시간 OPRA 호가(월 $1.50)용. 재배포가 안 되므로 공개 화면에는 쓰지 않고, 로그인 기능과 함께 붙인다. 계좌가 열리면 아래를 준비해 주면 `marketdata/ibkr` 구현을 추가한다.
1. IBKR Pro 계좌, 순자산 $500 이상, 시세 구독: OPRA Top of Book(L1), 무료 Cboe One + IEX
2. IB Gateway 설치 → API 설정에서 소켓 접속 허용, **Read-Only API 체크**(주문 원천 차단)
3. IBKR 사이트에서 TWS API(Java) 내려받기 → `TwsApi.jar`를 `libs/`에 둔다(Maven에 없음)
4. **IBKR 계좌에서는 매매하지 않는다**(해외 파생상품은 국내 증권사를 통해야 함, 기획서 원칙)

## 화면 항목

| 항목 | 뜻 |
|---|---|
| 현재가 / 전일 종가 대비 | 기초자산 가격과 전일 종가 대비 등락률(증권사 앱과 같은 기준). 시가는 쓰지 않는다. 장 밖이면 체결 시각 표시 |
| 만기까지 | 만기(미국 동부 16:00)까지 남은 **장중 시간**, 만기 시각 ET·KST |
| 스트래들 | ATM 콜 중간가 + 풋 중간가. 1세트 = × 100달러 |
| 예상 변동폭 | 스트래들 ÷ 주가(%, $). 85% 규칙 = 스트래들 × 0.85(약 1표준편차) |
| ATM IV | 중간가로 블랙-숄즈를 역산한 값(직접 계산)과 데이터 소스 값(API). 0DTE는 API 값이 비는 경우가 많다 |
| IV / HV(20일) | 옵션 IV ÷ 최근 20일 실제 변동성. 1보다 크면 옵션이 최근 움직임보다 비싸다 |
| IV 퍼센타일 | 쌓인 스냅샷 중 지금보다 IV가 낮았던 비율. 기록이 쌓일수록 정확하다 |
| 판단 패널 | 기획서 4장 규칙표에 따른 전략 구간(참고 지표, 투자 조언 아님), 실현 vs 예상, 다음 실적일(과거 실적 반응은 신호 계산에만 사용) |
| ATM IV 추이 | 오늘 서버에 저장된 기록 + 페이지를 켜 둔 동안의 값 |
| 기간 구조 / 스큐 | 이번 만기 IV ÷ 다음 만기 IV(1보다 크면 단기 이벤트 프리미엄), 2칸 OTM 풋 IV − 콜 IV |
| 콜·풋 체인 | ATM 위아래 10칸의 매수·매도·중간가, IV(API/직접), 델타 |
| 전략 비교 | 롱 스트래들, 롱 스트랭글(±1·±2칸), 숏 풋, 불 콜·베어 풋·크레딧 스프레드의 1계약 비용, 토스 수수료(계약당 $1.99 × 진입·청산) 포함 본전, 최대 손익. 행을 누르면 만기 손익 곡선 |
| 과거 실적 반응 | 발표 직전 종가 → 반응일 종가 변동(장 전 발표는 그날, 장 후 발표는 다음 날). 화면에는 안 보이고 신호 계산에 쓴다 |

## 일정 화면 (상단 "일정" 버튼)

종목과 상관없는 월 달력. 한 날짜 칸에 경제지표(FOMC·CPI·PPI·고용·PCE, 한국 시간)와 관심 종목 실적발표(장전/장후)를 같이 보여 준다. 날짜는 미국 기준. 아래에 나스닥100 월별 수익률(Hyperliquid `xyz:XYZ100` 선물 일봉 기준)(평균·상승 확률·연도별, 이번 달 강조).

## 복기 화면 (`/review.html`, 상단 "복기" 링크)

날짜를 고르면 그날 장중 1분마다 저장된 시각 목록이 나온다. 시각을 누르면 그때의 주가·스트래들·예상 변동폭·체인을 다시 보여 주고, 그때 각 전략을 1계약씩 샀다면 만기(지났으면 만기일 종가, 아니면 지금 가격)에 얼마였는지(토스 수수료 왕복 포함)와 "실제 변동 ÷ 예상 변동폭"을 보여 준다. 기록은 `optionhelper.recorder.symbols` 종목만, 앱을 켜 둔 장중에만 쌓인다.

## IV 히스토리 화면 (`/iv.html`, 상단 "IV 히스토리" 링크)

- **지금 IV가 1년 중 어디쯤인가**: IV Rank(1년 최저 0% ~ 최고 100% 중 위치), 퍼센타일(1년 중 지금보다 낮았던 날 비율), 1년 범위·중앙값
- **실제 변동보다 비싼가**: IV ÷ HV20, 1년 IV와 HV20 선 그래프
- **보유 옵션 시나리오**: 종류·행사가·만기·옵션 가격(토스에서 보는 값)·주가를 넣으면 IV·델타·하루 세타·베가와, 주가가 그대로일 때 날짜별(1주 간격, 만기 전날, 만기) × IV별(지금 / 종목 IV가 1년 중앙값·최저로 돌아갈 때 / ±10%p) 가격 표
- **관심 종목 비교**: 반도체 10종목의 IV·Rank·퍼센타일·IV÷HV

IV 기록은 데이터 소스가 주는 일별 IV를 `daily_iv` 테이블에 소스별로 쌓아 쓴다(한 번 받은 날짜는 다시 안 받음). 지금 소스는 날마다 그날까지의 HV20으로 정한 이론가 ATM IV라서, IV Rank·퍼센타일은 사실상 HV의 위치다. 그것도 없으면 앱이 장중에 쌓은 스냅샷의 하루 마지막 ATM IV로 대신한다(스냅샷도 소스별로 나눠 읽는다). 어느 쪽인지 화면에 표시된다. API: `/api/iv/history?symbol=`, `/api/iv/summary`, `/api/scenario?symbol=&type=CALL&strike=&expiry=&price=&spot=`.

## 구조

도메인 기준 패키지: `pricing`(블랙-숄즈·IV·만기 시계), `option`(체인·스냅샷), `strategy`(전략 손익·수수료), `volatility`, `signal`, `earnings`, `seasonality`, `marketdata`(데이터 소스 인터페이스와 구현), `scenario`(보유 옵션 시나리오), `watchlist`, `dashboard`(화면 조립·API·스냅샷 기록). 화면은 `src/main/resources/static/`의 `index.html`(실시간), `iv.html`(IV 히스토리), `review.html`(복기).
