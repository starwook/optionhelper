# 외부 API 명세

코드가 실제로 부르는 요청·응답만 적는다. 소스 선택 근거(무료 여부, 비교)는 `docs/데이터소스-조사.md`.
확인 수준: **공식** = 공식 문서 확인, **실측** = 실제 응답으로 확인, **미확인** = 아직 근거 없음(코드는 이름으로 찾고 없으면 비워 둔다).
새 API를 붙이거나 필드를 바꾸면 이 문서를 먼저 고친다. 비어 있는 항목은 추측하지 말고 조사해서 채운다.

## 1. Alpaca Market Data (`marketdata/alpaca`)

- 기본 URL `https://data.alpaca.markets`, 헤더 `APCA-API-KEY-ID`, `APCA-API-SECRET-KEY`
- 한도: 무료 분당 200회. 옵션 스냅샷 응답당 최대 1,000계약. 무료 플랜은 최근 15분 과거 데이터 조회 불가. (공식)

| 용도 | 요청 | 쓰는 파라미터 | 쓰는 응답 필드 | 확인 |
|---|---|---|---|---|
| 현재가 | `GET /v2/stocks/{symbol}/snapshot` | `feed=iex` | `latestTrade.p`, `latestTrade.t`, `dailyBar.o`, `prevDailyBar.c` | 공식 |
| 만기 목록·체인 | `GET /v1beta1/options/snapshots/{underlying}` | `feed=indicative`, `limit=1000`, `page_token`, `expiration_date` 또는 `expiration_date_gte/lte`, `strike_price_gte/lte` | `snapshots{OCC심볼: {latestQuote.bp, latestQuote.ap, impliedVolatility, greeks.delta}}`, `next_page_token` | 공식 |
| 일봉 | `GET /v2/stocks/bars` | `symbols`, `timeframe=1Day`, `start`, `end`, `limit=10000`, `adjustment=all`, `feed`, `page_token` | `bars{심볼: [{t, o, h, l, c}]}`, `next_page_token` | 공식 |

- 옵션 심볼은 OCC 형식(`NVDA251219C00180000`) → `OccSymbol.parse`.
- 0DTE·깊은 OTM은 `impliedVolatility`, `greeks`가 빠질 수 있다(공식 FAQ). 앱이 블랙-숄즈 역산으로 채운다.
- 일봉 제공 시작 시점(2016년 전후): 미확인 → 키로 실측.

## 2. Alpha Vantage (`earnings/AlphaVantage*`)

- 기본 URL `https://www.alphavantage.co/query`, 파라미터 `apikey`
- 한도: 무료 하루 25회(공식). 앱은 종목당 하루 1번.
- 오류는 HTTP 200에 JSON `Note`/`Information`/`Error Message` 키로 온다 → `AlphaVantageParser.errorMessage`.

| 용도 | 요청 | 응답 형식 | 쓰는 필드 | 확인 |
|---|---|---|---|---|
| 과거 실적일 | `function=EARNINGS&symbol=` | JSON | `quarterlyEarnings[].reportedDate`, `reportTime`(있으면) | 날짜 공식, `reportTime` 미확인 |
| 예정 실적일 | `function=EARNINGS_CALENDAR&symbol=&horizon=12month` | CSV(첫 줄 헤더) | `reportDate`, 이름에 `time`이 들어간 열(있으면) | 날짜 공식, 시점 열 미확인 |

- 장 전/장 후 구분 필드 이름과 값: 미확인 → 키 발급 후 실측.

## 3. IBKR TWS API (`marketdata/ibkr`, 구현 전)

IB Gateway에 소켓으로 붙는다(기본 포트: Gateway 실계좌 4001). Read-Only API 체크. `TwsApi.jar`는 `libs/`.
아래는 구현에 필요한데 아직 확인하지 않은 항목이다. 조사 결과가 오면 채운다.

| 용도 | 쓸 API(예정) | 채울 내용 | 확인 |
|---|---|---|---|
| 접속 | `EClientSocket.eConnect`, `EReader` 스레드, `nextValidId` | 접속 순서, 포트, clientId | 미확인 |
| 시세 종류 | `reqMarketDataType` | 실시간/지연 값(1~4) | 미확인 |
| 현재가 | `reqMktData`(주식 `Contract`) | 받는 틱 타입 번호(last, open, close) | 미확인 |
| 만기·행사가 목록 | `reqSecDefOptParams` | 인자(conId 얻는 법), 응답 콜백 필드 | 미확인 |
| 체인 시세·IV·델타 | `reqMktData`(옵션 `Contract`) + `tickOptionComputation` | 콜백 시그니처, 모델 IV 틱 번호, 값 없음 표시 | 미확인 |
| 과거 IV·HV·일봉 | `reqHistoricalData` | `whatToShow`(`OPTION_IMPLIED_VOLATILITY`, `HISTORICAL_VOLATILITY`, `ADJUSTED_LAST`), 일봉 최대 `durationStr` | 미확인 |
| 한도·오류 | | 동시 시세 100줄, 과거 데이터 페이싱, 구독 없음 오류 코드(354, 10089, 10167 등) | 미확인 |
