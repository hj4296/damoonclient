'use strict';
// 최적화: 메모리 자동 할당, 자바 버전별 GC 튜닝, 버전/로더별 최적화 모드 프리셋, 초기 options.txt
const os = require('os');
const fs = require('fs');
const path = require('path');
const { compareMc } = require('./versions');

const COMPONENT_MAJOR = {
  'jre-legacy': 8, 'java-runtime-alpha': 16, 'java-runtime-beta': 17,
  'java-runtime-gamma': 17, 'java-runtime-gamma-snapshot': 17,
  'java-runtime-delta': 21, 'java-runtime-epsilon': 25,
};

function javaMajor(version) {
  return version.javaVersion?.majorVersion || COMPONENT_MAJOR[version.javaVersion?.component] || 8;
}

// 시스템 RAM과 모드 수에 맞춰 권장 메모리(MB)
function recommendedMemory(mcVersion, modCount = 0) {
  const totalMb = Math.floor(os.totalmem() / 1024 / 1024);
  let want = compareMc(mcVersion, '1.13') < 0 ? 2048 : 4096;
  if (modCount > 40) want += 2048;
  if (modCount > 120) want += 2048;
  // OS가 쓸 몫을 남김: 전체의 절반을 넘지 않게
  return Math.max(1024, Math.min(want, Math.floor(totalMb / 2 / 512) * 512));
}

function jvmFlags(version, memoryMb) {
  const major = javaMajor(version);
  const flags = [`-Xms${Math.min(memoryMb, 1024)}M`, `-Xmx${memoryMb}M`, '-Dlog4j2.formatMsgNoLookups=true'];
  if (major >= 21 && memoryMb >= 4096) {
    // Java 21+: 세대별 ZGC → 프레임 끊김(GC 멈춤) 최소화
    flags.push('-XX:+UseZGC');
    if (major < 23) flags.push('-XX:+ZGenerational');
    flags.push('-XX:+DisableExplicitGC');
  } else {
    // 클라이언트용 G1 튜닝 (Java 8 ~ 17 및 저메모리)
    flags.push(
      '-XX:+UnlockExperimentalVMOptions', '-XX:+UseG1GC',
      '-XX:G1NewSizePercent=20', '-XX:G1ReservePercent=20',
      '-XX:MaxGCPauseMillis=50', '-XX:G1HeapRegionSize=32M',
      '-XX:+DisableExplicitGC', '-XX:+ParallelRefProcEnabled',
    );
  }
  if (major >= 9) flags.push('-XX:+UseStringDeduplication');
  if (major === 8) flags.push('-Dfml.ignoreInvalidMinecraftCertificates=true', '-Dfml.ignorePatchDiscrepancies=true');
  return flags;
}

// Modrinth 슬러그. 배열은 "앞에서부터 첫 번째로 설치 가능한 것 하나"
// 각 모드는 해당 버전·로더용 파일이 있을 때만 설치되고, 없으면 건너뜀 → 26.x 같은 신버전도 자동 대응
const PRESETS = {
  fabric: [
    'sodium', 'lithium', 'ferrite-core', 'entityculling', 'immediatelyfast', 'modernfix',
    'moreculling', 'dynamic-fps', 'krypton', ['starlight'], ['lazydfu'],
  ],
  quilt: [
    'sodium', 'lithium', 'ferrite-core', 'entityculling', 'immediatelyfast', 'modernfix',
    'moreculling', 'dynamic-fps', 'krypton',
  ],
  neoforge: [
    ['sodium', 'embeddium'], 'ferrite-core', 'modernfix', 'entityculling', 'immediatelyfast', 'dynamic-fps',
  ],
  forge: [
    ['embeddium', 'rubidium'], 'ferrite-core', 'modernfix', 'entityculling', 'immediatelyfast', 'dynamic-fps',
    ['patcher'], // 1.8.9 Forge용
  ],
  'legacy-fabric': ['entityculling'],
  vanilla: [],
};

function presetFor(loader) { return PRESETS[loader] || []; }

// 첫 실행 시에만 options.txt 생성 (기존 설정은 건드리지 않음)
function writeDefaultOptions(gameDir, mcVersion) {
  const file = path.join(gameDir, 'options.txt');
  if (fs.existsSync(file)) return;
  const legacy = compareMc(mcVersion, '1.13') < 0;
  const lines = [
    `lang:${compareMc(mcVersion, '1.11') < 0 ? 'ko_KR' : 'ko_kr'}`,
    'renderDistance:10',
    'entityShadows:false',
  ];
  if (legacy) lines.push('fboEnable:true', 'useVbo:true', 'maxFps:260');
  else lines.push('maxFps:260');
  if (compareMc(mcVersion, '1.18') >= 0) lines.push('simulationDistance:8');
  fs.mkdirSync(gameDir, { recursive: true });
  fs.writeFileSync(file, lines.join('\n') + '\n');
}

module.exports = { javaMajor, recommendedMemory, jvmFlags, presetFor, writeDefaultOptions, PRESETS };
