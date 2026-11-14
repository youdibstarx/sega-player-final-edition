'use strict';
// Synthetic data only. No production database, domain or credentials.
const { chromium, request } = require('playwright');
const { spawn } = require('node:child_process');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');

async function main() {
  const directory = mkdtempSync(path.join(tmpdir(), 'sega-ui-'));
  const base = 'http://127.0.0.1:8766';
  const server = spawn('python', ['-m', 'uvicorn', 'main:app', '--host', '127.0.0.1', '--port', '8766', '--no-access-log'], {
    cwd: path.resolve(__dirname, '..'),
    env: { ...process.env, DATABASE_PATH: path.join(directory, 'app.db'),
      SECURITY_DATABASE_PATH: path.join(directory, 'limits.db'),
      ADMIN_USERNAME: 'qa', ADMIN_PASSWORD: 'qa-local-only', DEVICE_PROOF_REQUIRED: 'false' },
    stdio: 'ignore'
  });
  let browser, api;
  const results = [];
  try {
    let ready = false;
    for (let n = 0; n < 80; n++) {
      try { if ((await fetch(base + '/health')).ok) { ready = true; break; } } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(ready, 'QA server failed to start');
    api = await request.newContext({ baseURL: base, extraHTTPHeaders: {
      Authorization: 'Basic ' + Buffer.from('qa:qa-local-only').toString('base64'), 'X-Panel-Request': '1' } });
    const identity = { device_id: '00000000-0000-4000-8000-000000000001',
      mac_alias: '12:23:34:45:56:67', device_key: 'AB123C' };
    for (const [url, data] of [['/api/v1/register', identity],
      ['/admin/api/resellers', { username: 'uitest', password: 'test-ui-password-1' }],
      ['/admin/api/resellers/1/credits', { amount: 3 }]]) {
      assert((await api.post(url, { data })).ok(), 'QA fixture failed: ' + url);
    }
    browser = await chromium.launch({ headless: true });
    for (const width of [390, 1366]) {
      const context = await browser.newContext({ viewport: { width, height: 900 } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      for (const route of ['/', '/portal', '/reseller/login', '/admin']) {
        assert((await page.goto(base + route)).ok());
        if (route === '/admin') {
          await page.waitForURL('**/admin/login');
          await page.locator('[name=username]').fill('qa');
          await page.locator('[name=password]').fill('qa-local-only');
          await page.locator('#login-button').click();
          await page.waitForURL('**/admin');
        }
        await page.waitForLoadState('networkidle');
        const toggle = page.locator('[data-theme-toggle]').first();
        await toggle.click();
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'blue');
        await toggle.click();
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
        if (route === '/portal') {
          const mac = page.locator('[data-mac-input]').first();
          await mac.fill('aabbccddeeff');
          await mac.dispatchEvent('input');
          assert.equal(await mac.inputValue(), 'AA:BB:CC:DD:EE:FF');
        }
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2);
        assert.equal(overflow, false, `Page overflow: ${route} at ${width}`);
        if (route === '/admin') {
          for (const tab of ['devices', 'playlists', 'resellers', 'offers', 'appearance', 'proofs']) {
            await page.locator(`[data-tab="${tab}"]`).click();
            assert(await page.locator('#tab-' + tab).isVisible());
          }
          const token = (await context.cookies()).find(cookie => cookie.name === 'sega_admin_session');
          assert(token && token.httpOnly && token.sameSite === 'Strict');
          await page.locator('#admin-logout').click();
          await page.waitForURL('**/admin/login');
          assert.equal((await context.request.get(base + '/admin/api/state')).status(), 401);
          await context.addCookies([token]);
          assert.equal((await context.request.get(base + '/admin/api/state')).status(), 401);
          await context.clearCookies();
          await page.goto(base + '/admin');
          await page.waitForURL('**/admin/login');
          results.push({ width, admin_login_logout_replay: 'passed' });
        }
        results.push({ width, route, themes: 'passed', overflow: false });
      }
      assert.deepEqual(errors, []);
      await context.close();
    }
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    await page.goto(base + '/reseller/login');
    await page.locator('[name=username]').fill('uitest');
    await page.locator('[name=password]').fill('test-ui-password-1');
    await page.locator('#login-button').click();
    await page.waitForURL('**/reseller');
    await page.locator('#mac').fill('122334455667');
    await page.locator('#mac').dispatchEvent('input');
    assert.equal(await page.locator('#mac').inputValue(), identity.mac_alias);
    page.once('dialog', dialog => dialog.accept());
    await page.locator('#activate-button').click();
    await page.waitForFunction(() => document.querySelector('#credits').textContent === '2');
    await page.locator('#logout').click();
    await page.waitForURL('**/reseller/login');
    assert.equal((await context.request.get(base + '/reseller/api/state')).status(), 401);
    results.push({ reseller_login_activation_logout: 'passed' });
    await page.goto(base + '/portal');
    await page.locator('#login [name=mac]').fill(identity.mac_alias);
    await page.locator('#login [name=key]').fill(identity.device_key);
    await page.locator('#login button').click();
    await page.locator('#account').waitFor({ state: 'visible' });
    await page.locator('#playlist [name=name]').fill('QA playlist');
    await page.locator('#playlist [name=url]').fill('https://example.org/qa.m3u');
    await page.locator('#playlist [name=pin]').fill('1234');
    await page.locator('#playlist [type=submit]').click();
    await page.locator('#playlist-list button').click();
    await page.locator('#playlist [name=current_pin]').fill('1234');
    page.once('dialog', dialog => dialog.accept());
    await page.locator('#delete').click();
    await page.waitForFunction(() => document.querySelectorAll('#playlist-list button').length === 0);
    await page.locator('#logout').click();
    assert(await page.locator('#login').isVisible());
    results.push({ portal_login_pin_playlist_delete_logout: 'passed' });
    console.log(JSON.stringify(results, null, 2));
  } finally {
    if (browser) await browser.close();
    if (api) await api.dispose();
    server.kill();
    await new Promise(resolve => server.exitCode !== null ? resolve() : server.once('exit', resolve));
    rmSync(directory, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
