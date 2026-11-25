// 다크/라이트 모드. 모든 페이지 <head>에서 app.css 바로 뒤에 불러온다(그려지기 전에 테마를 정해 깜빡임을 막는다).
// 색 값은 app.css의 :root(다크, 기본)와 :root[data-theme="light"]에 있다. 선택은 localStorage 'theme'에 남는다.
// 테마가 바뀌면 window에 'themechange' 이벤트를 쏜다(캔버스에 색을 직접 넣는 차트가 다시 칠하도록).
(() => {
  const KEY = 'theme';
  const root = document.documentElement;
  const apply = (theme) => { root.dataset.theme = theme; };
  apply(localStorage.getItem(KEY) === 'light' ? 'light' : 'dark');

  document.addEventListener('DOMContentLoaded', () => {
    const button = document.createElement('button');
    button.className = 'theme-toggle';
    const label = () => {
      const dark = root.dataset.theme === 'dark';
      button.textContent = dark ? '☀ 라이트 모드' : '☾ 다크 모드';
      button.setAttribute('aria-pressed', String(dark));
    };
    button.addEventListener('click', () => {
      const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      apply(next);
      localStorage.setItem(KEY, next);
      label();
      window.dispatchEvent(new Event('themechange'));
    });
    label();
    document.body.appendChild(button);
  });
})();
