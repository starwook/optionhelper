// 화면 용어 옆 ? 툴팁. 세 페이지(index, iv, review)가 같이 쓴다.
// 쓰는 법: 마크업에 <span class="term-help" data-term="iv" tabindex="0">?</span>, JS에서는 term('iv').
// 설명은 아래 GLOSSARY에만 적는다. 마우스를 올리거나(PC) 누르면(모바일) 뜨고, 다른 곳을 누르면 닫힌다.

const GLOSSARY = {
  // 변동성
  iv: 'IV(내재변동성): 시장이 앞으로 주가가 얼마나 출렁일지 옵션 가격에 매겨 둔 값(1년 기준 %). 높을수록 옵션이 비싸다. 예: IV 50%면 1년에 ±50% 정도 움직인다고 보는 것.',
  hv: 'HV(역사적·실현 변동성): 실제로 지난 기간 주가가 얼마나 출렁였는지(1년 기준 %). HV20은 최근 20거래일 기준. IV와 비교해 옵션이 비싼지 본다.',
  ivHvRatio: 'IV ÷ HV: 옵션에 붙은 변동성이 최근 실제 변동성의 몇 배인지. 1.3배 이상이면 비싼 편, 0.9배 이하면 싼 편으로 본다. 예: IV 60%, HV 40%면 1.5배.',
  ivRank: 'IV Rank: 1년 최저 IV를 0%, 최고 IV를 100%로 놓고 지금 IV가 어디쯤인지. 예: 1년 범위 30~70%에서 지금 50%면 Rank 50%.',
  ivPercentile: 'IV 퍼센타일: 지난 1년 중 지금보다 IV가 낮았던 날의 비율. 예: 80%면 1년 중 80%의 날보다 지금 옵션이 비싸다.',
  termStructure: '이번 만기 IV ÷ 다음 만기 IV. 1보다 크면 이번 만기 안에 실적발표 같은 큰 이벤트가 있다고 시장이 보는 것.',
  skew: '스큐: 같은 거리만큼 떨어진 풋과 콜의 IV 차이. 플러스면 하락 대비용 풋이 더 비싸다(보통 그렇다).',
  realizedVsExpected: '실제로 움직인 폭 ÷ 옵션이 예상한 폭. 100%보다 크면 예상보다 더 움직였다는 뜻.',

  // 예상 변동폭
  expectedMove: '예상 변동폭: 같은 행사가 콜+풋(스트래들) 가격 ÷ 주가. 시장이 만기까지 이 정도 움직인다고 값을 매겼다는 뜻. 예: 주가 $100, 스트래들 $5면 ±5%.',
  rule85: '85% 규칙: 스트래들 가격은 실제 예상 움직임보다 조금 크게 잡혀 있어서, 그 85% 정도를 "1표준편차(약 68% 확률) 범위"로 보는 경험칙.',

  // 옵션 기본
  call: '콜: 정해진 가격(행사가)에 주식을 살 수 있는 권리. 주가가 오르면 값이 오른다.',
  put: '풋: 정해진 가격(행사가)에 주식을 팔 수 있는 권리. 주가가 내리면 값이 오른다.',
  strike: '행사가: 옵션으로 주식을 사거나 팔 수 있는 약속 가격. 예: 150 콜은 만기 때 $150에 살 수 있는 권리.',
  expiry: '만기: 옵션의 권리가 끝나는 날(미국 동부 장 마감). 만기가 지나면 옵션은 내재가치만 남고 사라진다.',
  premium: '프리미엄: 옵션 가격. 사는 사람이 내고 파는 사람이 받는다. 1계약은 100주라서 화면 가격 × 100이 실제 금액.',
  atm: 'ATM(등가격): 행사가가 지금 주가와 가장 가까운 옵션. 예상 변동폭과 대표 IV는 ATM 옵션으로 계산한다.',
  itm: 'ITM(내가격): 지금 행사해도 이익인 옵션(콜은 행사가 < 주가, 풋은 행사가 > 주가). 반대는 OTM(외가격), 행사가 ≈ 주가는 ATM(등가격).',
  intrinsic: '내재가치: 지금 바로 행사하면 생기는 이익. 예: 주가 $105에 100 콜이면 $5. 옵션 가격은 이보다 낮아지지 않는다.',
  mid: '중간가: 매수 호가와 매도 호가의 가운데 값. 실제 체결은 보통 이 근처에서 지정가로 맞춘다.',
  bidAsk: '매수(bid): 지금 사려는 사람이 부르는 최고가 = 내가 바로 팔 수 있는 값. 매도(ask): 팔려는 사람이 부르는 최저가 = 내가 바로 살 수 있는 값.',
  breakeven: '손익분기(본전): 만기 때 주가가 이 가격이면 이익도 손실도 없다. 토스 수수료까지 넣어 계산했다.',
  indicative: 'indicative 호가: 무료 데이터용으로 실제 거래소(OPRA) 호가를 가공한 참고값. 실시간이지만 토스 화면 호가와 조금 다를 수 있다.',

  // 그릭스
  delta: '델타: 주가가 $1 오를 때 옵션 가격이 얼마나 움직이는지. 예: 델타 0.5면 주가 +$1에 옵션 +$0.5. 콜은 0~1, 풋은 0~−1.',
  gamma: '감마: 주가가 $1 움직일 때 델타가 얼마나 바뀌는지. 만기가 가깝고 ATM일수록 크다.',
  theta: '세타: 다른 조건이 그대로일 때 하루 지나면 옵션 가격이 얼마나 빠지는지(시간 가치 감소). 산 사람에게 손해, 판 사람에게 이익.',
  vega: '베가: IV가 1%p 바뀔 때 옵션 가격이 얼마나 움직이는지. 예: 베가 $0.20이면 IV가 1%p 오를 때 옵션 +$0.20.',

  // 전략
  credit: '크레딧: 시작할 때 돈을 받는 전략(옵션을 판 값이 산 값보다 큼). 주가가 예상 범위 안에 머물면 받은 돈이 이익이 된다.',
  debit: '데빗: 시작할 때 돈을 내는 전략(옵션을 산 값이 판 값보다 큼). 최대 손실은 낸 돈.',
  zone: 'IV·HV·실적 일정 같은 교과서식 규칙으로 옵션을 사는 쪽과 파는 쪽 중 어디가 유리해 보이는지 고른 참고 구간. 투자 조언이 아니다.',
  LONG_STRADDLE: '롱 스트래들: 같은 행사가 콜과 풋을 하나씩 산다. 오르든 내리든 크게 움직이면 이익, 그대로면 손실.',
  LONG_STRANGLE: '롱 스트랭글: 주가보다 높은 콜과 낮은 풋을 하나씩 산다. 스트래들보다 싸지만 더 크게 움직여야 이익.',
  SHORT_PUT: '숏 풋: 풋을 팔고 프리미엄을 받는다. 주가가 행사가 위에 있으면 받은 돈이 이익, 크게 떨어지면 손실이 커진다.',
  BULL_CALL_SPREAD: '불 콜 스프레드: 콜을 사고 더 높은 행사가 콜을 판다. 오를 때 이익, 대신 최대 이익이 정해져 있고 비용이 싸다.',
  BEAR_PUT_SPREAD: '베어 풋 스프레드: 풋을 사고 더 낮은 행사가 풋을 판다. 내릴 때 이익, 최대 이익과 손실이 정해져 있다.',
  BULL_PUT_CREDIT_SPREAD: '불 풋 크레딧 스프레드: 풋을 팔고 더 낮은 풋을 사서 돈을 받는다. 주가가 안 떨어지면 받은 돈이 이익, 손실은 두 행사가 차이까지.',
  BEAR_CALL_CREDIT_SPREAD: '베어 콜 크레딧 스프레드: 콜을 팔고 더 높은 콜을 사서 돈을 받는다. 주가가 안 오르면 받은 돈이 이익, 손실은 두 행사가 차이까지.',
};

/** JS가 그리는 화면에서 쓰는 ? 아이콘 마크업. */
const term = (key) => `<span class="term-help" data-term="${key}" tabindex="0">?</span>`;

// 툴팁 하나를 화면에 고정(position: fixed)해 두고 ? 위치에 맞춰 옮긴다.
// 표의 가로 스크롤 영역에 잘리지 않게 하려고 ::after 대신 body에 붙인다.
(() => {
  let tip = null;

  function show(el) {
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'term-tip';
      tip.setAttribute('role', 'tooltip');
      document.body.appendChild(tip);
    }
    tip.textContent = GLOSSARY[el.dataset.term] || el.dataset.term;
    tip.hidden = false;
    const r = el.getBoundingClientRect();
    const t = tip.getBoundingClientRect();
    const left = Math.max(8, Math.min(r.left + r.width / 2 - t.width / 2, window.innerWidth - t.width - 8));
    const below = r.bottom + 6 + t.height <= window.innerHeight;
    tip.style.left = left + 'px';
    tip.style.top = (below ? r.bottom + 6 : r.top - t.height - 6) + 'px';
  }

  function hide() {
    if (tip) tip.hidden = true;
  }

  const target = (e) => (e.target instanceof Element ? e.target.closest('.term-help') : null);

  document.addEventListener('mouseover', (e) => { const el = target(e); if (el) show(el); });
  document.addEventListener('mouseout', (e) => { if (target(e)) hide(); });
  document.addEventListener('focusin', (e) => { const el = target(e); if (el) show(el); });
  document.addEventListener('focusout', (e) => { if (target(e)) hide(); });
  // 캡처 단계에서 받아, ?를 눌렀을 때 바깥 카드 선택·label 포커스가 같이 일어나지 않게 막는다.
  document.addEventListener('click', (e) => {
    const el = target(e);
    if (!el) { hide(); return; }
    e.preventDefault();
    e.stopPropagation();
    show(el);
  }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hide(); });
  window.addEventListener('scroll', hide, true);
})();
