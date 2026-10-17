'use strict';
// 다운로드 엔진: SHA1 검증, 재시도, 동시 다운로드, 이미 받은 파일 건너뛰기
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const UA = 'damoon-client/1.0.0 (github.com/damoon/damoon-client)';

async function fetchRetry(url, opts = {}, tries = 4) {
  let lastErr;
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url, { ...opts, headers: { 'User-Agent': UA, ...(opts.headers || {}) } });
      if (res.status === 429 || res.status >= 500) throw new Error(`HTTP ${res.status} ${url}`);
      return res;
    } catch (e) {
      lastErr = e;
      await new Promise(r => setTimeout(r, 500 * 2 ** i));
    }
  }
  throw lastErr;
}

async function getJson(url, opts) {
  const res = await fetchRetry(url, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
  return res.json();
}

async function getText(url, opts) {
  const res = await fetchRetry(url, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
  return res.text();
}

// JSON 캐시 (매니페스트 등). ttl 내면 디스크 캐시 사용, 네트워크 실패 시 오래된 캐시라도 사용
async function getJsonCached(url, cacheFile, ttlMs = 10 * 60 * 1000) {
  try {
    const st = fs.statSync(cacheFile);
    if (Date.now() - st.mtimeMs < ttlMs) return JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
  } catch { /* 캐시 없음 */ }
  try {
    const data = await getJson(url);
    fs.mkdirSync(path.dirname(cacheFile), { recursive: true });
    fs.writeFileSync(cacheFile, JSON.stringify(data));
    return data;
  } catch (e) {
    if (fs.existsSync(cacheFile)) return JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
    throw e;
  }
}

function sha1File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha1');
    fs.createReadStream(file).on('data', d => h.update(d)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
  });
}

// quick=true: 크기만 비교 (이미 검증 완료된 설치에서 실행 속도 향상)
async function isValid(file, sha1, size, quick = false) {
  try {
    const st = fs.statSync(file);
    if (size && st.size !== size) return false;
    if (quick) return st.size > 0;
    if (sha1) return (await sha1File(file)) === sha1.toLowerCase();
    return st.size > 0;
  } catch { return false; }
}

// 단일 파일 다운로드 (urls 배열이면 순서대로 시도)
async function download(urls, dest, { sha1, size, quick } = {}) {
  if (await isValid(dest, sha1, size, quick)) return false;
  const list = Array.isArray(urls) ? urls.filter(Boolean) : [urls];
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  let lastErr;
  for (const url of list) {
    try {
      const res = await fetchRetry(url);
      if (!res.ok) { lastErr = new Error(`HTTP ${res.status} ${url}`); continue; }
      const buf = Buffer.from(await res.arrayBuffer());
      if (sha1) {
        const got = crypto.createHash('sha1').update(buf).digest('hex');
        if (got !== sha1.toLowerCase()) { lastErr = new Error(`SHA1 불일치 ${url}`); continue; }
      }
      const tmp = dest + '.part';
      fs.writeFileSync(tmp, buf);
      fs.renameSync(tmp, dest);
      return true;
    } catch (e) { lastErr = e; }
  }
  throw lastErr || new Error(`다운로드 실패: ${dest}`);
}

// 작업 목록을 동시에 처리 (tasks: [{urls, dest, sha1, size, optional}])
async function downloadAll(tasks, { concurrency = 16, onProgress, label = '', quick = false } = {}) {
  let done = 0, i = 0;
  const failures = [];
  const total = tasks.length;
  async function worker() {
    while (i < total) {
      const t = tasks[i++];
      try { await download(t.urls, t.dest, { ...t, quick }); }
      catch (e) { if (!t.optional) failures.push(`${path.basename(t.dest)}: ${e.message}`); }
      done++;
      onProgress && onProgress({ label, done, total });
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, total || 1) }, worker));
  if (failures.length) throw new Error(`${failures.length}개 파일 다운로드 실패\n` + failures.slice(0, 10).join('\n'));
}

module.exports = { UA, fetchRetry, getJson, getText, getJsonCached, download, downloadAll, sha1File, isValid };
