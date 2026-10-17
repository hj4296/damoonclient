'use strict';
// Fabric / Quilt / Legacy Fabric (모두 같은 메타 API 형식)
//   Fabric        1.14 ~ 26.x
//   Quilt         1.14.4 ~
//   Legacy Fabric 1.3 ~ 1.13.2 (1.8.9 PvP용 Fabric)
const fs = require('fs');
const { P, ensureDir } = require('../paths');
const { getJson } = require('../http');

const META = {
  fabric: 'https://meta.fabricmc.net/v2',
  'legacy-fabric': 'https://meta.legacyfabric.net/v2',
  quilt: 'https://meta.quiltmc.org/v3',
};

async function listLoaderVersions(kind, mc) {
  const list = await getJson(`${META[kind]}/versions/loader/${encodeURIComponent(mc)}`);
  return list.map(x => ({ version: x.loader.version, stable: x.loader.stable !== false && !/beta|pre/i.test(x.loader.version) }));
}

async function isSupported(kind, mc) {
  try { return (await listLoaderVersions(kind, mc)).length > 0; } catch { return false; }
}

// 프로필 JSON을 versions/<id>/<id>.json 으로 저장 (inheritsFrom: 바닐라)
async function install(kind, mc, loaderVersion) {
  if (!loaderVersion) {
    const list = await listLoaderVersions(kind, mc);
    if (!list.length) throw new Error(`${kind}는 ${mc}를 지원하지 않습니다.`);
    loaderVersion = (list.find(v => v.stable) || list[0]).version;
  }
  const profile = await getJson(`${META[kind]}/versions/loader/${encodeURIComponent(mc)}/${encodeURIComponent(loaderVersion)}/profile/json`);
  // 라이브러리의 url 필드는 저장소 루트 → install.js가 maven 경로를 붙여 다운로드
  ensureDir(P.versionDir(profile.id));
  fs.writeFileSync(P.versionJson(profile.id), JSON.stringify(profile, null, 2));
  return { versionId: profile.id, loaderVersion };
}

module.exports = { listLoaderVersions, isSupported, install };
