'use strict';
/* Damoon Client – 화면 로직 */
const D = window.damoon;
const $ = (s) => document.querySelector(s);
const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  for (const k of kids) if (k != null) e.append(k);
  return e;
};
const LOADER_NAMES = { vanilla: '바닐라', fabric: 'Fabric', quilt: 'Quilt', forge: 'Forge', neoforge: 'NeoForge', 'legacy-fabric': 'Legacy Fabric' };
const headUrl = (uuid) => `https://mc-heads.net/avatar/${uuid}/32`;

const state = { instances: [], current: null, logs: {}, info: null, accounts: { accounts: [] }, search: { q: '', offset: 0 }, core: [] };
const coreFor = (mc, loader) => state.core.find(c => c.mc === mc && (!loader || c.loader === loader));

function toast(msg, err = false) {
  const t = $('#toast');
  t.textContent = msg; t.className = 'toast' + (err ? ' err' : ''); t.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => (t.hidden = true), err ? 7000 : 3000);
}
async function guard(fn) { try { return await fn(); } catch (e) { toast(e.message, true); } }
const cur = () => state.instances.find(i => i.id === state.current);

/* ---------------- 인스턴스 목록 ---------------- */
async function loadInstances(selectId) {
  state.instances = await D.instances.list();
  if (selectId) state.current = selectId;
  if (!state.instances.find(i => i.id === state.current)) state.current = state.instances[0]?.id || null;
  renderSide();
  renderMain();
}

function renderSide() {
  const ul = $('#inst-list');
  ul.replaceChildren(...state.instances.map(i => {
    const li = el('li', { className: i.id === state.current ? 'on' : '' },
      el('div', { className: 'inst-icon', textContent: i.mc.replace(/^1\./, '') }),
      el('div', { className: 'inst-meta' }, el('b', { textContent: i.name }), el('small', { textContent: `${i.mc} · ${LOADER_NAMES[i.loader]}` })),
      i.running ? el('span', { className: 'dot', title: '실행 중' }) : null);
    li.onclick = () => { state.current = i.id; renderSide(); renderMain(); };
    return li;
  }));
}

function renderMain() {
  const i = cur();
  $('#empty').hidden = !!i;
  $('#inst').hidden = !i;
  if (!i) return;
  $('#inst-name').textContent = i.name;
  const b = [el('span', { className: 'badge gold', textContent: i.mc }), el('span', { className: 'badge', textContent: LOADER_NAMES[i.loader] + (i.loaderVersion && i.loader !== 'vanilla' ? ' ' + i.loaderVersion : '') })];
  if (i.optimize) b.push(el('span', { className: 'badge', textContent: '⚡ 최적화' }));
  if (i.damoonCore !== false && coreFor(i.mc, i.loader)) b.push(el('span', { className: 'badge core', textContent: '✦ Damoon Core' }));
  $('#badges').replaceChildren(...b);
  setPlayButton(i.running ? 'running' : 'idle');
  $('#log').textContent = state.logs[i.id] || '';
  const modTabs = i.loader !== 'vanilla';
  for (const t of ['mods', 'browse']) $(`#tabs [data-tab="${t}"]`).hidden = !modTabs;
  if (state.shownFor !== i.id) { $('#results').replaceChildren(); $('#search').value = ''; state.shownFor = i.id; }
  let tab = state.tab || (modTabs ? 'mods' : 'config');
  if (!modTabs && (tab === 'mods' || tab === 'browse')) tab = 'config';
  showTab(tab);
  fillConfig(i);
}

function showTab(name) {
  state.tab = name;
  for (const btn of document.querySelectorAll('#tabs button')) btn.classList.toggle('on', btn.dataset.tab === name);
  for (const t of document.querySelectorAll('.tab')) t.hidden = t.id !== 'tab-' + name;
  if (name === 'mods') loadMods();
  if (name === 'browse' && !$('#results').children.length) doSearch(true);
  if (name === 'log') $('#tab-log').scrollTop = 1e9;
}
document.querySelectorAll('#tabs button').forEach(b => (b.onclick = () => showTab(b.dataset.tab)));

/* ---------------- 플레이 ---------------- */
function setPlayButton(s) {
  const btn = $('#btn-play');
  btn.disabled = s === 'preparing';
  btn.classList.toggle('stop', s === 'running');
  btn.textContent = s === 'running' ? '종료' : s === 'preparing' ? '준비 중…' : '플레이';
  $('#progress').hidden = s !== 'preparing';
  $('#btn-official').hidden = s !== 'idle';
}

async function play(verify = false) {
  const i = cur(); if (!i) return;
  if (i.running) return guard(() => D.kill(i.id));
  if (!state.accounts.selected) {
    // 로그인 안 했으면 공식 런처 방식으로 안내
    if (confirm('로그인된 계정이 없어요.\n\n공식 마인크래프트 런처로 플레이할까요?\n(로그인은 공식 런처 계정을 사용합니다)')) return playOfficial();
    return openLogin();
  }
  state.logs[i.id] = '';
  setPlayButton('preparing');
  await guard(() => D.play(i.id, { verify }));
}
async function playOfficial() {
  const i = cur(); if (!i || i.running) return;
  state.logs[i.id] = '';
  setPlayButton('preparing');
  const r = await guard(() => D.exportOfficial(i.id));
  if (r) { $('#official-name').textContent = r.profileName; $('#dlg-official').showModal(); }
}
$('#btn-official').onclick = playOfficial;
$('#official-close').onclick = () => $('#dlg-official').close();
$('#btn-play').onclick = () => play(false);
$('#btn-verify').onclick = () => play(true);

D.on('progress', p => {
  if (p.id && p.id !== state.current) return;
  const pct = p.total ? Math.round((p.done / p.total) * 100) : 0;
  $('#bar').style.width = pct + '%';
  $('#progress-text').textContent = `${p.label} ${p.done}/${p.total}`;
});
D.on('log', ({ id, text }) => {
  state.logs[id] = ((state.logs[id] || '') + text).slice(-200000);
  if (id === state.current) {
    const log = $('#log'); log.textContent = state.logs[id];
    const tab = $('#tab-log'); if (!tab.hidden) tab.scrollTop = tab.scrollHeight;
  }
});
D.on('game:state', async ({ id, state: s, code, error }) => {
  const inst = state.instances.find(x => x.id === id);
  if (inst) inst.running = s === 'running';
  if (id === state.current) setPlayButton(s === 'stopped' ? 'idle' : s);
  if (s === 'stopped' && code && code !== 0) toast(`게임이 오류 코드 ${code}로 종료되었습니다. 로그 탭을 확인하세요.`, true);
  if (error) toast(error, true);
  if (s !== 'preparing') await loadInstances();
});

/* ---------------- 모드 ---------------- */
async function loadMods() {
  const i = cur(); if (!i || i.loader === 'vanilla') return;
  const mods = await guard(() => D.mods.list(i.id)) || [];
  const f = $('#mod-filter').value.trim().toLowerCase();
  const shown = mods.filter(m => !f || m.title.toLowerCase().includes(f));
  $('#mod-list').replaceChildren(...(shown.length ? shown.map(m => {
    const sw = el('button', { className: 'switch' + (m.enabled ? ' on' : ''), title: m.enabled ? '끄기' : '켜기' });
    sw.onclick = () => guard(async () => { await D.mods.toggle(i.id, m.file); loadMods(); });
    const del = el('button', { className: 'icon-btn', textContent: '🗑', title: '삭제' });
    del.onclick = () => guard(async () => { if (confirm(`${m.title} 을(를) 삭제할까요?`)) { await D.mods.remove(i.id, m.file); loadMods(); } });
    return el('li', { className: m.enabled ? '' : 'off' },
      el('div', { className: 't' }, el('b', { textContent: m.title }), el('small', { textContent: m.version || m.file })), sw, del);
  }) : [el('li', {}, el('div', { className: 't' }, el('small', { textContent: '설치된 모드가 없습니다. ⚡ 최적화 팩을 설치하거나 "모드 찾기"에서 추가하세요.' })))]));
}
$('#mod-filter').oninput = loadMods;

async function busy(btn, fn) {
  const t = btn.textContent; btn.disabled = true; btn.textContent = '처리 중…';
  try { return await fn(); } finally { btn.disabled = false; btn.textContent = t; }
}
$('#btn-optimize').onclick = (e) => guard(() => busy(e.target, async () => {
  const done = await D.mods.optimize(state.current);
  toast(done.length ? `최적화 모드 ${done.length}개 설치: ${done.join(', ')}` : '이 버전/로더에 맞는 새 최적화 모드가 없습니다.');
  loadMods();
}));
$('#btn-update').onclick = (e) => guard(() => busy(e.target, async () => {
  const n = await D.mods.updateAll(state.current); toast(n ? `${n}개 모드 업데이트` : '모두 최신입니다.'); loadMods();
}));
$('#btn-addfile').onclick = () => guard(async () => { const n = await D.mods.addLocal(state.current); if (n) { toast(`${n}개 추가`); loadMods(); } });
$('#btn-modfolder').onclick = () => guard(() => D.instances.openFolder(state.current, 'mods'));

const drop = $('#drop');
['dragenter', 'dragover'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => guard(async () => {
  const files = [...e.dataTransfer.files].filter(f => f.name.endsWith('.jar'));
  if (!files.length) return;
  await D.mods.addFiles(state.current, files); toast(`${files.length}개 추가`); loadMods();
}));

/* ---------------- 모드 찾기 (Modrinth) ---------------- */
let searchTimer;
$('#search').oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => doSearch(true), 350); };
$('#btn-more').onclick = () => doSearch(false);

async function doSearch(reset) {
  const i = cur(); if (!i) return;
  if (reset) { state.search = { q: $('#search').value.trim(), offset: 0 }; $('#results').replaceChildren(); }
  $('#browse-hint').textContent = `${i.mc} · ${LOADER_NAMES[i.loader]} 호환 모드만 표시됩니다.`;
  const r = await guard(() => D.mods.search(i.id, state.search.q, state.search.offset));
  if (!r) return;
  state.search.offset += r.hits.length;
  $('#results').append(...r.hits.map(h => {
    const btn = el('button', { textContent: '설치' });
    btn.onclick = () => guard(() => busy(btn, async () => { await D.mods.install(i.id, h.slug); btn.textContent = '설치됨 ✓'; toast(`${h.title} 설치 완료`); }).then(() => { btn.disabled = true; btn.textContent = '설치됨 ✓'; }));
    const img = el('img', { alt: '' }); if (h.icon) img.src = h.icon;
    return el('li', {}, img,
      el('div', { className: 't' }, el('b', { textContent: h.title }), el('small', { textContent: `${h.description} · ⬇ ${h.downloads.toLocaleString()}` })), btn);
  }));
  $('#btn-more').hidden = state.search.offset >= r.total || !r.hits.length;
  if (!r.total) $('#browse-hint').textContent = '검색 결과가 없습니다.';
}

/* ---------------- 인스턴스 설정 ---------------- */
function fillConfig(i) {
  const f = $('#config-form');
  f.name.value = i.name;
  f.memoryMb.max = Math.max(2048, Math.floor((state.info?.totalMemMb || 8192) * 0.75 / 512) * 512);
  f.memoryMb.value = i.memoryMb;
  $('#mem-out').textContent = (i.memoryMb / 1024).toFixed(1) + ' GB';
  f.width.value = i.width || ''; f.height.value = i.height || '';
  f.server.value = i.server || ''; f.jvmArgs.value = i.jvmArgs || ''; f.javaPath.value = i.javaPath || '';
  f.optimize.checked = !!i.optimize;
  f.damoonCore.checked = i.damoonCore !== false;
  $('#core-row').hidden = !coreFor(i.mc, i.loader);
  D.instances.recommendedMemory(i.mc).then(r => { $('#mem-hint').textContent = `권장: ${(r / 1024).toFixed(1)} GB (시스템 ${(state.info.totalMemMb / 1024).toFixed(0)} GB)`; });
}
$('#config-form').memoryMb.oninput = (e) => { $('#mem-out').textContent = (e.target.value / 1024).toFixed(1) + ' GB'; };
$('#config-form').onsubmit = (e) => guard(async () => {
  e.preventDefault();
  const f = e.target;
  await D.instances.update(state.current, {
    name: f.name.value.trim() || cur().name, memoryMb: +f.memoryMb.value,
    width: +f.width.value || 0, height: +f.height.value || 0,
    server: f.server.value.trim(), jvmArgs: f.jvmArgs.value.trim(), javaPath: f.javaPath.value.trim(), optimize: f.optimize.checked, damoonCore: f.damoonCore.checked,
  });
  toast('저장했습니다.'); loadInstances();
});
$('#btn-folder').onclick = () => guard(() => D.instances.openFolder(state.current));
$('#btn-delete').onclick = () => guard(async () => {
  const i = cur();
  if (!confirm(`'${i.name}' 인스턴스를 삭제할까요?\n월드, 모드, 설정이 모두 삭제됩니다.`)) return;
  await D.instances.remove(i.id); state.current = null; loadInstances();
});

/* ---------------- 새 인스턴스 ---------------- */
const nw = { loader: 'vanilla', available: {} };
async function openNew() {
  $('#dlg-new').showModal();
  await fillVersions();
}
async function fillVersions() {
  const sel = $('#new-version');
  sel.replaceChildren(el('option', { textContent: '불러오는 중…' }));
  const list = await guard(() => D.versions($('#new-snap').checked)) || [];
  sel.replaceChildren(...list.map(v => el('option', { value: v.id, textContent: v.id + (v.type === 'snapshot' ? ' (스냅샷)' : '') })));
  await refreshLoaders();
}
async function refreshLoaders() {
  const mc = $('#new-version').value;
  const box = $('#new-loaders');
  box.replaceChildren(el('small', { textContent: '로더 확인 중…' }));
  nw.available = await guard(() => D.loaders(mc)) || { vanilla: [{ version: mc }] };
  // Damoon Core가 있는 버전이면 그 로더를 기본 선택
  const core = coreFor(mc);
  const hint = $('#new-core-hint');
  if (core && nw.available[core.loader]?.length) {
    nw.loader = core.loader;
    hint.className = 'core-hint';
    hint.textContent = `✦ ${mc} ${LOADER_NAMES[core.loader]}: Damoon 메인 메뉴 · HUD · 줌 · 풀브라이트 자동 포함`;
  } else {
    hint.className = 'core-hint off';
    hint.textContent = `✦ Damoon Core(게임 안 기능)는 ${state.core.map(c => `${c.mc} ${LOADER_NAMES[c.loader]}`).join(', ')}에서 지원됩니다`;
  }
  const order = ['vanilla', 'fabric', 'forge', 'neoforge', 'quilt', 'legacy-fabric'];
  if (!nw.available[nw.loader]?.length) nw.loader = 'vanilla';
  box.replaceChildren(...order.filter(k => k === 'vanilla' || k !== 'legacy-fabric' || nw.available[k]?.length).map(k => {
    const ok = !!nw.available[k]?.length;
    const c = el('button', { type: 'button', className: 'chip' + (k === nw.loader ? ' on' : ''), textContent: LOADER_NAMES[k], disabled: !ok, title: ok ? '' : `${mc}에서 사용할 수 없음` });
    c.onclick = () => { nw.loader = k; refreshLoaderChips(); };
    return c;
  }));
  fillLoaderVersions();
}
function refreshLoaderChips() {
  for (const c of $('#new-loaders').children) c.classList.toggle('on', c.textContent === LOADER_NAMES[nw.loader]);
  fillLoaderVersions();
}
function fillLoaderVersions() {
  const sel = $('#new-loader-ver');
  const list = nw.available[nw.loader] || [];
  sel.disabled = nw.loader === 'vanilla';
  sel.replaceChildren(...(nw.loader === 'vanilla' ? [el('option', { textContent: '-' })] :
    [el('option', { value: '', textContent: '추천 (자동)' }), ...list.map(v => el('option', { value: v.version, textContent: v.version + (v.stable ? '' : ' (베타)') }))]));
  $('#new-opt').disabled = nw.loader === 'vanilla';
}
$('#new-version').onchange = refreshLoaders;
$('#new-snap').onchange = fillVersions;
$('#btn-new').onclick = openNew;
$('#btn-new-2').onclick = openNew;
$('#new-create').onclick = (e) => guard(async () => {
  e.preventDefault();
  const mc = $('#new-version').value;
  if (!mc) return;
  const inst = await D.instances.create({
    name: $('#new-name').value.trim() || undefined, mc, loader: nw.loader,
    loaderVersion: nw.loader === 'vanilla' ? null : ($('#new-loader-ver').value || null),
    optimize: nw.loader !== 'vanilla' && $('#new-opt').checked,
  });
  $('#dlg-new').close(); $('#new-name').value = '';
  state.tab = null;
  await loadInstances(inst.id);
  toast(`'${inst.name}' 생성 완료 — 플레이를 누르면 필요한 파일을 자동으로 받습니다.`);
});

/* ---------------- 계정 ---------------- */
function renderAccount() {
  const a = state.accounts.accounts.find(x => x.uuid === state.accounts.selected);
  $('#acc-name').textContent = a ? a.name : '로그인';
  $('#acc-sub').textContent = a ? 'Microsoft 계정' : '클릭해서 로그인';
  const img = $('#acc-head');
  img.onerror = () => img.removeAttribute('src'); // 오프라인이면 빈 칸으로
  if (a) img.src = headUrl(a.uuid); else img.removeAttribute('src');
  $('#acc-list').replaceChildren(...state.accounts.accounts.map(x => {
    const use = el('button', { textContent: x.uuid === state.accounts.selected ? '사용 중' : '사용', disabled: x.uuid === state.accounts.selected });
    use.onclick = () => guard(async () => { state.accounts = await D.accounts.select(x.uuid); renderAccount(); });
    const rm = el('button', { className: 'danger', textContent: '로그아웃' });
    rm.onclick = () => guard(async () => { state.accounts = await D.accounts.remove(x.uuid); renderAccount(); });
    return el('div', { className: 'acc-row' }, el('img', { src: headUrl(x.uuid), alt: '' }), el('span', { textContent: x.name }), use, rm);
  }));
}
function loginIdle() {
  const btn = el('button', { className: 'primary', textContent: state.accounts.accounts.length ? '다른 계정 추가' : 'Microsoft로 로그인' });
  btn.onclick = startLogin;
  $('#login-body').replaceChildren(btn);
}
function openLogin() { renderAccount(); loginIdle(); $('#dlg-login').showModal(); }
async function startLogin() {
  $('#login-body').replaceChildren(el('p', { textContent: '코드를 받는 중…' }));
  try {
    state.accounts = await D.accounts.login();
    renderAccount(); loginIdle(); toast('로그인 완료!');
  } catch (e) { loginIdle(); toast(e.message, true); }
}
D.on('login:code', ({ code, url }) => {
  const open = el('button', { className: 'primary', textContent: '브라우저에서 열기' });
  open.onclick = () => D.open(url);
  $('#login-body').replaceChildren(
    el('p', { textContent: `${url} 에서 아래 코드를 입력하세요. 승인되면 자동으로 로그인됩니다.` }),
    el('div', { className: 'code', textContent: code }), open);
  D.open(url);
});
$('#btn-account').onclick = openLogin;
$('#login-close').onclick = () => { D.accounts.cancel(); $('#dlg-login').close(); };

/* ---------------- 런처 설정 ---------------- */
$('#btn-settings').onclick = async () => {
  const s = await D.settings.get();
  $('#set-client').value = s.msClientId || '';
  $('#set-hide').checked = !!s.hideOnLaunch;
  $('#set-root').textContent = `데이터 폴더: ${state.info.root}`;
  $('#dlg-settings').showModal();
};
$('#set-save').onclick = () => guard(() => D.settings.set({ msClientId: $('#set-client').value.trim(), hideOnLaunch: $('#set-hide').checked }));

/* ---------------- 시작 ---------------- */
(async () => {
  state.info = await D.info();
  state.core = await D.coreSupported().catch(() => []);
  state.accounts = await D.accounts.list();
  renderAccount();
  await loadInstances();
})();
