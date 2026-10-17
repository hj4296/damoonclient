'use strict';
// 실행 인자 생성 + 게임 프로세스 실행
const { spawn } = require('child_process');
const { P } = require('./paths');
const { compareMc } = require('./versions');
const { allowed } = require('./rules');
const { jvmFlags } = require('./optimize');

const SEP = process.platform === 'win32' ? ';' : ':';

function substitute(str, vars) {
  return str.replace(/\$\{([a-zA-Z_]+)\}/g, (m, k) => (k in vars ? vars[k] : m));
}

// 새 형식(arguments.game/jvm)의 항목을 규칙에 따라 평탄화
function flattenArgs(list, features) {
  const out = [];
  for (const a of list || []) {
    if (typeof a === 'string') out.push(a);
    else if (allowed(a.rules, features)) out.push(...(Array.isArray(a.value) ? a.value : [a.value]));
  }
  return out;
}

function buildArgs({ version, install, account, gameDir, memoryMb, width, height, extraJvm = [], server }) {
  const features = { has_custom_resolution: !!(width && height), is_demo_user: false };
  const vars = {
    auth_player_name: account.name,
    version_name: version.id,
    game_directory: gameDir,
    assets_root: install.assets.assetsRoot,
    game_assets: install.assets.virtualDir,
    assets_index_name: version.assetIndex?.id || version.assets || 'legacy',
    auth_uuid: account.uuid,
    auth_access_token: account.accessToken,
    auth_session: `token:${account.accessToken}:${account.uuid}`,
    auth_xuid: account.xuid || '0',
    clientid: account.clientId || '',
    user_type: 'msa',
    user_properties: '{}',
    version_type: 'Damoon Client',
    natives_directory: install.nativesDir,
    launcher_name: 'damoon-client',
    launcher_version: '1.0.0',
    classpath: install.classpath.join(SEP),
    classpath_separator: SEP,
    library_directory: P.libraries,
    resolution_width: String(width || 854),
    resolution_height: String(height || 480),
  };

  const jvm = [...jvmFlags(version, memoryMb), ...extraJvm];
  if (install.loggingArg) jvm.push(install.loggingArg);
  // macOS의 -XstartOnFirstThread는 버전 JSON 규칙이 넣어줌 (LWJGL2를 쓰는 1.8.9~1.12에는 넣으면 안 됨)

  if (version.arguments?.jvm?.length) {
    jvm.push(...flattenArgs(version.arguments.jvm, features).map(a => substitute(a, vars)));
  } else {
    // 구형(1.12 이하): JVM 인자를 직접 구성
    jvm.push(`-Djava.library.path=${install.nativesDir}`, '-cp', vars.classpath);
  }
  const jvmUnique = jvm.filter((a, i) => a !== '-XstartOnFirstThread' || jvm.indexOf(a) === i);

  let game;
  if (version.arguments?.game?.length) game = flattenArgs(version.arguments.game, features).map(a => substitute(a, vars));
  else game = (version.minecraftArguments || '').split(' ').filter(Boolean).map(a => substitute(a, vars));
  if (features.has_custom_resolution && !version.arguments?.game?.length) game.push('--width', vars.resolution_width, '--height', vars.resolution_height);

  if (server) {
    const [host, port] = server.split(':');
    // 1.20+ 은 quickPlay, 이전 버전은 --server/--port
    if (compareMc(version.baseVersion || version.id, '1.20') >= 0) game.push('--quickPlayMultiplayer', `${host}:${port || 25565}`);
    else game.push('--server', host, '--port', port || '25565');
  }
  return [...jvmUnique, version.mainClass, ...game];
}

function launch(javaPath, args, gameDir, onLog = () => {}) {
  const child = spawn(javaPath, args, { cwd: gameDir, detached: false, windowsHide: false });
  child.stdout.on('data', d => onLog(d.toString()));
  child.stderr.on('data', d => onLog(d.toString()));
  return child;
}

// 로그에 토큰이 노출되지 않도록 가림
function redact(args) {
  return args.map((a, i) => (args[i - 1] === '--accessToken' || args[i - 1] === '--session' ? '********' : a));
}

module.exports = { buildArgs, launch, redact, flattenArgs, substitute, SEP };
