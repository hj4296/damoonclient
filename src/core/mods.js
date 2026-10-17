'use strict';
// 모드 관리: Modrinth 검색/설치(필수 의존성 자동 설치), 로컬 jar 추가, 켜기/끄기, 삭제, 업데이트
const fs = require('fs');
const path = require('path');
const { getJson, download } = require('./http');
const { presetFor } = require('./optimize');

const API = 'https://api.modrinth.com/v2';

function loaderFilter(loader) {
  if (loader === 'quilt') return ['quilt', 'fabric']; // Quilt는 대부분의 Fabric 모드 실행 가능
  return [loader];
}

async function search(query, mc, loader, offset = 0) {
  const facets = [['project_type:mod'], [`versions:${mc}`], loaderFilter(loader).map(l => `categories:${l}`)];
  const url = `${API}/search?query=${encodeURIComponent(query || '')}&limit=20&offset=${offset}` +
    `&index=${query ? 'relevance' : 'downloads'}&facets=${encodeURIComponent(JSON.stringify(facets))}`;
  const r = await getJson(url);
  return {
    total: r.total_hits,
    hits: r.hits.map(h => ({
      id: h.project_id, slug: h.slug, title: h.title, description: h.description,
      icon: h.icon_url, downloads: h.downloads, author: h.author,
    })),
  };
}

async function compatibleVersion(project, mc, loader) {
  const url = `${API}/project/${encodeURIComponent(project)}/version?loaders=${encodeURIComponent(JSON.stringify(loaderFilter(loader)))}` +
    `&game_versions=${encodeURIComponent(JSON.stringify([mc]))}`;
  let list;
  try { list = await getJson(url); } catch { return null; }
  if (!list.length) return null;
  return list.find(v => v.version_type === 'release') || list[0];
}

/* ---------- 인스턴스 모드 목록 (mods/.damoon-mods.json) ---------- */

function modsDir(gameDir) { const d = path.join(gameDir, 'mods'); fs.mkdirSync(d, { recursive: true }); return d; }
function manifestPath(gameDir) { return path.join(modsDir(gameDir), '.damoon-mods.json'); }
function readManifest(gameDir) {
  try { return JSON.parse(fs.readFileSync(manifestPath(gameDir), 'utf8')); } catch { return {}; }
}
function writeManifest(gameDir, m) { fs.writeFileSync(manifestPath(gameDir), JSON.stringify(m, null, 2)); }

// 모드 + 필수 의존성 설치
async function install(project, { gameDir, mc, loader, onLog = () => {} }, _visited = new Set()) {
  const manifest = readManifest(gameDir);
  const ver = await compatibleVersion(project, mc, loader);
  if (!ver) { onLog(`· ${project}: ${mc} ${loader}용 파일 없음 → 건너뜀`); return null; }
  if (_visited.has(ver.project_id)) return null;
  _visited.add(ver.project_id);

  const existing = manifest[ver.project_id];
  if (existing && existing.versionId === ver.id) { onLog(`· ${existing.title} 이미 설치됨`); return existing; }

  const file = ver.files.find(f => f.primary) || ver.files[0];
  const dir = modsDir(gameDir);
  if (existing) {
    for (const f of [existing.filename, existing.filename + '.disabled']) fs.rmSync(path.join(dir, f), { force: true });
  }
  await download(file.url, path.join(dir, file.filename), { sha1: file.hashes?.sha1, size: file.size });

  let title = project;
  try { title = (await getJson(`${API}/project/${ver.project_id}`)).title; } catch { /* 제목은 선택 */ }
  manifest[ver.project_id] = {
    title, slug: project, versionId: ver.id, versionNumber: ver.version_number,
    filename: file.filename, enabled: true,
  };
  writeManifest(gameDir, manifest);
  onLog(`✓ ${title} ${ver.version_number}`);

  for (const dep of ver.dependencies || []) {
    if (dep.dependency_type !== 'required' || !dep.project_id) continue;
    if (readManifest(gameDir)[dep.project_id]) continue;
    await install(dep.project_id, { gameDir, mc, loader, onLog }, _visited);
  }
  return manifest[ver.project_id];
}

// 최적화 프리셋 일괄 설치
async function installOptimizationPack({ gameDir, mc, loader, onLog = () => {} }) {
  const installed = [];
  for (const entry of presetFor(loader)) {
    const choices = Array.isArray(entry) ? entry : [entry];
    for (const slug of choices) {
      const ver = await compatibleVersion(slug, mc, loader);
      if (!ver) continue;
      const r = await install(slug, { gameDir, mc, loader, onLog });
      if (r) installed.push(r.title);
      break; // 대체 후보 중 하나만
    }
  }
  return installed;
}

function list(gameDir) {
  const dir = modsDir(gameDir);
  const manifest = readManifest(gameDir);
  const byFile = Object.fromEntries(Object.entries(manifest).map(([id, m]) => [m.filename, { id, ...m }]));
  return fs.readdirSync(dir)
    .filter(f => /\.jar(\.disabled)?$/.test(f))
    .map(f => {
      const base = f.replace(/\.disabled$/, '');
      const m = byFile[base];
      return { file: f, title: m?.title || base.replace(/\.jar$/, ''), version: m?.versionNumber || '', enabled: !f.endsWith('.disabled'), projectId: m?.id || null };
    })
    .sort((a, b) => a.title.localeCompare(b.title));
}

function toggle(gameDir, file) {
  const dir = modsDir(gameDir);
  const to = file.endsWith('.disabled') ? file.replace(/\.disabled$/, '') : file + '.disabled';
  fs.renameSync(path.join(dir, file), path.join(dir, to));
  return to;
}

function remove(gameDir, file) {
  const dir = modsDir(gameDir);
  fs.rmSync(path.join(dir, file), { force: true });
  const manifest = readManifest(gameDir);
  const base = file.replace(/\.disabled$/, '');
  for (const [id, m] of Object.entries(manifest)) if (m.filename === base) delete manifest[id];
  writeManifest(gameDir, manifest);
}

function addLocal(gameDir, srcFiles) {
  const dir = modsDir(gameDir);
  for (const f of srcFiles) if (/\.jar$/i.test(f)) fs.copyFileSync(f, path.join(dir, path.basename(f)));
}

async function updateAll({ gameDir, mc, loader, onLog = () => {} }) {
  const manifest = readManifest(gameDir);
  let updated = 0;
  for (const [id, m] of Object.entries(manifest)) {
    const ver = await compatibleVersion(id, mc, loader);
    if (ver && ver.id !== m.versionId) {
      const wasDisabled = fs.existsSync(path.join(modsDir(gameDir), m.filename + '.disabled'));
      await install(id, { gameDir, mc, loader, onLog });
      if (wasDisabled) toggle(gameDir, readManifest(gameDir)[id].filename);
      updated++;
    }
  }
  return updated;
}

module.exports = { search, compatibleVersion, install, installOptimizationPack, list, toggle, remove, addLocal, updateAll };
