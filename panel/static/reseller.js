'use strict';
const $ = selector => document.querySelector(selector);
async function api(path, method='GET', body) {
  const response = await fetch('/reseller/api/' + path, {
    method,
    cache: 'no-store',
    headers: {'Content-Type':'application/json', 'X-Reseller-Request':'1'},
    ...(body ? {body: JSON.stringify(body)} : {})
  });
  if (!response.ok) {
    let detail = '';
    try { detail = (await response.json()).detail || ''; } catch (error) {}
    const messages = {
      'MAC not registered. Open SEGA PLAYER on this device first.': 'هاد MAC باقي ما تسجّلش فالنظام. فتح تطبيق SEGA PLAYER فالجهاز أولاً.',
      'Multiple devices registered with this MAC; contact admin.': 'كاين أكثر من جهاز بنفس MAC. تاصل بالمدير باش يصحح التسجيل.',
      'This MAC already has an active license.': 'هاد MAC عندو رخصة مفعّلة وسارية، ما غاديش يتخصم ليك الرصيد.',
      'Insufficient reseller credits or disabled account.': 'الرصيد ما كافيش أو حساب الموزع موقوف.'
    };
    throw Error(messages[detail] || ('تعذر تنفيذ العملية (خطأ ' + response.status + ')' + (detail ? ': ' + detail : '')));
  }
  return response.json();
}
async function load() {
  const data = await api('state');
  $('#username').textContent = data.username;
  $('#credits').textContent = String(data.credits);
  $('#count').textContent = String(data.devices.length);
  const tbody = $('#devices');
  tbody.replaceChildren();
  for (const device of data.devices) {
    const row = document.createElement('tr');
    const status = device.active ? (device.plan === 'trial' ? 'تجربة' : 'مفعّل') : 'غير مفعّل';
    for (const value of [device.label || device.id, device.mac_alias, status, device.expires_at || '—']) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    }
    tbody.append(row);
  }
  if (!data.devices.length) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 4;
    cell.textContent = 'مازال ما فعّلتي حتى جهاز.';
    row.append(cell);
    tbody.append(row);
  }
  const ledger = $('#ledger');
  ledger.replaceChildren();
  for (const operation of data.ledger) {
    const row = document.createElement('tr');
    for (const value of [operation.created_at, operation.reason, String(operation.delta)]) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    }
    ledger.append(row);
  }
  $('#status').textContent = 'الحساب جاهز';
}
$('#activate-form').addEventListener('submit', async event => {
  event.preventDefault();
  const input = $('#mac');
  const button = $('#activate-button');
  const mac = input.value.trim().toUpperCase();
  if (!mac || !confirm('تأكيد تفعيل MAC ' + mac + ' لمدة سنة مقابل وحدة رصيد واحدة؟')) return;
  button.disabled = true;
  $('#status').textContent = 'جارٍ التفعيل...';
  try {
    const result = await api('activate', 'POST', {mac});
    await load();
    input.value = '';
    $('#status').textContent = 'تم تفعيل ' + result.mac + ' بنجاح حتى ' + result.expires_at + '، الرصيد المتبقي: ' + result.credits;
  } catch (error) {
    $('#status').textContent = error.message;
  } finally {
    button.disabled = false;
  }
});
load().catch(error => { $('#status').textContent = error.message; });
