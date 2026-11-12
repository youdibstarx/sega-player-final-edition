'use strict';
(() => {
  const key = 'sega-ui-theme';
  const root = document.documentElement;
  try { root.dataset.theme = localStorage.getItem(key) === 'blue' ? 'blue' : 'dark'; }
  catch (_) { root.dataset.theme = 'dark'; }

  function formatMac(value) {
    const hex = String(value).toUpperCase().replace(/[^0-9A-F]/g, '').slice(0, 12);
    return (hex.match(/.{1,2}/g) || []).join(':');
  }
  function attachMacFormatter(field) {
    field.addEventListener('input', () => {
      const beforeCaret = field.value.slice(0, field.selectionStart ?? field.value.length);
      const preceding = (beforeCaret.toUpperCase().match(/[0-9A-F]/g) || []).length;
      const formatted = formatMac(field.value);
      if (formatted === field.value) return;
      field.value = formatted;
      let position = 0, count = 0;
      while (position < formatted.length && count < preceding) {
        if (/[0-9A-F]/.test(formatted[position])) count++;
        position++;
      }
      field.setSelectionRange(position, position);
    });
  }
  function render() {
    document.querySelectorAll('[data-theme-toggle]').forEach(button => {
      const blue = root.dataset.theme === 'blue';
      button.setAttribute('aria-label', blue ? 'تفعيل التيم الأحمر والأسود' : 'تفعيل التيم الأزرق والأبيض');
      button.setAttribute('aria-pressed', String(blue));
      button.textContent = blue ? '🔴 التيم الأحمر' : '🔵 التيم الأزرق';
    });
  }
  document.addEventListener('DOMContentLoaded', () => {
    render();
    document.querySelectorAll('[data-mac-input]').forEach(attachMacFormatter);
    document.querySelectorAll('[data-theme-toggle]').forEach(button => button.addEventListener('click', () => {
      root.dataset.theme = root.dataset.theme === 'blue' ? 'dark' : 'blue';
      try { localStorage.setItem(key, root.dataset.theme); } catch (_) {}
      render();
    }));
  });
})();
