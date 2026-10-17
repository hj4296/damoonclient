'use strict';
// Damoon Client 데이터 폴더 구조
//   <root>/versions      버전 JSON + 클라이언트 jar (모든 인스턴스가 공유)
//   <root>/libraries     라이브러리 (공유)
//   <root>/assets        에셋 (공유)
//   <root>/runtime       자바 런타임 (자동 설치)
//   <root>/instances/<id>  인스턴스별 게임 폴더 (mods, saves, config ...)
const os = require('os');
const path = require('path');
const fs = require('fs');

function defaultRoot() {
  if (process.env.DAMOON_HOME) return process.env.DAMOON_HOME;
  switch (process.platform) {
    case 'win32': return path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), '.damoon');
    case 'darwin': return path.join(os.homedir(), 'Library', 'Application Support', 'damoon');
    default: return path.join(os.homedir(), '.damoon');
  }
}

let ROOT = defaultRoot();

const P = {
  get root() { return ROOT; },
  setRoot(r) { ROOT = r; },
  get versions() { return path.join(ROOT, 'versions'); },
  get libraries() { return path.join(ROOT, 'libraries'); },
  get assets() { return path.join(ROOT, 'assets'); },
  get runtime() { return path.join(ROOT, 'runtime'); },
  get instances() { return path.join(ROOT, 'instances'); },
  get cache() { return path.join(ROOT, 'cache'); },
  get accounts() { return path.join(ROOT, 'accounts.json'); },
  get settings() { return path.join(ROOT, 'settings.json'); },
  versionDir(id) { return path.join(this.versions, id); },
  versionJson(id) { return path.join(this.versions, id, `${id}.json`); },
  versionJar(id) { return path.join(this.versions, id, `${id}.jar`); },
  instanceDir(id) { return path.join(this.instances, id); },
};

function ensureDir(d) { fs.mkdirSync(d, { recursive: true }); return d; }

// "group:artifact:version[:classifier][@ext]" → 상대 경로
function mavenPath(name) {
  let ext = 'jar';
  let n = name;
  const at = n.indexOf('@');
  if (at !== -1) { ext = n.slice(at + 1); n = n.slice(0, at); }
  const [group, artifact, version, classifier] = n.split(':');
  const file = `${artifact}-${version}${classifier ? '-' + classifier : ''}.${ext}`;
  return [...group.split('.'), artifact, version, file].join('/');
}

module.exports = { P, ensureDir, mavenPath, defaultRoot };
