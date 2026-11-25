// 맨 위 주가·봉 차트 부품. 대시보드(index.html)와 실시간 모의투자(paper.html)가 이 코드 하나로 같은 화면을 그린다.
//   const quote = QuoteChart.mount(document.getElementById('quote'));
//   quote.setSymbol('NVDA');   // 종목을 바꿀 때마다(차트·현재가 스트림을 새로 받는다)
//   quote.update(d);           // /api/dashboard 응답이 올 때마다(전일 종가·장 구분·예상 범위)
//   quote.redraw();            // 화면 폭이 바뀌었을 때
// 부품이 알아서 하는 일: 마크업, 봉 단위 버튼, 보조지표 체크박스, /api/stock-chart(1분마다), /api/quote-stream(SSE, 약 1초) 현재가, 범례·점선·안내문.
// 그림은 Lightweight Charts 5(TradingView, Apache-2.0. 라이선스상 차트 안 TradingView 로고는 그대로 둔다)가 그린다. 스타일은 app.css의 .quote-*.
// 보조지표 값은 서버가 계산해 /api/stock-chart의 indicators로 준다(봉과 같은 순서, 값이 없으면 null). 이동평균·볼린저는 가격 칸에 겹치고,
// 거래량·RSI·스토캐스틱·MACD는 가격 칸 아래에 칸(pane)을 하나씩 만든다. 고른 지표는 localStorage에 기억한다.
const QuoteChart = (() => {
  const CHART_POLL_MS = 60000; // 봉은 1분마다 다시 받고, 마지막 봉은 그사이 실시간 현재가로 이어 그린다
  const INTERVALS = [['MIN_1', '1분'], ['MIN_5', '5분'], ['MIN_15', '15분'], ['MIN_30', '30분'], ['MIN_60', '60분'], null, ['DAY', '일'], ['WEEK', '주'], ['MONTH', '월'], ['YEAR', '년']];
  const INTERVAL_LABEL = Object.fromEntries(INTERVALS.filter(Boolean));
  const INTERVAL_SEC = { MIN_1: 60, MIN_5: 300, MIN_15: 900, MIN_30: 1800, MIN_60: 3600 };
  const SESSION_LABEL = { PRE_MARKET: '프리마켓에서', AFTER_HOURS: '애프터마켓에서', CLOSED: '장 마감 · 선물' };
  // [키, 체크박스 이름, 칸을 따로 쓰는지]. 순서대로 아래 칸이 쌓인다
  // 이동평균 [기간, 선 색(CSS 변수)]. 체크박스 숫자·범례 이름·선이 같은 색을 쓴다
  const MA_LINES = [[5, '--text'], [20, '--event'], [60, '--focus'], [120, '--put']];
  const MA_LABEL = '이동평균 ' + MA_LINES.map(([n, color]) => `<span style="color:var(${color})">${n}</span>`).join('·');
  const INDICATORS = [['ma', MA_LABEL, false], ['bb', '볼린저밴드', false], ['vol', '거래량', true], ['rsi', 'RSI', true], ['stoch', '스토캐스틱', true], ['macd', 'MACD', true]];
  const IND_DEFAULT = ['ma', 'vol', 'rsi'];
  const IND_STORE = 'quoteIndicators';
  const PRICE_PANE_PX = 380, SUB_PANE_PX = 110; // 가격 칸 높이, 아래 칸 하나 높이

  const n2 = (v) => (v == null ? '–' : Number(v).toFixed(2));
  const signed = (v, d = 1) => (v == null ? '–' : (v >= 0 ? '+' : '') + (v * 100).toFixed(d) + '%');
  const tone = (v) => (v == null ? '' : v >= 0 ? 'up' : 'down');
  const md = (date) => date.slice(5).replace('-', '/');

  /** base 대비 now 등락(+$0.02 (0.06%)), 색 포함. base가 없으면 빈 문자열. */
  function move(now, base) {
    if (now == null || !base) return '';
    const ch = now - base;
    return `<span class="${tone(ch)}">${ch >= 0 ? '+' : '−'}$${n2(Math.abs(ch))} (${signed(ch / base, 2)})</span>`;
  }

  function mount(root) {
    root.innerHTML = `<div class="quote-head">
        <span class="quote-sym"></span><span class="quote-price">–</span><span class="quote-chg"></span>
        <span class="grow"></span>
        <div class="ranges">${INTERVALS.map((x) => (x ? `<button data-interval="${x[0]}" aria-pressed="${x[0] === 'MIN_5'}">${x[1]}</button>` : '<span class="sep"></span>')).join('')}</div>
      </div>
      <div class="quote-ind">${INDICATORS.map(([k, label]) => `<label><input type="checkbox" value="${k}"> ${label}</label>`).join('')}</div>
      <div class="quote-chart"><div class="quote-canvas"></div><div class="quote-legend"></div><div class="quote-pane-labels"></div><div class="empty chart-msg">차트 불러오는 중…</div></div>
      <p class="quote-note"></p>`;
    const q = (sel) => root.querySelector(sel);
    const el = { sym: q('.quote-sym'), price: q('.quote-price'), chg: q('.quote-chg'), ranges: q('.ranges'), ind: q('.quote-ind'), box: q('.quote-chart'), canvas: q('.quote-canvas'), legend: q('.quote-legend'), paneLabels: q('.quote-pane-labels'), msg: q('.chart-msg'), note: q('.quote-note') };
    const st = { symbol: null, interval: 'MIN_5', chart: null, live: null, d: null, stream: null };
    const sc = { chart: null, series: null, data: null, last: null, lines: {}, ind: [] }; // ind = [{ key, series, fill(c) }]
    let picked = new Set(JSON.parse(localStorage.getItem(IND_STORE) || 'null') ?? IND_DEFAULT);
    el.ind.querySelectorAll('input').forEach((x) => { x.checked = picked.has(x.value); });
    const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    const COLOR = { up: css('--up'), down: css('--down'), line: css('--line'), muted: css('--muted'), faint: css('--faint'), main: css('--focus'), sub: css('--event') };
    const alpha = (hex, a) => hex + Math.round(a * 255).toString(16).padStart(2, '0'); // #rrggbb → #rrggbbaa

    /** 맨 위 현재가만 서버가 밀어 준다(SSE, 약 1초). */
    function openStream() {
      if (st.stream) st.stream.close();
      st.live = null;
      st.stream = new EventSource(`/api/quote-stream?symbol=${encodeURIComponent(st.symbol)}`);
      st.stream.onmessage = (e) => {
        const live = JSON.parse(e.data);
        if (live.symbol !== st.symbol.toUpperCase()) return;
        st.live = live;
        render();
      };
    }

    async function loadChart() {
      const { symbol, interval } = st;
      if (!symbol) return;
      try {
        const res = await fetch('/api/stock-chart?' + new URLSearchParams({ symbol, interval }));
        const c = await res.json();
        if (!res.ok) throw new Error(c.error || '서버 오류 ' + res.status);
        if (interval !== st.interval || symbol !== st.symbol) return; // 그새 바꿨으면 버린다
        st.chart = c;
      } catch (e) {
        if (interval !== st.interval || symbol !== st.symbol) return;
        st.chart = { error: e.message };
      }
      render();
    }

    function render() {
      const d = st.d, c = st.chart;
      if (!d || d.symbol !== st.symbol?.toUpperCase()) return;
      // 실시간 현재가(SSE)가 있으면 그걸, 없으면 대시보드 값을 쓴다
      const live = st.live?.symbol === d.symbol ? st.live : null;
      const spot = live ? live.price : d.spot;
      const prev = live?.previousClose ?? d.previousClose;
      const spotTime = new Date(live ? live.asOf : d.asOf);
      const phase = live ? live.session : d.session;
      const last = live ? live.lastClose : d.lastClose;
      const extended = phase !== 'REGULAR' && last;
      el.sym.textContent = d.symbol;
      el.price.textContent = '$' + n2(spot);
      // 정규장은 전일 종가 대비, 장 밖은 증권사 앱처럼 "정규장 $X로 마감 ±(그날 등락) · 애프터마켓에서 ±(마감가 대비)"
      el.chg.innerHTML = extended
        ? `<span class="quote-ext">정규장 $${n2(last.close)}로 마감 ${move(last.close, last.previousClose)} · ${SESSION_LABEL[phase]} ${move(spot, last.close)}</span>`
        : move(spot, prev);
      if (!c || c.error || c.symbol !== d.symbol) return message(c?.error || '차트 불러오는 중…');
      if (!c.candles.length) return message('차트 데이터가 없습니다.');
      if (!drawChart(c)) return message('차트 라이브러리를 불러오지 못했습니다(인터넷 연결 확인).');
      // 장중이면 마지막 봉을 실시간 현재가로 이어 그린다(봉은 1분, 현재가는 약 1초마다 갱신)
      if (d.marketOpen && c.live) liveCandle(c, spot, spotTime);
      const m = d.expectedMove;
      setLine('prev', c.previousClose, { color: COLOR.faint, title: '전일' });
      setLine('bandHi', m ? spot + m.dollars : null, { color: COLOR.muted, title: '예상 상단' });
      setLine('bandLo', m ? spot - m.dollars : null, { color: COLOR.muted, title: '예상 하단' });
      el.note.textContent = [
        c.intraday ? `${INTERVAL_LABEL[c.interval]}봉, 정규장(09:30–16:00 ET)만 · 시각은 미국 동부` : `${INTERVAL_LABEL[c.interval]}봉 · ${chartDate(c.candles[0].time)}부터(데이터 소스 상장 이후)`,
        c.previousClose ? `점선 = 전일 종가 ${n2(c.previousClose)}` : null,
        extended ? '장 밖 등락은 24시간 거래되는 선물 가격 기준(실제 프리·애프터마켓 체결가 아님)' : null,
        m ? `예상 상단·하단 = 이번 만기(${md(d.expiry)})까지 시장 예상 범위 ${n2(spot - m.dollars)} ~ ${n2(spot + m.dollars)}` : null,
        sc.ind.length ? '보조지표·거래량은 해외 가상자산 거래소 주식 추종 선물 봉 기준(거래량은 실제 주식 거래량 아님, 마지막 봉 지표는 1분마다 갱신)' : null,
        '휠·드래그로 확대·이동',
      ].filter(Boolean).join(' · ');
    }

    function message(text) {
      el.msg.hidden = false;
      el.msg.textContent = text;
      el.legend.innerHTML = '';
      el.note.textContent = '';
    }

    /** 날짜·시각 표시. type은 눈금 종류(0 년, 1 월, 2 일, 3 이상 시각), 없으면 십자선 라벨. */
    function chartTime(t, type) {
      const intraday = st.chart?.intraday;
      const parts = type === 0 ? { year: 'numeric' }
        : type === 1 ? { year: '2-digit', month: 'short' }
        : type === 2 ? { month: 'numeric', day: 'numeric' }
        : type >= 3 ? { hour: '2-digit', minute: '2-digit' }
        : intraday ? { month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit' }
        : { year: 'numeric', month: 'numeric', day: 'numeric' };
      return new Date(t * 1000).toLocaleString('ko-KR', { timeZone: intraday ? 'America/New_York' : 'UTC', hourCycle: 'h23', ...parts });
    }
    const chartDate = (t) => new Date(t * 1000).toLocaleDateString('ko-KR', { timeZone: 'UTC', year: 'numeric', month: 'numeric', day: 'numeric' });

    /** 차트를 한 번 만들고, 봉 데이터가 바뀌었을 때만 다시 넣는다. 라이브러리가 없으면 false. */
    function drawChart(c) {
      if (!window.LightweightCharts) return false;
      if (!sc.chart) {
        // 크기는 직접 맞춘다(autoSize는 처음 크기가 0이라 첫 화면 범위가 틀어진다). 숨겨져 있다 보이면 범위도 다시 잡는다
        new ResizeObserver(() => {
          const wasHidden = !sc.chart.timeScale().width();
          sc.chart.resize(el.canvas.clientWidth, el.canvas.clientHeight);
          if (wasHidden && el.canvas.clientWidth) fitView();
          placePaneLabels();
        }).observe(el.canvas);
        sc.chart = LightweightCharts.createChart(el.canvas, {
          width: el.canvas.clientWidth,
          height: el.canvas.clientHeight,
          layout: { background: { color: 'transparent' }, textColor: COLOR.muted, fontSize: 12, panes: { separatorColor: COLOR.line, separatorHoverColor: alpha(COLOR.muted, 0.3) } },
          grid: { vertLines: { color: COLOR.line }, horzLines: { color: COLOR.line } },
          rightPriceScale: { borderColor: COLOR.line },
          timeScale: { borderColor: COLOR.line, rightOffset: 4, tickMarkFormatter: (t, type) => chartTime(t, type) },
          localization: { locale: 'ko-KR', timeFormatter: (t) => chartTime(t) },
          crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
        });
        sc.series = sc.chart.addSeries(LightweightCharts.CandlestickSeries, { upColor: COLOR.up, downColor: COLOR.down, wickUpColor: COLOR.up, wickDownColor: COLOR.down, borderVisible: false });
        sc.chart.subscribeCrosshairMove((p) => showLegend(p.seriesData?.get(sc.series)));
        buildIndicators();
      }
      if (sc.data !== c) {
        const changed = sc.data?.symbol !== c.symbol || sc.data?.interval !== c.interval;
        sc.data = c;
        sc.series.setData(c.candles);
        sc.ind.forEach((x) => x.fill(c));
        sc.last = { ...c.candles[c.candles.length - 1] };
        if (changed) {
          sc.chart.applyOptions({ timeScale: { timeVisible: c.intraday } }); // 분봉만 눈금에 시각
          fitView();
        }
      }
      el.msg.hidden = true;
      showLegend();
      return true;
    }

    /** 최근 150개 봉이 보이게(적으면 전부). 종목·봉 단위를 바꿨을 때. */
    function fitView() {
      const n = sc.data?.candles.length ?? 0, ts = sc.chart.timeScale();
      if (n > 150) ts.setVisibleLogicalRange({ from: n - 150, to: n + 4 });
      else ts.fitContent();
    }

    /** 현재가로 마지막 봉을 고치거나, 분봉 시간이 넘어갔으면 새 봉을 연다. 60분봉은 09:30에 맞춰 :30에 자른다. */
    function liveCandle(c, spot, at) {
      const t = Math.floor(at.getTime() / 1000);
      let time = sc.last.time;
      if (c.intraday) {
        const step = INTERVAL_SEC[c.interval], offset = 1800 % step;
        time = Math.floor((t - offset) / step) * step + offset;
        if (time < sc.last.time) return;
      }
      sc.last = time === sc.last.time
        ? { ...sc.last, high: Math.max(sc.last.high, spot), low: Math.min(sc.last.low, spot), close: spot }
        : { time, open: spot, high: spot, low: spot, close: spot };
      sc.series.update(sc.last);
      showLegend();
    }

    /** 왼쪽 위 범례: 십자선이 가리키는 봉(없으면 마지막 봉)의 시·고·저·종과 전 봉 대비 등락. 아래 칸 지표 값은 각 칸 왼쪽 위 이름표에. */
    function showLegend(bar) {
      const b = bar || sc.last;
      if (!b) return;
      const candles = sc.data.candles;
      const i = candles.findIndex((x) => x.time === b.time);
      const prev = (i > 0 ? candles[i - 1] : i === -1 ? candles[candles.length - 1] : null)?.close;
      el.legend.innerHTML = `${chartTime(b.time)} &nbsp;시 <b>${n2(b.open)}</b>고 <b>${n2(b.high)}</b>저 <b>${n2(b.low)}</b>종 <b>${n2(b.close)}</b>${prev ? move(b.close, prev) : ''}`
        + overlayLegend(i === -1 ? candles.length - 1 : i);
      paneLegend(i === -1 ? candles.length - 1 : i);
    }

    /** 지표 이름과 그 봉 값. 이름 색 = 선 색. */
    function indicatorParts(i) {
      const v = sc.data.indicators, at = (arr) => n2(arr[i]);
      const tag = (color, name, value) => `<span style="color:${color}">${name}</span> <b>${value}</b>`;
      return {
        ma: MA_LINES.map(([n, color]) => tag(css(color), 'MA' + n, at(v['ma' + n]))).join(''),
        bb: tag(COLOR.faint, 'BB(20,2)', `${at(v.bbLower)} ~ ${at(v.bbUpper)}`),
        vol: tag(COLOR.muted, '거래량', Math.round(sc.data.candles[i].volume).toLocaleString('ko-KR')),
        rsi: tag(COLOR.main, 'RSI(14)', at(v.rsi)),
        stoch: tag(COLOR.main, 'Stoch(14,3,3) %K', at(v.stochK)) + tag(COLOR.sub, '%D', at(v.stochD)),
        macd: tag(COLOR.main, 'MACD(12,26,9)', at(v.macd)) + tag(COLOR.sub, '시그널', at(v.macdSignal)),
      };
    }

    /** 범례 둘째 줄: 가격 칸에 겹친 지표(이동평균·볼린저)의 그 봉 값. */
    function overlayLegend(i) {
      const parts = indicatorParts(i);
      const shown = INDICATORS.filter(([k, , own]) => !own && picked.has(k)).map(([k]) => parts[k]);
      return shown.length ? '<br>' + shown.join(' ') : '';
    }

    /** 아래 칸 이름표에 그 봉 값. */
    function paneLegend(i) {
      const parts = indicatorParts(i);
      el.paneLabels.querySelectorAll('[data-key]').forEach((x) => { x.innerHTML = parts[x.dataset.key]; });
    }

    /** 아래 칸마다 왼쪽 위에 이름표를 놓는다(칸 위치는 라이브러리가 정하니 그려진 뒤 읽는다). */
    function placePaneLabels() {
      const top = el.box.getBoundingClientRect().top;
      el.paneLabels.querySelectorAll('[data-key]').forEach((x, j) => {
        const pane = sc.chart?.panes()[j + 1]?.getHTMLElement();
        if (pane) x.style.top = pane.getBoundingClientRect().top - top + 4 + 'px';
      });
    }

    /** 고른 지표의 시리즈를 처음부터 다시 만든다(시리즈가 없어진 칸은 라이브러리가 지운다). */
    function buildIndicators() {
      sc.ind.forEach((x) => sc.chart.removeSeries(x.series));
      sc.ind = [];
      let pane = 0;
      for (const [key, , ownPane] of INDICATORS) {
        if (!picked.has(key)) continue;
        if (ownPane) pane += 1;
        addIndicator(key, ownPane ? pane : 0);
      }
      el.box.style.height = PRICE_PANE_PX + SUB_PANE_PX * pane + 'px';
      sc.chart.panes().forEach((p, i) => p.setStretchFactor(i === 0 ? PRICE_PANE_PX : SUB_PANE_PX));
      el.paneLabels.innerHTML = INDICATORS.filter(([k, , own]) => own && picked.has(k)).map(([k]) => `<div class="quote-pane-label" data-key="${k}"></div>`).join('');
      if (sc.data) sc.ind.forEach((x) => x.fill(sc.data));
      requestAnimationFrame(placePaneLabels);
    }

    /** 지표 하나의 시리즈들을 pane 칸에 만든다. */
    function addIndicator(key, pane) {
      const points = (c, arr) => c.candles.flatMap((b, i) => (arr[i] == null ? [] : [{ time: b.time, value: arr[i] }]));
      const line = (field, color, extra = {}) => {
        const series = sc.chart.addSeries(LightweightCharts.LineSeries, { color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, ...extra }, pane);
        sc.ind.push({ key, series, fill: (c) => series.setData(points(c, c.indicators[field])) });
        return series;
      };
      const guides = (series, levels) => levels.forEach((price) => series.createPriceLine({ price, color: alpha(COLOR.muted, 0.5), lineWidth: 1, lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: false }));
      // RSI·스토캐스틱: 눈금을 0~100으로 고정하고, 과매수·과매도 기준선 사이(보통 구간)를 옅게 칠한다.
      // 칠하기는 기준선 높이(hi)의 일정한 값을 baseValue(lo)까지 채우는 Baseline 시리즈로 한다
      const fixed = { autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }) };
      const zone = (lo, hi) => {
        const band = sc.chart.addSeries(LightweightCharts.BaselineSeries, {
          baseValue: { type: 'price', price: lo }, topLineColor: 'transparent', bottomLineColor: 'transparent',
          topFillColor1: alpha(COLOR.main, 0.07), topFillColor2: alpha(COLOR.main, 0.07),
          priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, ...fixed,
        }, pane);
        sc.ind.push({ key, series: band, fill: (c) => band.setData(c.candles.map((b) => ({ time: b.time, value: hi }))) });
        guides(band, [lo, hi]);
      };
      if (key === 'ma') MA_LINES.forEach(([n, color]) => line('ma' + n, css(color)));
      if (key === 'bb') { line('bbUpper', COLOR.faint); line('bbLower', COLOR.faint); }
      if (key === 'rsi') { zone(30, 70); line('rsi', COLOR.main, { lastValueVisible: true, ...fixed }); }
      if (key === 'stoch') { zone(20, 80); line('stochK', COLOR.main, { lastValueVisible: true, ...fixed }); line('stochD', COLOR.sub, fixed); }
      if (key === 'macd') {
        const hist = sc.chart.addSeries(LightweightCharts.HistogramSeries, { priceLineVisible: false, lastValueVisible: false }, pane);
        sc.ind.push({ key, series: hist, fill: (c) => hist.setData(c.candles.flatMap((b, i) => {
          const h = c.indicators.macdHist[i];
          return h == null ? [] : [{ time: b.time, value: h, color: alpha(h >= 0 ? COLOR.up : COLOR.down, 0.5) }];
        })) });
        guides(line('macd', COLOR.main, { lastValueVisible: true }), [0]);
        line('macdSignal', COLOR.sub);
      }
      if (key === 'vol') {
        const vol = sc.chart.addSeries(LightweightCharts.HistogramSeries, { priceLineVisible: false, priceFormat: { type: 'volume' } }, pane);
        sc.ind.push({ key, series: vol, fill: (c) => vol.setData(c.candles.map((b) => ({ time: b.time, value: b.volume, color: alpha(b.close >= b.open ? COLOR.up : COLOR.down, 0.5) }))) });
      }
    }

    /** 가로 기준선(점선). price가 없으면 지운다. */
    function setLine(key, price, { color, title }) {
      const line = sc.lines[key];
      if (price == null) { if (line) sc.series.removePriceLine(line); delete sc.lines[key]; return; }
      const opts = { price, color, title, lineWidth: 1, lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: true };
      if (line) line.applyOptions(opts);
      else sc.lines[key] = sc.series.createPriceLine(opts);
    }

    el.ranges.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-interval]');
      if (!b || b.dataset.interval === st.interval) return;
      st.interval = b.dataset.interval;
      st.chart = null;
      el.ranges.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      render();
      loadChart();
    });
    el.ind.addEventListener('change', () => {
      picked = new Set([...el.ind.querySelectorAll('input:checked')].map((x) => x.value));
      localStorage.setItem(IND_STORE, JSON.stringify([...picked]));
      if (!sc.chart) return;
      buildIndicators();
      render();
    });
    setInterval(loadChart, CHART_POLL_MS);

    return {
      setSymbol(symbol) {
        if (symbol === st.symbol) return;
        Object.assign(st, { symbol, chart: null, d: null });
        message('차트 불러오는 중…');
        loadChart();
        openStream();
      },
      update(d) { st.d = d; render(); },
      redraw: render,
    };
  }

  return { mount };
})();
