// 모의투자 공통: 빨리감기 연습(drill.html)과 실시간 모의투자(paper.html)가 같이 쓴다.
// 계좌·빨리감기 기록은 서버 DB(/api/paper/store)에 저장하고, 서버는 옵션 값(이론가·만기 정산)도 계산한다.
// 임시: 로그인이 없어 계좌는 사이트 전체에 하나다(2026-11-26). 로그인이 생기면 사용자별로 나눈다.
// 체결은 이론가(중간가) 한 가격, 수수료 없음 — 연습은 판단에 집중하게(2026-11-25 사용자 결정).
//
// 계좌   { cash, createdAt, positions: [포지션], closed: [포지션], trades: [{ t, pos, action, cash }] }
// 포지션 { id, symbol, expiry, label, kind, sets, legs: [{ type, strike, qty, entry }], cost, reserve,
//          openedAt, closedAt, proceeds, entry: { spot, iv, move }, exit: { spot, iv } }
//   qty: 1세트당 계약 수(+ 매수, − 매도). cost: 진입 때 나간 현금(받았으면 −).
//   reserve: 손실 한도가 있는 매도 전략의 최대 손실만큼 묶어 둔 증거금.
// 시각 t는 실시간이면 ISO 문자열, 빨리감기면 게임 날짜(숫자)다.

const Paper = (() => {
  const START_CASH = 10000;
  const MULT = 100; // 1계약 = 100주

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const money = (v) => (v == null ? '–' : (v < 0 ? '−$' : '$') + Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
  const signedMoney = (v) => (v == null ? '–' : (v > 0 ? '+' : '') + money(v));
  const pct = (v, d = 1) => (v == null || !isFinite(v) ? '–' : (v * 100).toFixed(d) + '%');
  const signedPct = (v, d = 1) => (v == null || !isFinite(v) ? '–' : (v >= 0 ? '+' : '') + (v * 100).toFixed(d) + '%');
  const tone = (v) => (v == null ? '' : v > 0 ? 'up' : v < 0 ? 'down' : '');
  const strikeText = (k) => (k % 1 === 0 ? String(k) : String(+k.toFixed(3)));
  const legText = (l) => `${l.qty > 0 ? '매수' : '매도'} ${strikeText(l.strike)} ${l.type === 'CALL' ? '콜' : '풋'}${Math.abs(l.qty) > 1 ? ' ×' + Math.abs(l.qty) : ''}`;
  const etDate = (iso) => new Date(iso).toLocaleDateString('en-CA', { timeZone: 'America/New_York' });

  // ── 저장 ──
  const newAccount = (createdAt) => ({ cash: START_CASH, createdAt, positions: [], closed: [], trades: [] });
  // 시작할 때 sync()로 서버 값을 한 번 받아 두고, load/save는 그 사본을 읽고 쓰며 바뀐 값을 서버에 보낸다.
  let store = {};
  const put = (key, value) => fetch(`/api/paper/store/${key}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value ?? null), keepalive: true })
    .then((res) => { if (!res.ok) throw new Error(res.statusText); })
    .catch((err) => notice('서버에 저장하지 못했습니다: ' + err.message));
  async function sync() {
    const res = await fetch('/api/paper/store');
    if (!res.ok) throw new Error('저장된 계좌를 불러오지 못했습니다');
    store = await res.json();
  }
  const load = (key) => structuredClone(store[key] ?? null);
  const save = (key, value) => { store[key] = structuredClone(value ?? null); return put(key, value); };

  // ── 계산 ──
  const intrinsic = (type, s, k) => Math.max(0, type === 'CALL' ? s - k : k - s);
  /** 옵션 다리들이 만기 주가 p에서 주는 돈(주당). */
  const payout = (legs, p) => legs.reduce((sum, l) => sum + l.qty * intrinsic(l.type, p, l.strike), 0);
  /** 손익 곡선은 행사가에서만 꺾이니 0, 행사가들, 아주 높은 가격만 보면 된다. */
  const kinks = (legs) => { const ks = [...new Set(legs.map((l) => l.strike))].sort((a, b) => a - b); return [0, ...ks, ks[ks.length - 1] * 3]; };
  /** 콜을 판 수가 산 수보다 많으면 주가가 오를수록 손실이 끝없이 커진다. */
  const unlimited = (legs) => legs.reduce((sum, l) => sum + (l.type === 'CALL' ? l.qty : 0), 0) < 0;
  const reserveFor = (legs, sets) => Math.max(0, -Math.min(...kinks(legs).map((p) => payout(legs, p)))) * MULT * sets;
  const reserved = (acc) => acc.positions.reduce((sum, p) => sum + p.reserve, 0);
  const buyingPower = (acc) => acc.cash - reserved(acc);
  /** 들어갈 때 내는(받으면 −) 돈, 1세트 기준 주당. marks[i]는 legs[i]의 지금 값. */
  const premiumOf = (legs, marks) => legs.reduce((sum, l, i) => sum + l.qty * marks[i].mid, 0);

  /** 주문 미리보기. */
  function preview(acc, legs, sets, marks) {
    if (!legs.length) return { error: '다리를 하나 이상 담으세요.' };
    if (unlimited(legs)) return { error: '받쳐 주는 콜 없이 콜을 팔면 손실이 끝없이 커질 수 있어 연습에서도 막아 둡니다. 더 높은 콜을 같이 사서(베어 콜 크레딧 스프레드) 손실을 묶어 보세요.' };
    const cost = premiumOf(legs, marks) * MULT * sets;
    const reserve = reserveFor(legs, sets);
    const after = buyingPower(acc) - cost - reserve;
    return { cost, reserve, after, error: after < 0 ? `매수 가능 금액이 ${money(-after)} 모자랍니다.` : null };
  }

  function open(acc, { symbol, expiry, label, kind, legs, sets, marks, t, entry }) {
    const p = preview(acc, legs, sets, marks);
    if (p.error) return p.error;
    const pos = {
      id: crypto.randomUUID(), symbol, expiry, label, kind, sets,
      legs: legs.map((l, i) => ({ ...l, entry: marks[i].mid })),
      cost: p.cost, reserve: p.reserve, openedAt: t, entry,
    };
    acc.cash -= p.cost;
    acc.positions.push(pos);
    acc.trades.push({ t, pos: pos.id, action: 'OPEN', cash: -p.cost });
    return null;
  }

  /** 청산(지금 이론가) 또는 만기 정산(내재가치). */
  function close(acc, id, marks, t, exit, settled = false) {
    const pos = acc.positions.find((p) => p.id === id);
    const proceeds = positionValue(pos, marks);
    acc.cash += proceeds;
    acc.positions = acc.positions.filter((p) => p !== pos);
    acc.closed.unshift({ ...pos, closedAt: t, proceeds, settled, exit });
    acc.trades.push({ t, pos: id, action: settled ? 'SETTLE' : 'CLOSE', cash: proceeds });
  }

  const positionValue = (pos, marks) => premiumOf(pos.legs, marks) * MULT * pos.sets;
  const equity = (acc, marksByPos) => acc.cash + acc.positions.reduce((sum, p) => sum + (marksByPos[p.id] ? positionValue(p, marksByPos[p.id]) : 0), 0);
  /** 수익률 기준 금액: 손익 ÷ (낸 돈, 받은 전략이면 묶인 증거금). */
  const basis = (pos) => Math.max(Math.abs(pos.cost), pos.reserve) || 1;

  /** 만기 손익(1세트, $). 무한이면 null. 본전은 주가. */
  function analyze(legs, marks) {
    const premium = premiumOf(legs, marks);
    const points = kinks(legs);
    const values = points.map((p) => payout(legs, p) - premium);
    const right = legs.reduce((s, l) => s + (l.type === 'CALL' ? l.qty : 0), 0);
    const breakevens = [];
    for (let i = 0; i < points.length - 1; i++) {
      const [a, b, fa, fb] = [points[i], points[i + 1], values[i], values[i + 1]];
      if (fa === 0) breakevens.push(a); else if (fa * fb < 0) breakevens.push(a + (b - a) * fa / (fa - fb));
    }
    return { premium: premium * MULT, maxProfit: right > 0 ? null : Math.max(...values) * MULT, maxLoss: right < 0 ? null : -Math.min(...values) * MULT, breakevens };
  }

  // ── 전략 후보: 전망마다 ATM 기준으로 만든다. 고르는 건 사용자, 여기서는 후보와 기준만 보여 준다 ──
  const VIEWS = [['ALL', '전체'], ['UP', '오를 것 같다'], ['DOWN', '내릴 것 같다'], ['BIG_MOVE', '크게 움직일 것 같다'], ['NOT_DOWN', '안 떨어질 것 같다'], ['NOT_UP', '안 오를 것 같다']];
  const VIEW_LABEL = Object.fromEntries(VIEWS);

  /** 그 만기 호가창(rows)으로 만들 수 있는 전망별 후보. 행사가가 모자라면 그 후보는 빠진다. */
  function candidates(rows) {
    const ks = rows.map((r) => r.strike);
    const i = rows.findIndex((r) => r.atm);
    if (i < 0) return [];
    const k = (n) => ks[i + n];
    const C = (n, qty) => k(n) != null && { type: 'CALL', strike: k(n), qty };
    const Pu = (n, qty) => k(n) != null && { type: 'PUT', strike: k(n), qty };
    const list = [
      ['UP', '콜 사기 (등가격)', 'call', [C(0, 1)]],
      ['UP', '콜 사기 (2칸 위, 더 쌈)', 'call', [C(2, 1)]],
      ['UP', '불 콜 스프레드', 'BULL_CALL_SPREAD', [C(0, 1), C(2, -1)]],
      ['DOWN', '풋 사기 (등가격)', 'put', [Pu(0, 1)]],
      ['DOWN', '풋 사기 (2칸 아래, 더 쌈)', 'put', [Pu(-2, 1)]],
      ['DOWN', '베어 풋 스프레드', 'BEAR_PUT_SPREAD', [Pu(0, 1), Pu(-2, -1)]],
      ['BIG_MOVE', '롱 스트래들', 'LONG_STRADDLE', [C(0, 1), Pu(0, 1)]],
      ['BIG_MOVE', '롱 스트랭글 (±1칸)', 'LONG_STRANGLE', [Pu(-1, 1), C(1, 1)]],
      ['BIG_MOVE', '롱 스트랭글 (±2칸, 더 쌈)', 'LONG_STRANGLE', [Pu(-2, 1), C(2, 1)]],
      ['NOT_DOWN', '풋 팔기 (1칸 아래)', 'SHORT_PUT', [Pu(-1, -1)]],
      ['NOT_DOWN', '불 풋 크레딧 스프레드', 'BULL_PUT_CREDIT_SPREAD', [Pu(-1, -1), Pu(-3, 1)]],
      ['NOT_UP', '베어 콜 크레딧 스프레드', 'BEAR_CALL_CREDIT_SPREAD', [C(1, -1), C(3, 1)]],
      ['NOT_UP', '베어 콜 크레딧 스프레드 (더 멀리)', 'BEAR_CALL_CREDIT_SPREAD', [C(2, -1), C(4, 1)]],
    ];
    return list.filter(([, , , legs]) => legs.every(Boolean)).map(([view, name, kind, legs]) => ({ view, name, kind, legs }));
  }

  /**
   * 후보마다 손익과 "본전까지 필요한 움직임 vs 시장이 예상하는 움직임(예상 변동폭)"을 붙인다.
   * 순위·추천은 매기지 않는다(2026-11-25 사용자 결정: 기준만 보여 주고 고르는 건 직접).
   */
  function rate(ideas, rows, spot, move) {
    const rated = ideas.map((idea) => {
      const a = analyze(idea.legs, marksFor(rows, idea.legs));
      const need = Math.min(...a.breakevens.map((b) => Math.abs(b / spot - 1)));
      const selling = a.premium < 0;
      const fits = move == null || !isFinite(need) ? null : selling ? need >= move : need <= move;
      return { ...idea, a, need, selling, fits };
    });
    return rated;
  }

  function ideaCards(rated, spot, move, view) {
    const shown = rated.map((x, i) => [x, i]).filter(([x]) => view === 'ALL' || x.view === view);
    if (!shown.length) return '<p class="muted">이 만기 호가창으로 만들 수 있는 후보가 없습니다.</p>';
    return shown.map(([x, i]) => {
      const be = x.a.breakevens.map((b) => `${b.toFixed(2)} <span class="faint">(${signedPct(b / spot - 1)})</span>`).join(' / ') || '–';
      const basisLine = move == null ? '' : x.selling
        ? `본전까지 ${pct(x.need)} 여유 · 시장 예상 ±${pct(move)} ${x.fits ? '바깥이라 여유 있음' : '안쪽이라 예상만큼만 움직여도 손실 가능'}`
        : `본전까지 ${pct(x.need)} 움직여야 함 · 시장 예상 ±${pct(move)} ${x.fits ? '안쪽' : '보다 더 움직여야 이익'}`;
      return `<div class="panel card"><span class="chip">${esc(VIEW_LABEL[x.view])}</span>
        <div class="plain">${esc(x.name)}${term(x.kind)}</div>
        <p class="sub-line">${esc(x.legs.map(legText).join(' + '))}</p>
        <dl class="terms">
          <div><dt>${x.selling ? '받는 돈' : '내는 돈'} (1세트)</dt><dd>${money(Math.abs(x.a.premium))}</dd></div>
          <div><dt>최대 이익</dt><dd class="up">${x.a.maxProfit == null ? '무한' : money(x.a.maxProfit)}</dd></div>
          <div><dt>최대 손실</dt><dd class="down">${x.a.maxLoss == null ? '무한' : money(x.a.maxLoss)}</dd></div>
          <div><dt>만기 본전${term('breakeven')}</dt><dd>${be}</dd></div>
        </dl>
        ${basisLine ? `<p class="note${x.fits === false ? ' warn' : ''}">${basisLine}</p>` : ''}
        <button class="ghost" data-pick="${i}">주문표에 담기</button></div>`;
    }).join('');
  }

  const viewPills = (view) => VIEWS.map(([v, label]) => `<button data-view="${v}" aria-pressed="${v === view}">${label}</button>`).join('');

  // ── 판단 기준: 숫자 타일 + 일정 타임라인 + 참고 구간 ──
  const ZONES = {
    PREMIUM_SELLING: '파는 쪽이 유리한 구간 (풋 팔기, 크레딧 스프레드)',
    PREMIUM_BUYING: '사는 쪽이 유리한 구간 (스트래들, 스트랭글)',
    EARNINGS_LONG_VOL: '실적 앞, 사는 쪽이 유리한 구간',
    EARNINGS_OVERPRICED: '실적 앞, 파는 쪽이 유리한 구간',
  };
  const level = (v, lo, hi) => (v == null ? '' : v >= hi ? '<span class="event">비싼 편</span>' : v <= lo ? '<span class="up">싼 편</span>' : '보통');

  /** 숫자 타일. tiles: [{ label, value, sub }] (label·value·sub는 HTML). */
  const tilesHtml = (tiles) => `<div class="tiles">${tiles.map((t) => `<div class="tile"><div class="k">${t.label}</div><div class="v">${t.value}</div>${t.sub ? `<div class="s">${t.sub}</div>` : ''}</div>`).join('')}</div>`;

  /**
   * 실시간 모의투자의 판단 기준 타일.
   * c: { spot, move, moveDollars, iv, hv, ivHv, percentile, realized, expiryLabel }
   */
  function criteriaTiles(c) {
    const r = c.realized;
    return tilesHtml([
      { label: `${esc(c.expiryLabel)} 만기까지 예상 변동폭${term('expectedMove')}`, value: c.move == null ? '–' : `±${pct(c.move)}`,
        sub: c.moveDollars == null ? '' : `±$${c.moveDollars.toFixed(2)} · ${(c.spot - c.moveDollars).toFixed(2)} ~ ${(c.spot + c.moveDollars).toFixed(2)}` },
      { label: `ATM IV${term('iv')}`, value: pct(c.iv),
        sub: `최근 20일 실제 ${pct(c.hv)}${term('hv')} · ${c.ivHv == null ? '–' : c.ivHv.toFixed(2) + '배'} ${level(c.ivHv, 0.9, 1.3)}` },
      { label: `오늘 움직임 ÷ 하루 예상${term('realizedVsExpected')}`, value: r ? `<span class="${tone(r.realizedPct)}">${signedPct(r.realizedPct, 2)}</span>` : '–',
        sub: r ? `하루 예상 ±${pct(r.expectedPct)}의 <strong>${Math.round(r.ratio * 100)}%</strong>${r.ratio >= 1 ? ' · 예상보다 크게 움직임' : ''}` : '' },
      { label: `IV 1년 위치${term('ivPercentile')}`, value: c.percentile == null ? '기록 부족' : pct(c.percentile, 0), sub: c.percentile == null ? '쌓인 기록이 1년치가 안 됨' : level(c.percentile, 0.2, 0.8) },
    ]);
  }

  /**
   * 일정 타임라인. events: [{ days, dateLabel, when, label, kind: 'EARNINGS'|'MACRO', inside }] 가까운 순.
   * 선택한 만기 전 일정(inside)은 노랑으로 강조하고, 만기 다음에 구분선을 긋는다.
   */
  function eventsHtml(events, expiryLabel) {
    if (!events.length) return '<p class="muted">예정된 일정이 없습니다.</p>';
    const insideCount = events.filter((x) => x.inside).length;
    let divided = false;
    const rows = events.map((x) => {
      const divider = !x.inside && !divided ? (divided = true, `<li class="ev-divider"><span>${esc(expiryLabel)} 만기</span></li>`) : '';
      return `${divider}<li class="ev${x.inside ? ' in' : ''}">
        <span class="ev-day">${x.days <= 0 ? '오늘' : 'D-' + x.days}</span>
        <span class="ev-date">${esc(x.dateLabel)}</span>
        <span class="ev-name">${x.kind === 'EARNINGS' ? '<span class="ev-tag">실적</span>' : ''}${esc(x.label)}</span>
        <span class="ev-when">${esc(x.when)}</span></li>`;
    }).join('');
    return `<p class="ev-sum">${insideCount ? `<strong class="event">${esc(expiryLabel)} 만기 전 일정 ${insideCount}건</strong> · 발표 때 크게 움직일 수 있음` : `${esc(expiryLabel)} 만기 전에는 큰 일정이 없음`}</p><ul class="timeline">${rows}</ul>`;
  }

  const zonesHtml = (signals) => `<p class="zone">참고 구간${term('zone')} · ${(signals || []).length
    ? signals.map((s) => `<strong>${esc(ZONES[s.zone] || s.zone)}</strong> <span class="muted">${esc(s.reason)}</span>`).join(' / ')
    : '<span class="muted">뚜렷한 구간 없음 (옵션 값이 최근 실제 움직임과 비슷한 수준)</span>'}</p>`;

  // ── 호가창과 주문표 ──

  /** 담은 다리들의 지금 값을 그 만기 호가창(rows)에서 찾는다. 없으면 그 자리는 undefined. */
  const marksFor = (rows, legs) => legs.map((l) => { const r = rows.find((x) => x.strike === l.strike); return r && (l.type === 'CALL' ? r.call : r.put); });

  /** 호가창: 콜(파랑) | 행사가 | 풋(보라). 칸마다 이론가·IV·델타, 가격을 누르면 사기로 담긴다(팔기는 주문표에서 바꾼다). */
  function chainHtml(rows, spot) {
    const iv = (l) => l.ivCalc ?? l.ivApi;
    const side = (type, k, l, itm) => {
      const bg = itm ? (type === 'CALL' ? 'c-itm' : 'p-itm') : '';
      if (!l) return '<td></td><td></td><td></td>';
      const buy = `<td class="${bg}"><button class="quote buy" data-type="${type}" data-strike="${k}">${l.mid.toFixed(2)}</button></td>`;
      const ivCell = `<td class="${bg} faint">${pct(iv(l))}</td>`;
      const deltaCell = `<td class="${bg} faint">${l.delta == null ? '–' : l.delta.toFixed(2)}</td>`;
      // 행사가 쪽으로 가격이 붙게: 콜은 IV·델타·가격, 풋은 가격·델타·IV
      return type === 'CALL' ? ivCell + deltaCell + buy : buy + deltaCell + ivCell;
    };
    const body = rows.map((r) => `<tr class="${r.atm ? 'atm' : ''}">${side('CALL', r.strike, r.call, r.strike < spot)}<td class="strike">${strikeText(r.strike)}</td>${side('PUT', r.strike, r.put, r.strike > spot)}</tr>`).join('');
    return `<div class="scroll"><table class="chain"><thead>
      <tr><th colspan="3" class="call-h">콜${term('call')}</th><th class="strike"></th><th colspan="3" class="put-h">풋${term('put')}</th></tr>
      <tr><th>IV${term('iv')}</th><th>델타${term('delta')}</th><th>가격 (눌러서 사기)</th><th class="strike">행사가${term('strike')}</th><th>가격 (눌러서 사기)</th><th>델타</th><th>IV</th></tr>
      </thead><tbody>${body}</tbody></table></div>`;
  }

  const singleName = (l) => `${strikeText(l.strike)} ${l.type === 'CALL' ? '콜' : '풋'} ${l.qty > 0 ? '사기' : '팔기'}${Math.abs(l.qty) > 1 ? ' ×' + Math.abs(l.qty) : ''}`;
  const relabel = (ticket) => Object.assign(ticket, { label: ticket.legs.length === 1 ? singleName(ticket.legs[0]) : '직접 만든 조합', kind: null });

  /** 담은 다리 + 매수/매도 전환 + 세트 수 + 미리보기 + 주문 버튼. */
  function ticketHtml(acc, ticket, marks, blocked) {
    if (!ticket.legs.length) return '<p class="muted">호가창에서 가격을 누르면 <strong>사기</strong>로 담깁니다. 팔고 싶으면 담은 뒤 <strong>매도로</strong>를 누르세요. 여러 개를 담아 스프레드를 만들거나, 아래 후보에서 "주문표에 담기"로 한 번에 담을 수 있습니다.</p>';
    const p = preview(acc, ticket.legs, ticket.sets, marks);
    const rows = ticket.legs.map((l, i) => `<tr><td class="left"><span class="${l.type === 'CALL' ? 'call' : 'put'}">${esc(legText(l))}</span></td>
      <td>${money(marks[i].mid)}</td>
      <td><button class="link" data-flip="${i}">${l.qty > 0 ? '매도로' : '매수로'}</button> · <button class="link" data-remove="${i}">빼기</button></td></tr>`).join('');
    const a = analyze(ticket.legs, marks);
    return `<div class="ticket-name">${esc(ticket.label)}</div>
      <table><thead><tr><th class="left">다리 (1세트)</th><th>가격(주당)</th><th></th></tr></thead><tbody>${rows}</tbody></table>
      <label class="sets">세트 수 <input id="sets" type="number" min="1" max="100" value="${ticket.sets}"></label>
      <dl class="terms">
        <div><dt>${(p.cost ?? 0) >= 0 ? '내는 돈' : '받는 돈'}</dt><dd>${money(Math.abs(p.cost ?? 0))}</dd></div>
        <div><dt>최대 이익 / 손실</dt><dd><span class="up">${a.maxProfit == null ? '무한' : money(a.maxProfit * ticket.sets)}</span> / <span class="down">${a.maxLoss == null ? '무한' : money(a.maxLoss * ticket.sets)}</span></dd></div>
        <div><dt>묶이는 증거금${term('reserve')}</dt><dd>${money(p.reserve)}</dd></div>
        <div><dt>주문 뒤 매수 가능 금액</dt><dd class="${tone(p.after)}">${money(p.after)}</dd></div>
      </dl>
      ${p.error ? `<p class="note warn">${esc(p.error)}</p>` : ''}
      ${blocked ? `<p class="note warn">${esc(blocked)}</p>` : ''}
      <button class="primary" id="submit" ${p.error || blocked ? 'disabled' : ''}>모의 주문</button>`;
  }

  /** 호가 담기·매수/매도 전환·빼기·후보 담기를 처리. ideas는 지금 화면의 후보 목록. 처리했으면 true. */
  function ticketClick(t, ticket, ideas) {
    if (t.dataset.type) {
      const same = ticket.legs.find((l) => l.type === t.dataset.type && l.strike === Number(t.dataset.strike));
      if (same) same.qty += 1; else ticket.legs.push({ type: t.dataset.type, strike: Number(t.dataset.strike), qty: 1 });
      ticket.legs = ticket.legs.filter((l) => l.qty !== 0);
      relabel(ticket);
    } else if (t.dataset.flip) {
      ticket.legs[Number(t.dataset.flip)].qty *= -1;
      relabel(ticket);
    } else if (t.dataset.remove) {
      ticket.legs.splice(Number(t.dataset.remove), 1);
      relabel(ticket);
    } else if (t.dataset.pick) {
      const idea = ideas[Number(t.dataset.pick)];
      Object.assign(ticket, { legs: idea.legs.map((l) => ({ ...l })), sets: 1, label: idea.name, kind: idea.kind });
    } else return false;
    return true;
  }

  // ── 포지션·거래·평가액 ──

  /** 들고 있는 포지션 표. marksByPos[id]는 그 포지션 다리들의 지금 값. */
  function positionsHtml(acc, marksByPos, expiryText) {
    if (!acc.positions.length) return '<p class="muted">들고 있는 포지션이 없습니다.</p>';
    const rows = acc.positions.map((p) => {
      const m = marksByPos[p.id];
      const value = m ? positionValue(p, m) : null;
      const pl = value == null ? null : value - p.cost;
      return `<tr><td class="left"><strong>${esc(p.label)}</strong><div class="faint">${esc(p.symbol ?? '')} ${esc(p.legs.map(legText).join(' + '))}${p.sets > 1 ? ` · ${p.sets}세트` : ''}</div></td>
        <td>${esc(expiryText(p))}</td><td>${money(p.cost)}</td><td>${money(value)}</td>
        <td class="${tone(pl)}">${signedMoney(pl)}<div class="faint">${signedPct(pl == null ? null : pl / basis(p))}</div></td>
        <td><button class="link" data-close="${p.id}" ${m ? '' : 'disabled'}>청산</button></td></tr>`;
    }).join('');
    return `<div class="scroll"><table><thead><tr><th class="left">포지션</th><th>만기</th><th>진입 금액${term('debit')}</th><th>지금 가치</th><th>손익</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }

  /** 끝난 포지션: 손익과 함께 "진입 뒤 주가가 얼마나 움직였나 vs 진입 때 예상 변동폭", IV 변화를 숫자로. */
  function closedHtml(acc, timeText) {
    if (!acc.closed.length) return '<p class="muted">아직 끝난 거래가 없습니다.</p>';
    const rows = acc.closed.map((p) => {
      const pl = p.proceeds - p.cost;
      const move = p.exit?.spot && p.entry?.spot ? p.exit.spot / p.entry.spot - 1 : null;
      const vsExpected = move != null && p.entry?.move ? (Math.abs(move) > p.entry.move ? '예상보다 크게 움직임' : '예상 안쪽') : '';
      const iv = p.entry?.iv && p.exit?.iv ? `${pct(p.entry.iv)} → ${pct(p.exit.iv)}` : '–';
      return `<tr><td class="left"><strong>${esc(p.label)}</strong><div class="faint">${esc(p.symbol ?? '')} ${esc(p.legs.map(legText).join(' + '))}</div></td>
        <td>${esc(timeText(p.openedAt))} → ${esc(timeText(p.closedAt))}${p.settled ? ' <span class="faint">(만기 정산)</span>' : ''}</td>
        <td class="${tone(pl)}">${signedMoney(pl)}<div class="faint">${signedPct(pl / basis(p))}</div></td>
        <td>${signedPct(move)}<div class="faint">진입 때 예상 ±${pct(p.entry?.move)} · ${vsExpected}</div></td>
        <td>${iv}</td></tr>`;
    }).join('');
    return `<div class="scroll"><table><thead><tr><th class="left">거래</th><th>기간</th><th>손익</th><th>그동안 주가 변화</th><th>ATM IV${term('iv')}</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }

  /** 평가액 선 그래프. points: [[라벨, 값]]. 시작 자금선을 점선으로. */
  function equityChart(points, width) {
    if (points.length < 2) return '<p class="muted">하루 이상 지나면 평가액 그래프가 그려집니다.</p>';
    const w = Math.max(320, width || 720), h = 180, padL = 64, padB = 22, top = 10;
    const ys = points.map((p) => p[1]).concat(START_CASH);
    let y0 = Math.min(...ys), y1 = Math.max(...ys);
    const pad = (y1 - y0) * 0.08 || 50;
    y0 -= pad; y1 += pad;
    const X = (i) => padL + (i / (points.length - 1)) * (w - padL - 8);
    const Y = (y) => top + (1 - (y - y0) / (y1 - y0)) * (h - top - padB);
    const last = points[points.length - 1][1];
    const color = last >= START_CASH ? 'var(--up)' : 'var(--down)';
    const path = points.map((p, i) => (i ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(p[1]).toFixed(1)).join('');
    return `<svg viewBox="0 0 ${w} ${h}" width="100%" role="img" aria-label="평가액 그래프">
      <line x1="${padL}" x2="${w - 8}" y1="${Y(START_CASH)}" y2="${Y(START_CASH)}" stroke="var(--faint)" stroke-dasharray="4 4"/>
      <text x="${padL - 6}" y="${Y(START_CASH) + 4}" text-anchor="end">$${START_CASH.toLocaleString()}</text>
      <path d="${path}" fill="none" stroke="${color}" stroke-width="2"/>
      <circle cx="${X(points.length - 1)}" cy="${Y(last)}" r="4" fill="${color}"/>
      <text x="${padL}" y="${h - 4}">${esc(points[0][0])}</text>
      <text x="${w - 8}" y="${h - 4}" text-anchor="end">${esc(points[points.length - 1][0])}</text>
    </svg>`;
  }

  // ── 확인창·알림: 브라우저 기본 confirm/alert는 막히는 환경(앱 내장 브라우저 등)이 있어 화면 안 <dialog>로 띄운다 ──
  let box = null;
  function dialog(message, buttons) {
    if (!box) { box = document.createElement('dialog'); box.className = 'box'; document.body.appendChild(box); }
    box.innerHTML = `<p>${esc(message)}</p><div class="actions">${buttons.map(([label, value, cls]) => `<button class="${cls}" value="${value}">${esc(label)}</button>`).join('')}</div>`;
    return new Promise((resolve) => {
      box.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { box.close(); resolve(b.value === 'yes'); }));
      box.addEventListener('cancel', () => resolve(false), { once: true });
      box.showModal();
    });
  }
  /** 예/아니오 확인. 예면 true. */
  const ask = (message, yesLabel = '확인') => dialog(message, [['취소', 'no', 'ghost'], [yesLabel, 'yes', 'primary']]);
  /** 알림(확인 버튼 하나). */
  const notice = (message) => dialog(message, [['확인', 'yes', 'primary']]);

  return {
    ask, notice,
    START_CASH, MULT, esc, money, signedMoney, pct, signedPct, tone, strikeText, legText, etDate,
    newAccount, sync, load, save, preview, open, close, positionValue, equity, buyingPower, reserved, basis,
    analyze, candidates, rate, ideaCards, viewPills, tilesHtml, criteriaTiles, eventsHtml, zonesHtml, marksFor, chainHtml, ticketHtml, ticketClick,
    positionsHtml, closedHtml, equityChart,
  };
})();
