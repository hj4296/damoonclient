'use strict';
// Microsoft 계정 로그인 (디바이스 코드 방식) → Xbox Live → XSTS → Minecraft
// ※ 정품 계정이 있어야 실행됩니다. Azure 앱(client id) 등록이 필요합니다 (README 참고).
const fs = require('fs');
const { P } = require('./paths');

const MS = 'https://login.microsoftonline.com/consumers/oauth2/v2.0';
const SCOPE = 'XboxLive.signin offline_access';

async function post(url, body, form = false) {
  const res = await fetch(url, {
    method: 'POST',
    headers: form ? { 'Content-Type': 'application/x-www-form-urlencoded' } : { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body),
  });
  const text = await res.text();
  let json; try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { ok: res.ok, status: res.status, json };
}

// 1단계: 사용자에게 보여줄 코드 발급
async function startDeviceLogin(clientId) {
  if (!clientId) throw new Error('Microsoft Client ID가 설정되지 않았습니다. 설정 → 계정에서 입력하세요.');
  const r = await post(`${MS}/devicecode`, { client_id: clientId, scope: SCOPE }, true);
  if (!r.ok) throw new Error(r.json.error_description || '디바이스 코드 요청 실패');
  return r.json; // { user_code, device_code, verification_uri, interval, expires_in, message }
}

// 2단계: 사용자가 브라우저에서 승인할 때까지 대기
async function pollDeviceLogin(clientId, dc, isCancelled = () => false) {
  const deadline = Date.now() + dc.expires_in * 1000;
  let interval = (dc.interval || 5) * 1000;
  while (Date.now() < deadline) {
    if (isCancelled()) throw new Error('로그인이 취소되었습니다.');
    await new Promise(r => setTimeout(r, interval));
    const r = await post(`${MS}/token`, {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code', client_id: clientId, device_code: dc.device_code,
    }, true);
    if (r.ok) return r.json;
    if (r.json.error === 'authorization_pending') continue;
    if (r.json.error === 'slow_down') { interval += 5000; continue; }
    throw new Error(r.json.error_description || r.json.error || '로그인 실패');
  }
  throw new Error('코드가 만료되었습니다. 다시 시도하세요.');
}

async function refreshMs(clientId, refreshToken) {
  const r = await post(`${MS}/token`, { grant_type: 'refresh_token', client_id: clientId, refresh_token: refreshToken, scope: SCOPE }, true);
  if (!r.ok) throw new Error('세션이 만료되었습니다. 다시 로그인하세요.');
  return r.json;
}

const XSTS_ERRORS = {
  2148916233: '이 Microsoft 계정에는 Xbox 프로필이 없습니다. minecraft.net에서 먼저 로그인하세요.',
  2148916235: '이 국가/지역에서는 Xbox Live를 사용할 수 없습니다.',
  2148916236: '성인 인증이 필요한 계정입니다 (Xbox 페이지에서 확인).',
  2148916237: '성인 인증이 필요한 계정입니다 (Xbox 페이지에서 확인).',
  2148916238: '미성년자 계정입니다. 보호자가 가족 그룹에 추가해야 합니다.',
};

async function msToMinecraft(msAccessToken) {
  const xbl = await post('https://user.auth.xboxlive.com/user/authenticate', {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` },
    RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT',
  });
  if (!xbl.ok) throw new Error('Xbox Live 인증 실패');
  const uhs = xbl.json.DisplayClaims.xui[0].uhs;

  const xsts = await post('https://xsts.auth.xboxlive.com/xsts/authorize', {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.json.Token] },
    RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT',
  });
  if (!xsts.ok) throw new Error(XSTS_ERRORS[xsts.json.XErr] || 'XSTS 인증 실패');
  const xuid = xsts.json.DisplayClaims?.xui?.[0]?.xid;

  const mc = await post('https://api.minecraftservices.com/authentication/login_with_xbox', {
    identityToken: `XBL3.0 x=${uhs};${xsts.json.Token}`,
  });
  if (!mc.ok) throw new Error('Minecraft 서비스 인증 실패 (Client ID가 Minecraft API 사용 승인을 받았는지 확인)');

  const prof = await fetch('https://api.minecraftservices.com/minecraft/profile', {
    headers: { Authorization: `Bearer ${mc.json.access_token}` },
  });
  if (prof.status === 404) throw new Error('이 계정은 Minecraft: Java Edition을 보유하고 있지 않습니다.');
  if (!prof.ok) throw new Error('프로필 조회 실패');
  const p = await prof.json();
  return {
    uuid: p.id, name: p.name, accessToken: mc.json.access_token, xuid: xuid || '0',
    expiresAt: Date.now() + (mc.json.expires_in || 86400) * 1000,
    skin: p.skins?.find(s => s.state === 'ACTIVE')?.url || null,
  };
}

/* ---------- 계정 저장 (리프레시 토큰은 OS 보안 저장소로 암호화) ---------- */

function load(crypt) {
  try {
    const raw = JSON.parse(fs.readFileSync(P.accounts, 'utf8'));
    for (const a of raw.accounts) if (a.refreshEnc) a.refreshToken = crypt.decrypt(a.refreshEnc);
    return raw;
  } catch { return { accounts: [], selected: null }; }
}

function save(data, crypt) {
  const out = {
    selected: data.selected,
    accounts: data.accounts.map(({ refreshToken, accessToken, ...rest }) => ({ ...rest, refreshEnc: refreshToken ? crypt.encrypt(refreshToken) : null })),
  };
  fs.mkdirSync(P.root, { recursive: true });
  fs.writeFileSync(P.accounts, JSON.stringify(out, null, 2), { mode: 0o600 });
}

// 실행 직전: 토큰이 만료됐으면 갱신
async function ensureFresh(account, clientId) {
  if (account.accessToken && account.expiresAt > Date.now() + 60_000) return account;
  const ms = await refreshMs(clientId, account.refreshToken);
  const mc = await msToMinecraft(ms.access_token);
  return { ...account, ...mc, refreshToken: ms.refresh_token || account.refreshToken };
}

module.exports = { startDeviceLogin, pollDeviceLogin, msToMinecraft, ensureFresh, load, save };
