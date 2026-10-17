'use strict';
// 오프라인 단위 테스트: node test/core.test.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

process.env.DAMOON_HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'damoon-test-'));
const { P, mavenPath } = require('../src/core/paths');
const { allowed } = require('../src/core/rules');
const { compareMc, mergeVersion } = require('../src/core/versions');
const { planLibraries } = require('../src/core/install');
const { buildArgs } = require('../src/core/launch');
const { neoPrefix } = require('../src/core/loaders/forge');
const { jvmFlags, presetFor } = require('../src/core/optimize');
const instances = require('../src/core/instances');
const mods = require('../src/core/mods');

let n = 0;
const t = (name, fn) => { fn(); n++; console.log('✓', name); };

t('maven 경로', () => {
  assert.strictEqual(mavenPath('net.fabricmc:fabric-loader:0.16.9'), 'net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar');
  assert.strictEqual(mavenPath('org.lwjgl:lwjgl:3.3.3:natives-windows'), 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar');
  assert.strictEqual(mavenPath('de.oceanlabs.mcp:mcp_config:1.20.1:mappings@txt'), 'de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1-mappings.txt');
});

t('버전 비교 (1.8.9 ~ 26.3)', () => {
  const sorted = ['26.3', '1.21.11', '1.8.9', '26.1.2', '1.20.1', '26.1', '1.12.2'].sort(compareMc);
  assert.deepStrictEqual(sorted, ['1.8.9', '1.12.2', '1.20.1', '1.21.11', '26.1', '26.1.2', '26.3']);
});

t('NeoForge 버전 접두사', () => {
  assert.strictEqual(neoPrefix('1.21.1'), '21.1.');
  assert.strictEqual(neoPrefix('1.21'), '21.0.');
  assert.strictEqual(neoPrefix('1.20.2'), '20.2.');
  assert.strictEqual(neoPrefix('1.20.1'), null);
  assert.strictEqual(neoPrefix('26.1'), '26.1.0.');
  assert.strictEqual(neoPrefix('26.1.2'), '26.1.2.');
  assert.strictEqual(neoPrefix('26.3'), '26.3.0.');
  assert.strictEqual(neoPrefix('1.8.9'), null);
});

t('규칙 평가', () => {
  const me = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'osx' : 'linux';
  assert.strictEqual(allowed(undefined), true);
  assert.strictEqual(allowed([{ action: 'allow' }, { action: 'disallow', os: { name: me } }]), false);
  assert.strictEqual(allowed([{ action: 'allow', os: { name: me } }]), true);
  assert.strictEqual(allowed([{ action: 'allow', features: { is_demo_user: true } }], {}), false);
  assert.strictEqual(allowed([{ action: 'allow', features: { has_custom_resolution: true } }], { has_custom_resolution: true }), true);
});

// 1.8.9 스타일 (minecraftArguments + 구형 natives)
const v189 = {
  id: '1.8.9', mainClass: 'net.minecraft.client.main.Main', assets: '1.8', assetIndex: { id: '1.8' },
  minecraftArguments: '--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} --userType ${user_type}',
  javaVersion: { component: 'jre-legacy', majorVersion: 8 },
  libraries: [
    { name: 'com.mojang:netty:1.6', downloads: { artifact: { path: 'com/mojang/netty/1.6/netty-1.6.jar', url: 'https://x/netty.jar', sha1: 'a' } } },
    { name: 'org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209', natives: { linux: 'natives-linux', windows: 'natives-windows', osx: 'natives-osx' },
      extract: { exclude: ['META-INF/'] },
      downloads: { classifiers: {
        'natives-linux': { path: 'n/linux.jar', url: 'https://x/l.jar' }, 'natives-windows': { path: 'n/win.jar', url: 'https://x/w.jar' }, 'natives-osx': { path: 'n/osx.jar', url: 'https://x/o.jar' } } } },
  ],
};

// Legacy Forge 1.8.9 프로필
const forge189 = {
  id: '1.8.9-forge1.8.9-11.15.1.2318-1.8.9', inheritsFrom: '1.8.9', jar: '1.8.9', mainClass: 'net.minecraft.launchwrapper.Launch',
  minecraftArguments: v189.minecraftArguments + ' --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker',
  libraries: [
    { name: 'net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9', url: 'https://maven.minecraftforge.net/' },
    { name: 'net.minecraft:launchwrapper:1.12' },
    { name: 'com.typesafe.akka:akka-actor_2.11:2.3.3', url: 'https://maven.minecraftforge.net/', clientreq: true, serverreq: true },
    { name: 'net.sf.jopt-simple:jopt-simple:4.6', serverreq: true, clientreq: false },
  ],
};

t('Forge 1.8.9 상속 병합 + 라이브러리 계획', () => {
  const m = mergeVersion(forge189, { ...v189, clientJarId: '1.8.9', baseVersion: '1.8.9' });
  assert.strictEqual(m.mainClass, 'net.minecraft.launchwrapper.Launch');
  assert.strictEqual(m.clientJarId, '1.8.9');
  assert.ok(m.minecraftArguments.includes('FMLTweaker'));
  const plan = planLibraries(m);
  const names = plan.classpath.map(p => path.basename(p));
  assert.ok(names.includes('forge-1.8.9-11.15.1.2318-1.8.9.jar'));
  assert.ok(names.includes('launchwrapper-1.12.jar'));
  assert.ok(!names.includes('jopt-simple-4.6.jar'), '서버 전용 라이브러리 제외');
  assert.strictEqual(plan.natives.length, 1, '현재 OS 네이티브 1개');
  const lw = plan.tasks.find(x => x.dest.endsWith('launchwrapper-1.12.jar'));
  assert.ok(lw.urls[0] === null || lw.urls.includes('https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'));
});

t('1.8.9 실행 인자', () => {
  const args = buildArgs({
    version: { ...v189, baseVersion: '1.8.9' },
    install: { classpath: ['/a.jar', '/b.jar'], nativesDir: '/n', assets: { assetsRoot: '/as', virtualDir: '/as' }, loggingArg: null },
    account: { name: 'Damoon', uuid: 'uuid1', accessToken: 'TOKEN' }, gameDir: '/g', memoryMb: 2048, server: 'mc.example.com',
  });
  assert.ok(args.includes('-Djava.library.path=/n'));
  assert.ok(args.includes('net.minecraft.client.main.Main'));
  assert.strictEqual(args[args.indexOf('--username') + 1], 'Damoon');
  assert.strictEqual(args[args.indexOf('--server') + 1], 'mc.example.com');
  assert.ok(args.includes('-XX:+UseG1GC'), 'Java 8 → G1');
  assert.ok(!args.join(' ').includes('${'), '치환 안 된 변수 없음');
});

// 26.3 스타일 (arguments 객체 + natives 분류자 라이브러리)
const v263 = {
  id: '26.3', mainClass: 'net.minecraft.client.main.Main', assetIndex: { id: '30' }, javaVersion: { component: 'java-runtime-epsilon', majorVersion: 25 },
  arguments: {
    game: ['--username', '${auth_player_name}', '--version', '${version_name}', '--accessToken', '${auth_access_token}', '--versionType', '${version_type}',
      { rules: [{ action: 'allow', features: { has_custom_resolution: true } }], value: ['--width', '${resolution_width}', '--height', '${resolution_height}'] },
      { rules: [{ action: 'allow', features: { is_demo_user: true } }], value: '--demo' },
      { rules: [{ action: 'allow', features: { is_quick_play_multiplayer: true } }], value: ['--quickPlayMultiplayer', '${quickPlayMultiplayer}'] }],
    jvm: [{ rules: [{ action: 'allow', os: { name: 'osx' } }], value: ['-XstartOnFirstThread'] },
      '-Djava.library.path=${natives_directory}', '-Dminecraft.launcher.brand=${launcher_name}', '-cp', '${classpath}'],
  },
  libraries: [
    { name: 'org.lwjgl:lwjgl:3.3.3', downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar', url: 'https://x', sha1: 'b' } } },
    { name: 'org.lwjgl:lwjgl:3.3.3:natives-linux', rules: [{ action: 'allow', os: { name: 'linux' } }], downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar', url: 'https://x', sha1: 'c' } } },
    { name: 'org.lwjgl:lwjgl:3.3.3:natives-windows', rules: [{ action: 'allow', os: { name: 'windows' } }], downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar', url: 'https://x', sha1: 'd' } } },
    { name: 'org.lwjgl:lwjgl:3.3.3:natives-macos', rules: [{ action: 'allow', os: { name: 'osx' } }], downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos.jar', url: 'https://x', sha1: 'e' } } },
  ],
};
const fabric263 = {
  id: 'fabric-loader-0.19.3-26.3', inheritsFrom: '26.3', mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
  arguments: { game: [], jvm: ['-DFabricMcEmu= net.minecraft.client.main.Main '] },
  libraries: [{ name: 'net.fabricmc:fabric-loader:0.19.3', url: 'https://maven.fabricmc.net/' }],
};

t('Fabric 26.3 병합 + 실행 인자 (Java 25 → ZGC)', () => {
  const m = mergeVersion(fabric263, { ...v263, clientJarId: '26.3', baseVersion: '26.3' });
  assert.strictEqual(m.mainClass, 'net.fabricmc.loader.impl.launch.knot.KnotClient');
  assert.strictEqual(m.baseVersion, '26.3');
  const plan = planLibraries(m);
  assert.strictEqual(plan.classpath.length, 3, 'loader + lwjgl + 현재 OS natives');
  const args = buildArgs({
    version: m, install: { classpath: [...plan.classpath, '/v.jar'], nativesDir: '/n', assets: { assetsRoot: '/as', virtualDir: '/as' }, loggingArg: '-Dlog4j.configurationFile=/x.xml' },
    account: { name: 'Damoon', uuid: 'u', accessToken: 'TOKEN' }, gameDir: '/g', memoryMb: 6144, width: 1280, height: 720, server: 'play.example.com:25566',
  });
  assert.ok(args.includes('-XX:+UseZGC'));
  assert.ok(!args.includes('-XX:+ZGenerational'), 'Java 25에서는 ZGenerational 플래그 불필요');
  assert.ok(!args.includes('--demo'));
  assert.strictEqual(args[args.indexOf('--width') + 1], '1280');
  assert.strictEqual(args[args.indexOf('--quickPlayMultiplayer') + 1], 'play.example.com:25566');
  assert.strictEqual(args.filter(a => a === '--quickPlayMultiplayer').length, 1);
  assert.strictEqual(args[args.indexOf('--versionType') + 1], 'Damoon Client');
  assert.ok(args.indexOf('-DFabricMcEmu= net.minecraft.client.main.Main ') < args.indexOf('net.fabricmc.loader.impl.launch.knot.KnotClient'));
  assert.ok(!args.join(' ').includes('${'), '치환 안 된 변수 없음');
});

t('JVM 플래그: Java 21 저메모리 → G1, 고메모리 → 세대별 ZGC', () => {
  assert.ok(jvmFlags({ javaVersion: { majorVersion: 21 } }, 2048).includes('-XX:+UseG1GC'));
  const f = jvmFlags({ javaVersion: { majorVersion: 21 } }, 8192);
  assert.ok(f.includes('-XX:+UseZGC') && f.includes('-XX:+ZGenerational'));
});

t('최적화 프리셋', () => {
  assert.ok(presetFor('fabric').includes('sodium'));
  assert.ok(!JSON.stringify(presetFor('forge')).includes('patcher'), 'Patcher는 Essential 메인 메뉴를 끌고 와서 제외');
  const core = require('../src/core/damooncore');
  assert.strictEqual(core.coreFile('forge', '1.8.9'), 'damoon-core-forge-1.8.9.jar');
  assert.strictEqual(core.coreFile('fabric', '1.21.1'), 'damoon-core-fabric-1.21.1.jar');
  assert.strictEqual(core.coreFile('fabric', '1.8.9'), null);
  assert.deepStrictEqual(presetFor('vanilla'), []);
});

t('인스턴스 생성/수정 + 모드 켜기/끄기', () => {
  const i = instances.create({ name: '1.8.9 PvP', mc: '1.8.9', loader: 'forge' });
  assert.strictEqual(i.id, '1.8.9-pvp');
  const opts = fs.readFileSync(path.join(P.instanceDir(i.id), 'options.txt'), 'utf8');
  assert.ok(opts.includes('lang:ko_KR') && opts.includes('fboEnable:true'));
  const j = instances.create({ mc: '26.3', loader: 'fabric' });
  assert.ok(fs.readFileSync(path.join(P.instanceDir(j.id), 'options.txt'), 'utf8').includes('simulationDistance:8'));
  instances.update(j.id, { versionId: 'x' });
  assert.strictEqual(instances.update(j.id, { loader: 'neoforge' }).versionId, null, '로더 변경 시 재설치');
  const gd = P.instanceDir(j.id);
  fs.mkdirSync(path.join(gd, 'mods'), { recursive: true });
  fs.writeFileSync(path.join(gd, 'mods', 'sodium.jar'), 'x');
  assert.strictEqual(mods.list(gd)[0].enabled, true);
  mods.toggle(gd, 'sodium.jar');
  assert.strictEqual(mods.list(gd)[0].enabled, false);
  mods.remove(gd, 'sodium.jar.disabled');
  assert.strictEqual(mods.list(gd).length, 0);
  assert.strictEqual(instances.list().length, 2);
});


// 공식 런처 내보내기 (네트워크 없이: 미리 받아둔 것처럼 파일을 준비)
(async () => {
  const official = require('../src/core/official');
  const off = path.join(process.env.DAMOON_HOME, 'official-mc');
  process.env.DAMOON_OFFICIAL_DIR = off;
  fs.mkdirSync(P.cache, { recursive: true });
  fs.writeFileSync(path.join(P.cache, 'version_manifest_v2.json'), JSON.stringify({ versions: [{ id: '26.3', type: 'release', releaseTime: '2026-09-15T00:00:00Z' }] }));
  for (const v of [{ ...v263 }, { ...fabric263 }]) {
    fs.mkdirSync(P.versionDir(v.id), { recursive: true });
    fs.writeFileSync(P.versionJson(v.id), JSON.stringify(v));
  }
  const lib = (rel) => { const f = path.join(P.libraries, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, 'jar'); };
  for (const l of [...v263.libraries, ...fabric263.libraries]) lib(l.downloads?.artifact?.path || mavenPath(l.name));
  const inst = instances.create({ name: 'Official Test', mc: '26.3', loader: 'fabric' });
  instances.save({ ...instances.get(inst.id), versionId: fabric263.id, optimizedOnce: true });
  fs.mkdirSync(off, { recursive: true });
  fs.writeFileSync(path.join(off, 'launcher_profiles.json'), JSON.stringify({ profiles: { keep: { name: '기존 프로필' } }, settings: { x: 1 }, version: 3 }));

  const r = await official.exportToOfficial(inst.id);
  const prof = JSON.parse(fs.readFileSync(path.join(off, 'launcher_profiles.json'), 'utf8'));
  const mine = prof.profiles['damoon-official-test'];
  assert.strictEqual(r.profileName, 'Damoon · Official Test');
  assert.ok(prof.profiles.keep && prof.settings.x === 1, '기존 프로필/설정 보존');
  assert.strictEqual(mine.lastVersionId, fabric263.id);
  assert.strictEqual(mine.gameDir, P.instanceDir(inst.id));
  assert.ok(mine.javaArgs.includes('-Xmx') && mine.icon.startsWith('data:image/png'));
  assert.ok(fs.existsSync(path.join(off, 'versions', fabric263.id, fabric263.id + '.json')), '로더 JSON 복사');
  assert.ok(!fs.existsSync(path.join(off, 'versions', '26.3')), '바닐라는 공식 런처가 직접 받음');
  assert.ok(fs.existsSync(path.join(off, 'libraries', mavenPath('net.fabricmc:fabric-loader:0.19.3'))), '라이브러리 복사');
  assert.ok(fs.existsSync(path.join(off, 'launcher_profiles.json.damoon-backup')), '백업 생성');
  n++; console.log('✓ 공식 런처로 내보내기');

  fs.rmSync(process.env.DAMOON_HOME, { recursive: true, force: true });
  console.log(`\n${n}개 테스트 통과`);
})().catch(e => { console.error(e); process.exit(1); });
