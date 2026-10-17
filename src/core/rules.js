'use strict';
// Mojang 버전 JSON의 "rules" 평가 (OS / 아키텍처 / 기능 플래그)
const os = require('os');

function osName() {
  return process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'osx' : 'linux';
}

function archName() {
  // Mojang 규칙은 "x86"(32비트)만 특별 취급. arm64는 natives 분류자에서 별도 처리
  return process.arch === 'ia32' ? 'x86' : process.arch;
}

function ruleMatches(rule, features = {}) {
  if (rule.os) {
    if (rule.os.name && rule.os.name !== osName()) return false;
    if (rule.os.arch && rule.os.arch !== archName()) return false;
    if (rule.os.version) {
      try { if (!new RegExp(rule.os.version).test(os.release())) return false; } catch { return false; }
    }
  }
  if (rule.features) {
    for (const [k, v] of Object.entries(rule.features)) {
      if (!!features[k] !== v) return false;
    }
  }
  return true;
}

// rules가 없으면 허용. 있으면 마지막으로 일치한 규칙의 action을 따름
function allowed(rules, features) {
  if (!rules || !rules.length) return true;
  let result = false;
  for (const r of rules) if (ruleMatches(r, features)) result = r.action === 'allow';
  return result;
}

module.exports = { osName, archName, allowed };
