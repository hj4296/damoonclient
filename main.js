'use strict';
const { app, BrowserWindow, ipcMain, shell, dialog, safeStorage } = require('electron');
const path = require('path');
const fs = require('fs');
const { P, ensureDir } = require('./src/core/paths');
const versions = require('./src/core/versions');
const instances = require('./src/core/instances');
const mods = require('./src/core/mods');
const auth = require('./src/core/auth');
const { recommendedMemory } = require('./src/core/optimize');
const os = require('os');

let win;
const running = new Map(); // instanceId → child process
let loginCancelled = false;

/* ---------- 설정 / 계정 ---------- */
function readSettings() {
  try { return JSON.parse(fs.readFileSync(P.settings, 'utf8')); } catch { return {}; }
}
function writeSettings(s) { ensureDir(P.root); fs.writeFileSync(P.settings, JSON.stringify(s, null, 2)); }
function clientId() { return readSettings().msClientId || process.env.DAMOON_MS_CLIENT_ID || ''; }

const crypt = {
  encrypt: s => safeStorage.isEncryptionAvailable() ? 'enc:' + safeStorage.encryptString(s).toString('base64') : 'b64:' + Buffer.from(s).toString('base64'),
  decrypt: s => s.startsWith('enc:') ? safeStorage.decryptString(Buffer.from(s.slice(4), 'base64')) : Buffer.from(s.slice(4), 'base64').toString(),
};
let accounts = null;
function accountsData() { if (!accounts) accounts = auth.load(crypt); return accounts; }
function publicAccounts() {
  const d = accountsData();
  return { selected: d.selected, accounts: d.accounts.map(a => ({ uuid: a.uuid, name: a.name })) };
}

/* ---------- 창 ---------- */
function createWindow() {
  win = new BrowserWindow({
    width: 1180, height: 760, minWidth: 940, minHeight: 620,
    backgroundColor: '#0d0f14', title: 'Damoon Client',
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.loadFile(path.join(__dirname, 'src', 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' }; });
}

const send = (ch, data) => win && !win.isDestroyed() && win.webContents.send(ch, data);
const handle = (ch, fn) => ipcMain.handle(ch, async (_e, ...a) => {
  try { return { ok: true, data: await fn(...a) }; } catch (e) { return { ok: false, error: e.message }; }
});

/* ---------- IPC ---------- */
handle('app:info', () => ({ root: P.root, totalMemMb: Math.floor(os.totalmem() / 1048576), platform: process.platform, arch: process.arch }));
handle('versions:list', (snap) => versions.listVersions({ includeSnapshots: !!snap }));
handle('loaders:available', (mc) => instances.availableLoaders(mc));

handle('instances:list', () => instances.list().map(i => ({ ...i, running: running.has(i.id) })));
handle('instances:create', (o) => instances.create(o));
handle('instances:update', (id, patch) => instances.update(id, patch));
handle('instances:remove', (id) => { if (running.has(id)) throw new Error('실행 중인 인스턴스는 삭제할 수 없습니다.'); instances.remove(id); });
handle('instances:openFolder', (id, sub) => { const d = ensureDir(path.join(P.instanceDir(id), sub || '')); shell.openPath(d); });
handle('instances:recommendedMemory', (mc) => recommendedMemory(mc));

const instCtx = (id) => { const i = instances.get(id); return { gameDir: P.instanceDir(id), mc: i.mc, loader: i.loader }; };
handle('mods:list', (id) => mods.list(P.instanceDir(id)));
handle('mods:search', (id, q, offset) => { const c = instCtx(id); return mods.search(q, c.mc, c.loader, offset); });
handle('mods:install', (id, project) => mods.install(project, { ...instCtx(id), onLog: s => send('log', { id, text: s + '\n' }) }));
handle('mods:toggle', (id, file) => mods.toggle(P.instanceDir(id), file));
handle('mods:remove', (id, file) => mods.remove(P.instanceDir(id), file));
handle('mods:optimize', (id) => mods.installOptimizationPack({ ...instCtx(id), onLog: s => send('log', { id, text: s + '\n' }) }));
handle('mods:updateAll', (id) => mods.updateAll({ ...instCtx(id), onLog: s => send('log', { id, text: s + '\n' }) }));
handle('mods:addLocal', async (id) => {
  const r = await dialog.showOpenDialog(win, { title: '모드 파일 선택', properties: ['openFile', 'multiSelections'], filters: [{ name: 'Mod', extensions: ['jar'] }] });
  if (!r.canceled) mods.addLocal(P.instanceDir(id), r.filePaths);
  return r.filePaths?.length || 0;
});
handle('mods:addPaths', (id, paths) => { mods.addLocal(P.instanceDir(id), paths || []); });

handle('settings:get', () => ({ ...readSettings(), msClientId: clientId() }));
handle('settings:set', (patch) => { writeSettings({ ...readSettings(), ...patch }); });

handle('accounts:list', () => publicAccounts());
handle('accounts:select', (uuid) => { const d = accountsData(); d.selected = uuid; auth.save(d, crypt); return publicAccounts(); });
handle('accounts:remove', (uuid) => {
  const d = accountsData();
  d.accounts = d.accounts.filter(a => a.uuid !== uuid);
  if (d.selected === uuid) d.selected = d.accounts[0]?.uuid || null;
  auth.save(d, crypt); return publicAccounts();
});
handle('accounts:login', async () => {
  loginCancelled = false;
  const cid = clientId();
  const dc = await auth.startDeviceLogin(cid);
  send('login:code', { code: dc.user_code, url: dc.verification_uri });
  const ms = await auth.pollDeviceLogin(cid, dc, () => loginCancelled);
  const mc = await auth.msToMinecraft(ms.access_token);
  const d = accountsData();
  d.accounts = [...d.accounts.filter(a => a.uuid !== mc.uuid), { ...mc, refreshToken: ms.refresh_token }];
  d.selected = mc.uuid;
  auth.save(d, crypt);
  return publicAccounts();
});
handle('accounts:cancelLogin', () => { loginCancelled = true; });
handle('shell:open', (url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });

handle('game:play', async (id, opts = {}) => {
  if (running.has(id)) throw new Error('이미 실행 중입니다.');
  const d = accountsData();
  let acc = d.accounts.find(a => a.uuid === d.selected);
  if (!acc) throw new Error('먼저 Microsoft 계정으로 로그인하세요.');
  acc = await auth.ensureFresh(acc, clientId());
  d.accounts = d.accounts.map(a => a.uuid === acc.uuid ? acc : a);
  auth.save(d, crypt);

  running.set(id, null);
  send('game:state', { id, state: 'preparing' });
  try {
    const child = await instances.play(id, acc, {
      verify: !!opts.verify,
      onProgress: p => send('progress', { id, ...p }),
      onLog: text => send('log', { id, text }),
      onExit: code => { running.delete(id); send('game:state', { id, state: 'stopped', code }); },
    });
    running.set(id, child);
    send('game:state', { id, state: 'running' });
    if (readSettings().hideOnLaunch) win.minimize();
  } catch (e) {
    running.delete(id);
    send('game:state', { id, state: 'stopped', error: e.message });
    throw e;
  }
});
handle('game:kill', (id) => { const c = running.get(id); if (c) c.kill(); });

app.whenReady().then(() => {
  const custom = readSettings().dataDir;
  if (custom) P.setRoot(custom);
  ensureDir(P.root);
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
