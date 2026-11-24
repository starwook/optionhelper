# 외부 API 명세

코드가 부르거나 부를 외부 API의 요청·응답 사양. 소스 선택 근거(무료 여부, 비교)는 `docs/데이터소스-조사.md`.
새 API를 붙이거나 필드를 바꾸면 이 문서를 먼저 고친다. 비어 있는 항목은 추측하지 말고 조사해서 채운다.

확인 수준
- **공식**: 공식 문서에서 확인 (근거 링크를 항목 옆에 둔다)
- **실측**: 실제 응답으로 확인 (공개 `demo` 키 등)
- **레거시**: IBKR 구 문서(`interactivebrokers.github.io/tws-api`, 현재 deprecated 표시)에서만 확인
- **미확인**: 근거 없음. 구현 시 실측으로 채운다

조사일: 2026-11-24

---

## 1. Alpaca Market Data (`marketdata/alpaca`)

### 공통

| 항목 | 내용 | 확인 |
|---|---|---|
| 기본 URL | `https://data.alpaca.markets` | 공식 [1] |
| 인증 | 헤더 `APCA-API-KEY-ID`, `APCA-API-SECRET-KEY` | 공식 [1] |
| 한도 | Basic(무료) 과거 데이터 API 분당 200회, Algo Trader Plus(월 $99) 분당 10,000회 | 공식 [4] |
| 한도 헤더 | `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`(UNIX 초) | 공식 [1] |
| 최근 15분 제한 | Basic은 최근 15분 SIP 데이터 조회 불가. 과거 조회는 `end`가 15분 이상 지난 시각이면 SIP도 무료 | 공식 [5] |
| 주식 과거 데이터 시작 | 2016년부터 (Basic·유료 동일). 무료 키 실측: `feed=sip` 첫 일봉 2016-01-04, `feed=iex` 첫 일봉 2020-07-27 | 공식 [4], 실측 |
| 옵션 과거 데이터 시작 | 2024년 2월부터 | 공식 [6] |
| 오류 HTTP 코드 | 400 파라미터 오류, 401 인증 헤더 없음/틀림, 403 구독 범위 밖, 429 한도 초과, 500 서버 오류 | 공식 [1][2][3] |
| 오류 본문 | 레퍼런스에 스키마 없음. FAQ 예시: `{"code":42210000,"message":"subscription does not permit querying recent SIP data"}` (HTTP 코드는 안 나와 있음) | 공식 [5] |

#### indicative vs opra (옵션 피드)

| 피드 | 내용 | 플랜 | 확인 |
|---|---|---|---|
| `indicative` | OPRA를 가공한 참고용 호가(실제 OPRA 호가 아님). 체결은 15분 지연 | Basic(무료) | 공식 [4][6] |
| `opra` | OPRA 통합 BBO 원본 | Algo Trader Plus(유료) + OPRA 약정 | 공식 [4][6] |

- 기본값: 스키마는 `opra`라고 적혀 있고, 설명에는 "구독 없으면 `indicative`"라고 적혀 있어 문서끼리 다르다. 코드는 항상 `feed`를 명시하므로 영향 없음. [2]

### 1-1. 주식 스냅샷 `GET /v2/stocks/{symbol}/snapshot` — 공식 [1]

파라미터

| 이름 | 값 | 기본값 |
|---|---|---|
| `symbol` (path) | 종목 | 필수 |
| `feed` | `sip`, `iex`, `delayed_sip`, `boats`, `overnight`, `otc` | 무제한 구독이면 `sip`, 아니면 `iex` |
| `currency` | ISO 4217 | `USD` |

페이지네이션 없음.

응답 (최상위: `symbol`, `currency`, `latestTrade`, `latestQuote`, `minuteBar`, `dailyBar`, `prevDailyBar`)

| 객체 | 필드 |
|---|---|
| `latestTrade` | `t` 시각(RFC-3339, 나노초), `p` 가격, `s` 수량, `x` 거래소, `i` 체결 ID, `c` 조건 플래그[], `z` 테이프, `u`(선택: canceled/incorrect/corrected) |
| `latestQuote` | `t`, `bp`/`bs`/`bx` 매수 호가·수량·거래소, `ap`/`as`/`ax` 매도 호가·수량·거래소(`ap`=0이면 매도 호가 없음), `c`, `z` |
| `minuteBar`, `dailyBar`, `prevDailyBar` | `t`, `o`, `h`, `l`, `c`, `v` 거래량, `n` 체결 건수, `vw` VWAP |

**코드가 쓰는 것**: `feed=iex`(설정값), `latestTrade.p`, `latestTrade.t`, `prevDailyBar.c`.
- 시가(`dailyBar.o`)는 2026-11-24부터 쓰지 않는다(화면은 증권사 앱처럼 전일 종가 기준만). 참고: 개장 전엔 `dailyBar`가 직전 거래일 봉이라 시가로 쓰면 어제 시가가 된다(2026-11-24 화면 실측: 프리장 157.07이 "시가 대비 −5.96%"로 나옴, 어제 시가 ≈167.03).
- 전일 종가: 대시보드는 `prevDailyBar.c` 대신 SIP 일봉(`/v2/stocks/bars`, `end`=어제)의 오늘 전 마지막 종가를 우선 쓴다(개장 전 `prevDailyBar`가 하루 밀릴 수 있어서). 일봉이 없을 때만 `prevDailyBar.c`.

**실측** (2026-11-24, 무료 키): `feed=iex` → HTTP 200, `latestTrade.p`/`t`, `dailyBar.o`, `prevDailyBar.c` 모두 옴.

### 1-2. 옵션 체인 스냅샷 `GET /v1beta1/options/snapshots/{underlying_symbol}` — 공식 [2]

파라미터

| 이름 | 값 | 기본값 |
|---|---|---|
| `underlying_symbol` (path) | 기초자산 | 필수 |
| `feed` | `opra`, `indicative` | 위 "기본값" 참고 |
| `limit` | 1~1000. **응답 전체 데이터 수 기준**(심볼당 아님) | 100 |
| `page_token` | 이전 응답의 `next_page_token` | |
| `type` | `call`, `put` | |
| `strike_price_gte`, `strike_price_lte` | 숫자 | |
| `expiration_date` | `YYYY-MM-DD`(정확히 일치) | |
| `expiration_date_gte`, `expiration_date_lte` | `YYYY-MM-DD` | |
| `root_symbol` | 예: `AAPL1` | |
| `updated_since` | RFC-3339 또는 `YYYY-MM-DD` | |

응답

| 필드 | 내용 |
|---|---|
| `snapshots` | OCC 심볼(예 `AAPL240426C00162500`)을 키로 하는 객체. 값에 필수 필드 없음 |
| `snapshots.*.latestQuote` | `t`, `bp`, `bs`, `bx`, `ap`, `as`, `ax`, `c` |
| `snapshots.*.latestTrade` | `t`, `p`, `s`, `x`, `c` |
| `snapshots.*.greeks` | `delta`, `gamma`, `theta`, `vega`, `rho` (블랙-숄즈) |
| `snapshots.*.impliedVolatility` | 숫자 (블랙-숄즈). 연 환산 기준은 문서에 없음. 화면 4장 비교로는 달력 365일 기준과 맞음(2026-11-24, 추정). 프리장엔 주가 시점이 옵션 호가와 어긋나 크게 튈 수 있음 |
| `snapshots.*.dailyBar`, `minuteBar`, `prevDailyBar` | `t`, `o`, `h`, `l`, `c`, `v`, `n`, `vw` |
| `next_page_token` | 문자열 또는 `null`. `page_token`으로 넘기면 다음 페이지 |

`greeks`·`impliedVolatility`가 빠지는 조건 (공식 FAQ [5]):
- 최신 호가의 매수·매도가가 0이 아니어야 함
- 기초자산의 최신 SIP 체결이 있어야 함
- 만기가 오늘 이후여야 함 → **0DTE는 그릭 없음**
- IV 반복 계산(최대 100회)이 수렴해야 함 → 깊은 OTM은 빠질 수 있음

**코드가 쓰는 것**: `feed`(설정, 기본 `indicative`), `limit=1000`, `page_token`, `expiration_date` 또는 `expiration_date_gte/lte`, `strike_price_gte/lte`. 응답 `snapshots{심볼: latestQuote.bp, latestQuote.ap, impliedVolatility, greeks.delta}`, `next_page_token`. 심볼은 `OccSymbol.parse`.

**실측** (2026-11-24, 무료 키): `feed=indicative` → HTTP 200. 각 항목에 `latestQuote{ap, as, ax, bp, bs, bx, c, t}`, `latestTrade`, `dailyBar`, `impliedVolatility`, `greeks`가 옴. 12/24 만기 175~185 행사가는 `impliedVolatility`(약 0.57~0.60)와 `greeks.delta`가 채워져 옴. `limit=5`면 `next_page_token`이 옴(페이지네이션 동작 확인).

### 1-3. 주식 봉 `GET /v2/stocks/bars` — 공식 [3]

파라미터

| 이름 | 값 | 기본값 |
|---|---|---|
| `symbols` | 쉼표 구분, 필수 | |
| `timeframe` | `[1-59]Min`/`T`, `[1-23]Hour`/`H`, `1Day`/`1D`, `1Week`/`1W`, `[1,2,3,4,6,12]Month`/`M` | 필수 |
| `start` | RFC-3339 또는 `YYYY-MM-DD` | 오늘 0시(실시간 권한 없으면 최소 15분 전) |
| `end` | RFC-3339 또는 `YYYY-MM-DD`, 포함 | 지금(실시간 권한 없으면 15분 전) |
| `limit` | 1~10000. **응답 전체 기준** | 1000 |
| `adjustment` | `raw`, `split`, `dividend`, `spin-off`, `all`. 쉼표로 여러 개. 같은 키 반복은 400 | `raw` |
| `feed` | `sip`, `iex`, `boats`, `otc` | `sip` |
| `sort` | `asc`, `desc` | `asc` |
| `asof` | `YYYY-MM-DD` (심볼 변경 매핑 기준일, `-`면 매핑 안 함) | 오늘 |
| `currency` | | `USD` |
| `page_token` | | |

응답

| 필드 | 내용 |
|---|---|
| `bars` | 심볼을 키로 하는 객체, 값은 봉 배열 `{t, o, h, l, c, v, n, vw}` |
| `next_page_token` | 문자열 또는 `null` |
| `currency` | |

- 정렬은 심볼 우선, 그다음 시각. 한 페이지에 한 심볼만 올 수 있으니 `next_page_token`이 `null`이 될 때까지 계속 받는다. [3]
- 일봉 `t`: FAQ에 "일봉은 뉴욕 날짜 기준으로 자른다"고 되어 있고 [5], 예시 값이 `2022-08-16T04:00:00Z`(뉴욕 자정, EDT)다. UTC 오프셋 규칙은 명시돼 있지 않음 → 겨울(EST)엔 `05:00:00Z`로 추정, **미확인**.

**코드가 쓰는 것**: `symbols`, `timeframe=1Day`, `start`, `end`(어제), `limit=10000`, `adjustment=all`, `feed=sip`(실패 시 `iex`), `page_token`. 응답 `bars{심볼: [t, o, h, l, c]}`, `next_page_token`.

**상단 주가 차트(5분봉, 2026-11-25)**: `symbols`, `timeframe=5Min`, `start`=그날 09:30 ET(RFC-3339 UTC), `end`=min(그날 장 마감, 지금), `limit=10000`, `feed`=설정 주식 피드(무료 `iex`. SIP는 최근 15분 조회 불가라 안 씀). 응답 `bars{심볼: [t, c]}`. 하루 78개라 페이지 넘김 없음. `t`가 봉 시작 시각인지 끝 시각인지는 문서에 없음 → 시작으로 가정, **미확인**.
- IV 역산 백필(1-4)은 옵션 행사가와 맞추려고 같은 엔드포인트를 `adjustment=raw`로 한 번 더 부른다(분할 조정된 종가는 과거 행사가와 안 맞음).

**실측 정정** (2026-11-24): 겨울 일봉 `t`는 `05:00:00Z`로 옴(예: `2026-11-23T05:00:00Z`). 위 "겨울엔 05:00Z로 추정, 미확인"이 맞았다.

### 1-4. 옵션 봉 `GET /v1beta1/options/bars` — 공식 [6], 실측

파라미터는 주식 봉(1-3)과 같은 모양: `symbols`(OCC 심볼 쉼표 구분), `timeframe`, `start`, `end`, `limit`, `page_token`, `sort`. `feed`·`adjustment` 없음.

응답: `bars{심볼: [{t, o, h, l, c, v, n, vw}]}`, `next_page_token`.

**실측** (2026-11-24, 무료 키)
- **만기된 계약도 응답한다**: `NVDA251219C00180000`, 2025-11-17~21 → 일봉 5개(`t`=`2025-11-17T05:00:00Z` 등), `next_page_token: null`.
- 상장되지 않은 심볼을 섞어도 HTTP 200, 그 심볼만 응답에서 빠진다(`NVDA261218C00999000`을 섞어 확인). 코드는 응답에 있는 심볼만 쓴다.
- 역산 검산: 2026-11-23 NVDA 종가 165.05, 12/18 165 콜 11.5·풋 11.1 → IV 약 64%(같은 시기 체인 스냅샷 ATM IV 약 67%와 비슷).
- 한 번에 넣을 수 있는 `symbols` 최대 개수: **미확인** → 50개씩 나눠 보낸다.
- 거래가 없는 날은 봉이 없다(일봉은 체결 기준). `c`는 그날 마지막 체결가라 기초자산 종가(16:00)와 시각이 어긋날 수 있다.

**코드가 쓰는 것**: `symbols`(50개씩), `timeframe=1Day`, `start`, `end`, `limit=10000`, `page_token`. 응답 `bars{심볼: [t, c]}`, `next_page_token`. 심볼은 `OccSymbol.parse`.

**실측** (2026-11-24, 무료 키, `adjustment=all`): `feed=sip`도 HTTP 200(`end`가 어제라 15분 제한에 안 걸림), 첫 봉 2016-01-04. `feed=iex`는 첫 봉 2020-07-27. 코드는 `sip`을 먼저 부르므로 실제로는 2016년부터 받는다.

근거
- [1] https://docs.alpaca.markets/reference/stocksnapshotsingle
- [2] https://docs.alpaca.markets/reference/optionchain
- [3] https://docs.alpaca.markets/reference/stockbars
- [4] https://docs.alpaca.markets/docs/about-market-data-api
- [5] https://docs.alpaca.markets/docs/market-data-faq
- [6] https://docs.alpaca.markets/docs/historical-option-data

---

## 2. Alpha Vantage (`earnings/AlphaVantage*`)

### 공통

| 항목 | 내용 | 확인 |
|---|---|---|
| 기본 URL | `https://www.alphavantage.co/query`, 파라미터 `function`, `apikey` | 공식 [7] |
| 한도 | 무료 하루 25회. 분당 한도는 무료 플랜에 명시 없음. 유료는 분당 75~1200회, 일 한도 없음 | 공식 [8][9] |
| 오류 형식 | HTTP 200 + JSON 메시지. 실측된 키는 `Information`(예: demo 키 초당 2회 초과 시 `{"Information": "Please query the demo URLs at no more than 2 requests per second."}`) | 실측 |
| 그 밖의 오류 키 | `Note`, `Error Message`는 이번 조사에서 문서·응답 모두 확인 못 함. 키 오류·일 한도 초과 응답 형식도 미확인 | 미확인 |
| CSV 엔드포인트 오류 | `EARNINGS_CALENDAR`를 `EARNINGS` 직후(1초 안) 부르면 HTTP 200에 헤더 다음 줄로 메시지가 글자마다 쉼표로 쪼개진 깨진 CSV(`I,n,f,o,r,m,a...`)가 왔다. 1초 이상 띄우면 정상. 예정 실적이 없으면 헤더만 온다(NVDA, 2026-11-24) | 실측 |

앱은 종목당 하루 1번(`EARNINGS` + `EARNINGS_CALENDAR` = 2회) 부른다.

### 2-1. 과거 실적 `function=EARNINGS` (JSON)

파라미터: `symbol`(필수), `apikey`. 다른 파라미터 없음. — 공식 [7]

응답 (2026-11-24 `symbol=IBM&apikey=demo` 실측)

| 위치 | 키 |
|---|---|
| 최상위 | `symbol`, `annualEarnings`, `quarterlyEarnings` |
| `annualEarnings[]` | `fiscalDateEnding`, `reportedEPS` |
| `quarterlyEarnings[]` | `fiscalDateEnding`, `reportedDate`, `reportedEPS`, `estimatedEPS`, `surprise`, `surprisePercentage`, `reportTime` |

- 모든 값은 **문자열**(숫자도 `"2.95"`처럼 따옴표).
- `reportTime` 값: `pre-market`, `post-market` (IBM 123개 분기 전체에서 이 두 값만 나옴). 다른 종목에서 빈 값이 나올 수 있는지는 미확인.
- 예: `{"fiscalDateEnding":"2026-09-30","reportedDate":"2026-10-21","reportedEPS":"2.95","estimatedEPS":"2.88","surprise":"0.07","surprisePercentage":"2.4306","reportTime":"post-market"}`

**코드가 쓰는 것**: `quarterlyEarnings[].reportedDate`, `reportTime`.

### 2-2. 예정 실적 `function=EARNINGS_CALENDAR` (CSV)

파라미터 — 공식 [7] (문서 해당 절은 요약본으로만 읽음)

| 이름 | 값 | 기본값 |
|---|---|---|
| `symbol` | 선택. 없으면 전체 종목 | |
| `horizon` | `3month`, `6month`, `12month` | `3month` |

응답 (실측): `Content-Type: application/x-download`, CSV, 첫 줄 헤더.

```
symbol,name,reportDate,fiscalDateEnding,estimate,currency,timeOfTheDay
A,AGILENT TECHNOLOGIES INCORPORATED,2026-11-23,2026-10-31,1.72,USD,post-market
ADXN,ADDEX THERAPEUTICS LIMITED,2026-11-23,2026-09-30,,USD,pre-market
```

- 장 전/장 후 열: **`timeOfTheDay`**, 값 `pre-market`, `post-market`, 빈 값(많음). (실측)
- 빈 숫자는 `,,`로 온다.
- 예정 실적이 없으면 헤더 한 줄만 온다(`symbol=IBM&horizon=12month` 실측 시 72바이트, 행 없음).
- `name`에 쉼표가 있는 회사명이 따옴표로 감싸져 오는지: 미확인.

**코드가 쓰는 것**: `reportDate`, 이름에 `time`이 들어간 열(= `timeOfTheDay`).

근거
- [7] https://www.alphavantage.co/documentation/ (Earnings History, Earnings Calendar 절)
- [8] https://www.alphavantage.co/support/
- [9] https://www.alphavantage.co/premium/
- 실측: `https://www.alphavantage.co/query?function=EARNINGS&symbol=IBM&apikey=demo`, `...function=EARNINGS_CALENDAR&horizon=3month&apikey=demo`, `...function=EARNINGS_CALENDAR&symbol=IBM&horizon=12month&apikey=demo`

---

## 3. IBKR TWS API, Java (`marketdata/ibkr`, 구현 전)

IB Gateway(또는 TWS)에 TCP 소켓으로 붙는다. Gateway와 TWS는 API 입장에서 같다. [10]
공식 문서는 IBKR Campus(`https://www.interactivebrokers.com/docs/tws-api/doc/...`)다. 구 문서(`interactivebrokers.github.io/tws-api`)는 deprecated 표시가 있어 **레거시**로 따로 표시한다.

### 3-1. 라이브러리

| 항목 | 내용 | 확인 |
|---|---|---|
| 받는 곳 | https://download2.interactivebrokers.com/installers/tws-api/index.html (Mac/Unix zip `twsapi_macunix.<major>.<minor>.zip`) | 공식 [11] |
| 버전 (2026-11-24) | Stable 1050.02 (2026-09-09), Latest 1052.01 (2026-10-30) | 공식 [11] |
| 설치 | `unzip ... -d $HOME/` → `~/IBJts/` 아래 `samples`, `source`, `API_VersionNum.txt` | 공식 [12] |
| 배포 경로 | IBKR는 github.io의 MSI/ZIP만 공식. pip·NuGet 등 다른 저장소는 IBKR와 무관(Maven Central도 공식 아님) | 공식 [13] |
| Java 소스 위치 | `~/IBJts/source/JavaClient` (Python이 `~/IBJts/source/pythonclient`인 것과 같은 구조) | 미확인 → 압축 풀어 확인 |
| `TwsApi.jar` 유무·경로 | | 미확인 → 압축 풀어 확인. 없으면 `JavaClient` 소스를 빌드 |
| 패키지명 | `com.ib.client` | 미확인 → jar로 확인 |
| 버전 맞추기 | TWS/Gateway 버전과 API 버전을 맞추라고 권고 | 공식 [13] |

### 3-2. 접속

| 항목 | 내용 | 확인 |
|---|---|---|
| Gateway/TWS 설정 | API → Settings에서 "Enable ActiveX and Socket Clients" 켜기, "Socket Port" 확인. 문서는 "Read-Only API" 끄기를 권하지만 이 앱은 주문을 안 하므로 **켠 채로 둔다** | 공식 [14] |
| 포트 | TWS 실계좌 7496, 모의 7497 (레거시 [L1]). IB Gateway 4001/4002는 공식 문서에서 못 찾음 → Gateway 설정 화면의 "Socket Port"로 확인 | TWS 레거시, Gateway 미확인 |
| 객체 생성 (Java) | `EReaderSignal signal = new EJavaSignal(); EClientSocket client = new EClientSocket(wrapper, signal);` C#/C++ 예시는 `new EClientSocket(this, signal)`. Java 클래스명 `EJavaSignal`은 레거시 샘플 기준 | 공식 [15](C#·C++), Java는 미확인 → jar로 확인 |
| 접속 | `client.eConnect(host, port, clientId)` (예: `"127.0.0.1"`, 포트, `0`) | 공식 [16] |
| EReader | 접속 **후에** 만든다(핸드셰이크로 버전을 정한 뒤여야 함). | 공식 [16] |
| 읽기 스레드 (Java 공식 예시) | `final EReader reader = new EReader(client, signal); reader.start(); new Thread(() -> { while (client.isConnected()) { signal.waitForSignal(); try { reader.processMsgs(); } catch (Exception e) { ... } } }).start();` | 공식 [17] |
| 준비 완료 신호 | `EWrapper.nextValidId(int orderId)`가 오면 요청을 보내도 된다(그 전 요청은 버려질 수 있음). `connectAck()`는 소켓 연결 직후 호출 | 공식 [16] |
| clientId | 같은 TWS/Gateway에 최대 32개 클라이언트. 이미 쓰는 clientId면 오류 326 | 공식 [10][18] |
| 소켓 열기 실패 | 오류 502 (API 꺼짐/포트 다름/방화벽). TWS가 아니라 OS가 낸 오류라 TWS 로그에 안 남음 | 공식 [16][18] |
| 값 없음(Unset) | 값이 없으면 그 타입의 최댓값. `double`은 `Double.MAX_VALUE`(1.7976931348623157E308) | 공식 [19] |

### 3-3. 시세 종류 `reqMarketDataType(int)` — 공식 [20]

| 값 | 이름 | 내용 |
|---|---|---|
| 1 | Live | 실시간. 시세 구독 필요 |
| 2 | Frozen | 장 마감 시 마지막 값. 장이 닫혔는데 1로 요청하면 bid/ask가 `-1`로 오는 경우가 많다 |
| 3 | Delayed | 무료 15~20분 지연. 지연 틱 번호(66~76)로 온다 |
| 4 | Delayed Frozen | 구독 없는 사용자의 지연 frozen. 장중엔 전날 값 |

- `reqMktData` 전에 부른다. 실제로 오는 종류는 `EWrapper.marketDataType(int reqId, int marketDataType)`로 알려 준다(안 불러도 온다).
- 구독이 있으면 요청 종류와 관계없이 가장 좋은 데이터가 온다.

### 3-4. 주식 현재가 `reqMktData` — 공식 [21]

- 요청: `reqMktData(int reqId, Contract contract, String genericTickList, boolean snapshot, boolean regulatorySnapshot, List<TagValue> mktDataOptions)` — Java 예시 `client.reqMktData(reqId, contract, "", false, false, null);`
- 콜백 (Java, 공식 예시):
  - `tickPrice(int tickerId, int field, double price, TickAttrib attribs)`
  - `tickSize(int tickerId, int field, Decimal size)` — 수량은 `Decimal` 타입
  - `tickGeneric(int tickerId, int field, double value)`, `tickString(int tickerId, int field, String value)` (레거시 [L2])

| 틱 | 번호 | 지연 번호 | 콜백 |
|---|---|---|---|
| Bid | 1 | 66 | tickPrice |
| Ask | 2 | 67 | tickPrice |
| Last | 4 | 68 | tickPrice |
| High | 6 | 72 | tickPrice |
| Low | 7 | 73 | tickPrice |
| Close(전일 종가) | 9 | 75 | tickPrice |
| Open | 14 | 76 | tickPrice |
| 옵션 IV(기초자산 기준 30일) | 24 | | tickGeneric, generic tick `106` 필요 |
| 옵션 HV | 23 | | tickGeneric, generic tick `104` 필요 |
| 콜/풋 미결제약정 | 27 / 28 | | tickSize, generic tick `101` |
| 콜/풋 거래량 | 29 / 30 | | tickSize, generic tick `100` |

### 3-5. 만기·행사가 목록 `reqSecDefOptParams` — 공식 [22]

1. 기초자산 conId 얻기: `reqContractDetails(int reqId, Contract contract)`(주식: symbol, secType `STK`, exchange `SMART`, currency `USD`) → `contractDetails(int reqId, ContractDetails contractDetails)` → `contractDetailsEnd(int reqId)`. [23]
   - conId 읽는 법: `contractDetails.contract().conid()` — 미확인 → jar로 확인. (Contract 객체에 `ConId: integer` 필드는 공식 [24])
2. 요청: `reqSecDefOptParams(int reqId, String underlyingSymbol, String futFopExchange, String underlyingSecType, int underlyingConId)` — 예: `client.reqSecDefOptParams(0, "IBM", "", "STK", 8314);` `futFopExchange`는 `""`이면 전체 거래소.
3. 콜백 (Java 공식 예시): `securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes)` — 거래소마다 한 번씩 여러 번 온다.
4. 끝: `securityDefinitionOptionalParameterEnd(int reqId)` — Java 이름 미확인 → jar로 확인.

- 만기와 행사가는 각각 집합이라, 조합 중 실제로 없는 계약이 있을 수 있다. [22]
- 만기 문자열 형식: 예시상 `YYYYMMDD`(레거시 계약 예시 `"20170120"`) — 미확인 → 실측.
- 전체 체인을 `reqContractDetails`로 받는 건 느리게 제한(throttle)되므로 권장하지 않는다. [22]

### 3-6. 옵션 시세·IV·그릭 `reqMktData`(옵션) + `tickOptionComputation` — 공식 [25]

옵션 Contract: `symbol`, `secType="OPT"`, `lastTradeDateOrContractMonth`(만기), `strike`, `right`(`"C"`/`"P"`), `multiplier="100"`, `exchange="SMART"`, `currency="USD"`. 파생상품 계약은 가능한 한 모든 필드를 채워야 한다(tradingClass 포함 가능). [23][24], 예시값은 레거시 [L3]

- 그릭은 옵션 `reqMktData`에서 자동으로 요청된다. **옵션과 기초자산 둘 다 시세 구독이 있어야** 그릭이 온다. 기초자산 구독이 없으면 "not subscribed" 오류가 오는데 그릭이 필요 없으면 무시해도 된다.
- 콜백 (Java 공식 예시):

```java
public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol, double delta,
        double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice)
```

| field | 뜻 | 지연 번호 |
|---|---|---|
| 10 | Bid 기준 계산 | 80 |
| 11 | Ask 기준 계산 | 81 |
| 12 | Last 기준 계산 | 82 |
| 13 | 모델 계산(모델 IV) | 83 |

- `tickAttrib`: 0 = return based, 1 = price based.
- 값 없음: 공식 문서에 이 콜백 전용 설명은 없고, 공통 규칙상 `Double.MAX_VALUE`(Unset) [19]. 공식 예시도 `floatMaxString(...)`으로 출력한다. `-1`/`-2` 같은 별도 값: 미확인 → 실측.
- 직접 계산: `calculateImpliedVolatility(reqId, contract, optionPrice, underPrice, null)`, `calculateOptionPrice(reqId, contract, volatility, underPrice, null)` → 결과는 `tickOptionComputation`.

### 3-7. 과거 IV·HV·일봉 `reqHistoricalData` — 공식 [26][27]

요청: `reqHistoricalData(int reqId, Contract contract, String endDateTime, String durationStr, String barSizeSetting, String whatToShow, int useRTH, int formatDate, boolean keepUpToDate, List<TagValue> chartOptions)`

| 인자 | 내용 |
|---|---|
| `endDateTime` | `"YYYYMMDD HH:mm:ss TMZ"`, 빈 문자열이면 현재 |
| `durationStr` | `"<숫자> <단위>"`, 단위 `S` `D` `W` `M` `Y` (예: `"1 Y"`) |
| `barSizeSetting` | `1 secs`…`30 secs`, `1 min`…`30 mins`, `1 hour`…`8 hours`, `1 day`, `1 week`, `1 month` |
| `useRTH` | 1이면 정규장 데이터만 |
| `formatDate` | 일봉은 `yyyyMMdd` 형식만 |
| `keepUpToDate` | true면 `endDateTime`을 비워야 함. TRADES/MIDPOINT/BID/ASK만 |

- 콜백: `historicalData(int reqId, Bar bar)`(bar: time, open, high, low, close, volume, count, wap — 필드명은 레거시 [L4]), `historicalDataEnd(int reqId, String start, String end)`.
- 일봉 최대 기간: 표에 `365 D` / `52 W` / `12 M` / `68 Y`. 즉 여러 해는 `Y` 단위로 한 번에 요청 가능.

| whatToShow | 지원 상품 | 봉 값 |
|---|---|---|
| `OPTION_IMPLIED_VOLATILITY` | ETF, 지수, **주식** (옵션 계약 아님) | 시가·고가·저가·종가 = 그 기간의 IV |
| `HISTORICAL_VOLATILITY` | ETF, 지수, 주식 | 같은 방식의 HV |
| `ADJUSTED_LAST` | ETF, 옵션, 주식 | 분할·배당 조정 체결가, 거래량 있음 |
| `TRADES` | 주식·옵션 등 | 분할만 조정(배당 미조정) |

- `ADJUSTED_LAST`에 `endDateTime`을 비워야 한다는 제약: 공식 문서에 없음, 미확인.
- 쓸 수 없는 과거 데이터 [27]: **만기된 옵션**, 옵션의 EOD 데이터, 6개월보다 오래된 30초 이하 봉, 거래소를 옮긴 종목의 이전 기간 등.

페이싱 한도 [27] (공식 문서 절 제목은 "30초 이하 봉"):
- 같은 요청을 15초 안에 다시 보내기
- 같은 계약·거래소·틱 타입으로 2초 안에 6번 이상
- 10분 동안 60번 초과
- `BID_ASK`는 2번으로 센다
- 일봉에도 적용되는지는 문서가 명시하지 않음 → 일봉도 이 한도 안에서 보내는 걸로 가정하고 구현.

### 3-8. 한도

| 항목 | 내용 | 확인 |
|---|---|---|
| 동시 시세 줄 | 기본 100줄(TWS 화면, 호가창 depth, 실시간 봉과 공유). 수수료·Quote Booster로 늘어남. 넘으면 오류 101 | 공식 [18][28] |
| 요청 속도 | 초당 (시세 줄 수 ÷ 2)건 → 기본 초당 50건. "Reject messages above maximum allowed message rate" 설정이 켜져 있으면 넘을 때 오류 100, 3번 넘으면 세션 종료. 꺼져 있으면 TWS가 알아서 늦춘다 | 공식 [28] |
| 동시 과거 데이터 요청 | 50개 | 레거시 [L5] |
| 시세 구독 | 유료 구독이 있어야 API로 실시간 시세를 받는다(지연 데이터 제외) | 공식 [27] |

### 3-9. 오류 — 공식 [18][29]

콜백 (Java 공식 예시, API 10.x):

```java
public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson)
```

- `id`는 그 오류를 낸 요청 ID(주문이면 orderId). `errorTime`은 유닉스 시각. 레거시 문서엔 `errorTime` 없는 4인자 버전이 있으니 받은 jar의 `EWrapper`를 기준으로 한다. 알림(2104 등)도 이 콜백으로 온다.

| 코드 | 뜻 |
|---|---|
| 100 | 초당 메시지 한도 초과 |
| 101 | 동시 시세 줄 한도 초과 |
| 102 | 이미 쓰는 ticker ID |
| 162 | 과거 데이터 서비스 오류(페이싱 위반 포함) |
| 165 | 과거 데이터 조회 메시지(예: 데이터 없음) |
| 166 | 만기된 계약의 과거 데이터 없음 |
| 200 | 계약을 못 찾음 / 계약이 모호함 |
| 300 | 없는 ticker ID 취소 |
| 321 | 요청 검증 중 서버 오류 |
| 322 | 요청 처리 중 서버 오류 |
| 326 | clientId 중복 |
| 354 | 시세 구독 없음 |
| 420 | 잘못된 실시간 요청(페이싱) |
| 502 | TWS/Gateway에 접속 못 함 |
| 504 | 접속 안 된 상태에서 요청 |
| 1100 | IB 서버와 연결 끊김 |
| 1101 | 재연결됨, 시세 구독 사라짐 → 다시 요청 |
| 1102 | 재연결됨, 시세 구독 유지 |
| 2104 / 2106 / 2158 | 시세 / 과거 데이터 / 계약 정의 서버 연결 OK (알림, 오류 아님) |
| 10089 | 구독한 시세가 API 사용을 지원하지 않음 |
| 10090 | 요청한 시세 일부 구독 없음(예: 옵션 구독은 있는데 기초자산 구독이 없어 그릭 계산 불가) |
| 10091 | 요청한 시세 일부가 API용 추가 구독 필요 |
| 10186 | 시세 구독 없음 + 지연 시세도 꺼져 있음 |
| 10197 | 경쟁 세션(모의·실계좌 동시 로그인) 중이라 시세 없음 |
| 10167, 10168 | 공식 오류 코드 표에 없음. 미확인 |

### 3-10. Read-Only API

- 기본으로 켜져 있다. 켜져 있으면 주문 정보가 API에 안 보인다(레거시 [L1]). 이 앱은 주문을 하지 않으므로 켠 채로 쓴다.
- 이 상태에서 주문을 보내면 나오는 오류 코드: 미확인 (공식 표에 "Read-Only"로 표시된 코드는 10005 "Read-only models." 하나뿐이고 주문 거부 코드인지는 불명).
- 시세·과거 데이터·계약 조회가 Read-Only에서 막히는지: 공식 문서에 언급 없음. 막힌다는 기록도 없음 → 실측.

근거 (공식, IBKR Campus. `https://ibkrcampus.com/...`에서 아래로 리다이렉트됨)
- [10] https://www.interactivebrokers.com/docs/tws-api/doc/download-tws , https://www.interactivebrokers.com/docs/tws-api/doc/connectivity/connectivity-behavior
- [11] https://download2.interactivebrokers.com/installers/tws-api/index.html
- [12] https://www.interactivebrokers.com/docs/tws-api/doc/install-the-tws-api-on-mac-os-linux
- [13] https://www.interactivebrokers.com/docs/tws-api/doc/download-the-tws-api
- [14] https://www.interactivebrokers.com/docs/tws-api/doc/api-settings
- [15] https://www.interactivebrokers.com/docs/tws-api/doc/architecture
- [16] https://www.interactivebrokers.com/docs/tws-api/doc/connectivity/establishing-an-api-connection
- [17] https://www.interactivebrokers.com/docs/tws-api/doc/connectivity/e-reader-thread
- [18] https://www.interactivebrokers.com/docs/tws-api/doc/error/codes
- [19] https://www.interactivebrokers.com/docs/tws-api/doc/unset-values
- [20] https://www.interactivebrokers.com/docs/tws-api/doc/md/delayed-data
- [21] https://www.interactivebrokers.com/docs/tws-api/doc/md/streaming-data
- [22] https://www.interactivebrokers.com/docs/tws-api/doc/contracts/option-chains
- [23] https://www.interactivebrokers.com/docs/tws-api/doc/contracts/details
- [24] https://www.interactivebrokers.com/docs/tws-api/doc/contracts/object
- [25] https://www.interactivebrokers.com/docs/tws-api/doc/md/option-greeks
- [26] https://www.interactivebrokers.com/docs/tws-api/doc/md/historical-bars
- [27] https://www.interactivebrokers.com/docs/tws-api/doc/md/market-data-limitations
- [28] https://www.interactivebrokers.com/docs/tws-api/doc/pacing-limitations
- [29] https://www.interactivebrokers.com/docs/tws-api/doc/error/messages

근거 (레거시, deprecated)
- [L1] https://interactivebrokers.github.io/tws-api/initial_setup.html
- [L2] https://interactivebrokers.github.io/tws-api/interfaceIBApi_1_1EWrapper.html
- [L3] https://interactivebrokers.github.io/tws-api/basic_contracts.html
- [L4] https://interactivebrokers.github.io/tws-api/historical_bars.html
- [L5] https://interactivebrokers.github.io/tws-api/historical_limitations.html

### 3-11. 구현 전에 실측으로 채울 것

`TwsApi.jar`를 받은 뒤 `javap`나 IDE로 `EWrapper`·`EClientSocket`·`ContractDetails`를 열면 대부분 바로 확인된다.
- Java 소스/jar 경로, 패키지명 `com.ib.client`
- `EJavaSignal` 클래스명, `contractDetails.contract().conid()`, `securityDefinitionOptionalParameterEnd` 이름
- IB Gateway 기본 포트(설정 화면)
- `reqSecDefOptParams` 만기 문자열 형식
- `tickOptionComputation`의 값 없음 표시
- Read-Only에서 조회 요청이 모두 되는지

---

## 4. 코드와 어긋나는 점 (고치지 않고 기록만, 해결한 것은 표시)

| # | 위치 | 내용 | 영향 |
|---|---|---|---|
| 1 | `AlphaVantageParser` 주석 | "공식 문서에 열·키 이름이 나와 있지 않아"라고 되어 있으나, 실측으로 `EARNINGS`의 `reportTime`(`pre-market`/`post-market`)과 `EARNINGS_CALENDAR`의 `timeOfTheDay`(같은 값 + 빈 값)가 확인됐다. 지금 로직(이름에 `time`이 들어간 열, `pre`/`post` 접두어)으로도 맞게 동작한다 | **해결**(2026-11-24): 주석 갱신 |
| 2 | `AlphaVantageParser.calendarCsv` | 줄을 `split(',')`로 자른다. `name` 열(회사명)에 쉼표가 들어가 따옴표로 감싸져 오면 `reportDate`(3번째 열) 위치가 밀린다. 따옴표 처리 여부는 미확인 | **해결**(2026-11-24): 따옴표 처리 추가 |
| 3 | `AlphaVantageEarningsSource.events` | `EARNINGS_CALENDAR`(CSV) 응답은 오류 검사를 안 한다. 한도 초과 등으로 JSON 메시지가 오면 헤더에 `reportDate`가 없어 조용히 빈 목록이 된다 | **해결**(2026-11-24): 읽을 수 없으면 예외(저장값 유지·경고 로그), 두 호출 사이 1.5초 대기 |
| 4 | `AlpacaParser.bars` | 일봉 `t`를 `OffsetDateTime.parse(...).toLocalDate()`로 UTC 날짜로 쓴다. 일봉 `t`가 뉴욕 자정(04:00Z/05:00Z)이면 UTC 날짜와 뉴욕 날짜가 같아 문제없다. 다만 오프셋 규칙은 문서에 명시돼 있지 않음 | 현재 영향 없음으로 판단, 실측 필요 |
| 5 | `AlphaVantageParser.errorMessage` | `Note`·`Error Message` 키는 이번 조사에서 확인 못 했고, 실측된 건 `Information`뿐이다. 세 키를 다 보는 지금 방식은 안전한 쪽 | 영향 없음 |
| 6 | 이전 문서 3장 | "Gateway 실계좌 4001"은 공식 문서에서 확인되지 않았다(위 3-2). 또 과거 IV(`OPTION_IMPLIED_VOLATILITY`)는 옵션 계약이 아니라 **주식 계약**으로 요청해야 한다(3-7) | IBKR 구현 시 주의 |
