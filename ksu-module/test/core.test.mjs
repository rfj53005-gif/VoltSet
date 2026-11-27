import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const C = require(path.resolve(__dirname, '../webroot/core.js'));

test('QFPROM aging fuse parsing matches hardware SM8850 bit layout', () => {
  const real = C.parseQfpromAging('daa822e1,21006ecb,0000001c');
  assert.ok(real);
  assert.equal(real.clus0FusedDelta, -2);
  assert.equal(real.clus1FusedDelta, 8);
  assert.equal(real.formattedHex, '0xdaa822e1 / 0x21006ecb / 0x0000001c');

  const signedTest = C.parseQfpromAging('00005000,0c000000,00000002');
  assert.equal(signedTest.clus0FusedDelta, 5);
  assert.equal(signedTest.clus1FusedDelta, -3);
  assert.equal(C.parseQfpromAging('none'), null);
});

test('SocInfo and CPRH drift assessment match Kotlin core rules', () => {
  const soc = C.parseSocInfo('chip=SM8850 foundry=1 feature=3 pcode=2 oem=0 pmic_rev=2.0');
  assert.equal(soc.chipId, 'SM8850');
  assert.equal(soc.foundryId, '1');
  assert.equal(soc.pcode, '2');
  assert.equal(soc.pmicRev, '2.0');

  const driftRows = [
    { domain: 0, freqKhz: 883200, currentMv: 628, baselineMv: 624, deltaMv: 4, expectedCdbCxMv: 0 },
    { domain: 1, freqKhz: 2496000, currentMv: 820, baselineMv: 824, deltaMv: -4, expectedCdbCxMv: 0 },
  ];
  const d1 = C.assessCprhDrift(1, driftRows);
  assert.equal(d1.isLikelyCprhDrift, true);
  assert.equal(d1.affectedBins, 2);
  assert.equal(d1.minDeltaMv, -4);
  assert.equal(d1.maxDeltaMv, 4);

  const d6 = C.assessCprhDrift(6, driftRows);
  assert.equal(d6.isLikelyCprhDrift, false);
});

test('QPT sample parsing, energy delta, wrap guard, power_uw fallback, and CSV round-trip', () => {
  const s1 = C.parseQptSample(
    [
      'QPT_RAIL=cpu-m:500000000',
      'QPT_RAIL=gpu:100000000',
      'QPT_RAIL=debug-0:9999',
      'QPT_POWER_UW=cpu-m:100000',
      'FREQ_CPU0_KHZ=1670400',
    ].join('\n'),
    1000000000
  );
  const s2 = C.parseQptSample(
    [
      'QPT_RAIL=cpu-m:500250000',
      'QPT_RAIL=gpu:100000000',
      'QPT_POWER_UW=cpu-m:999000',
      'QPT_POWER_UW=gpu:42000',
      'FREQ_CPU0_KHZ=1228800',
    ].join('\n'),
    2000000000
  );
  assert.equal(Object.hasOwn(s1.energyUjByRail, 'debug-0'), false);

  const delta = C.computeQptDelta(s1, s2);
  assert.ok(delta);
  assert.ok(Math.abs(delta.cpuMidMw - 250) < 0.01);
  assert.ok(Math.abs(delta.gpuMw - 42) < 0.01);

  const stats = C.summarizeQptSession([
    { cpuMidMw: 400, cpuLargeMw: 600, cpuTotalMw: 1000, gpuMw: 200, nspMw: 0, dtMs: 1000 },
    { cpuMidMw: 700, cpuLargeMw: 900, cpuTotalMw: 1600, gpuMw: 400, nspMw: 0, dtMs: 2000 },
  ]);
  assert.equal(stats.sampleCount, 2);
  assert.ok(Math.abs(stats.avgCpuTotalMw - 1400) < 0.01);

  const rec = C.buildSavedPowerRecord(6, stats, 1700000000000);
  const restored = C.deserializePowerRecords(C.serializePowerRecords({ 6: rec }));
  assert.equal(restored[6].avgCpuTotalMw, 1400);
});

test('SCMI quality solver and BootSetStatus match Kotlin semantics', () => {
  const boot = C.computeBootSetStatus({ firstSdamIndex: 6, previousTargetIndex: 6, stagedThisBootIndex: 6 });
  assert.equal(boot.runningIndex, 6);
  assert.equal(boot.pendingIndex(6), null);
  assert.equal(boot.pendingIndex(7), 7);
});

test('SCMI comparison uses the level index as well as the frequency', () => {
  const row = { domain: 0, freqKhz: 384000, levelIndex: 4, voltageMv: 488 };
  const set = {
    predictedCxOffsetForBin(domain, freqKhz, levelIndex) {
      assert.deepEqual([domain, freqKhz, levelIndex], [0, 384000, 4]);
      return -15;
    },
  };
  assert.equal(C.diffScmi([row], null, set)[0].expectedCdbCxMv, -15);
});

test('Auto-restore reads fingerprinted configurations and retains the target of legacy ones', () => {
  const cfgText = 'enabled=1\ntarget_set=6\n';
  const hash = 'ab'.repeat(32);
  assert.deepEqual(C.parseAutoRestoreConfig(cfgText + `pdp_cdb_sha256=${hash}\n`), {
    enabled: true, targetSet: 6, pdpCdbSha256: hash,
  });
  assert.deepEqual(C.parseAutoRestoreConfig(cfgText), { enabled: true, targetSet: 6, pdpCdbSha256: null });
  assert.deepEqual(C.parseAutoRestoreConfig(''), { enabled: false, targetSet: null, pdpCdbSha256: null });

  const st = C.parseAutoRestoreStatus(
    [
      'boot_id=1f44d065-7924-430c-95d1-e1ae9c464720',
      'time=2026-11-26 20:25:00',
      'action=refreshed',
      'target_set=6',
      'observed_set=6',
      'detail=本次开机已重新写入并回读确认 Set 6',
    ].join('\n')
  );
  assert.equal(st.action, 'refreshed');
  assert.equal(st.targetSet, 6);
  assert.equal(st.observedSet, 6);
  assert.equal(st.detail, '本次开机已重新写入并回读确认 Set 6');
});

test('JS core matches shared golden status-golden.txt / expected-contract.json', async () => {
  const fixturesDir = new URL('../../shared-fixtures/', import.meta.url);
  const rawStatus = fs.readFileSync(new URL('status-golden.txt', fixturesDir), 'utf8');
  const contract = JSON.parse(fs.readFileSync(new URL('expected-contract.json', fixturesDir), 'utf8'));
  const snap = await C.buildDeviceSnapshot(rawStatus);

  assert.equal(snap.pdpCdbSha256, contract.pdpCdbSha256);
  assert.equal(snap.bootId, contract.bootId);
  assert.equal(snap.socModel, contract.socModel);
  assert.equal(snap.deviceModel, contract.deviceModel);
  assert.equal(snap.pdpCdb.overrideSetId, contract.overrideSetId);
  assert.equal(snap.bootSet.runningIndex, contract.activeSetId);
  assert.equal(snap.cdtStatus.state, contract.cdtState);
  assert.equal(snap.cdtStatus.version, contract.cdtVersion);
  assert.equal(snap.cdtStatus.key7Enabled, contract.cdtKey7Enabled);
  assert.equal(snap.compatibility.level, contract.compatibilityLevel);
  assert.equal(snap.compatibility.passedCount, contract.passedCount);
  assert.equal(snap.compatibility.totalCount, contract.totalCount);
  assert.equal(snap.pdpHardwareLimits.totalDcvsTablesFound, contract.totalDcvsTablesFound);
  assert.equal(snap.pdpHardwareLimits.clus0.limitsByFreqKhz.size, contract.clus0FreqCount);
  assert.equal(snap.pdpHardwareLimits.clus1.limitsByFreqKhz.size, contract.clus1FreqCount);
  assert.equal(snap.scmiPoints.length, contract.scmiPointsCount);
  assert.equal(snap.baselinePoints.length, contract.baselinePointsCount);
  assert.equal(snap.qualityReport.score, contract.qualityScore);
  assert.equal(snap.systemProbe.bootReason, contract.bootReason);
  assert.equal(snap.systemProbe.pstoreCrash, contract.pstoreCrash);
  assert.equal(snap.systemProbe.isAbnormalBoot, contract.isAbnormalBoot);
  assert.equal(snap.systemProbe.hasPstoreCrash, contract.hasPstoreCrash);
  assert.equal(snap.systemProbe.qptRails.length, contract.qptRailsCount);

  assert.equal(snap.pdpCdb.sets.length, contract.sets.length);
  for (const expected of contract.sets) {
    const profile = snap.pdpCdb.sets.find((s) => s.setId === expected.setId);
    const est = snap.setEstimates[expected.setId];
    assert.ok(profile && est);
    assert.equal(profile.tag, expected.tag);
    assert.equal(expected.setId === snap.bootSet.runningIndex, expected.isActive);
    assert.equal(est.clus0.minDeltaMv, expected.clus0MinDeltaMv);
    assert.equal(est.clus0.maxDeltaMv, expected.clus0MaxDeltaMv);
    assert.equal(est.clus1.minDeltaMv, expected.clus1MinDeltaMv);
    assert.equal(est.clus1.maxDeltaMv, expected.clus1MaxDeltaMv);
  }

  const report = C.formatTextReport(snap, 'v0.6.0');
  assert.match(report, /\[pdp 硬件限位与本机预计范围\]/);
  assert.match(report, /启动原因 \(boot_reason\): reboot,userrequested/);
  assert.match(report, /内核崩溃记录 \(pstore\): none/);
});

test('QPT parses TEMP_CPU_MC, serializes 12-col CSV, and stays compatible with 9-col CSV', () => {
  const s1 = C.parseQptStreamFrame('QPT_SAMPLE_NS=1000000000\tQPT_RAIL=cpu-m:100000\tTEMP_CPU_MC=41500');
  const s2 = C.parseQptStreamFrame(
    'QPT_SAMPLE_NS=2000000000\tQPT_RAIL=cpu-m:500000\tFREQ_CPU0_KHZ=1800000\tFREQ_CPU1_KHZ=2400000\tTEMP_CPU_MC=43250',
  );
  assert.equal(s1.cpuTempC, 41.5);
  assert.equal(s2.cpuTempC, 43.25);

  const delta = C.computeQptDelta(s1, s2);
  assert.equal(delta.cpuTempC, 43.25);
  const stats = C.summarizeQptSession([
    delta,
    { cpuMidMw: 500, cpuLargeMw: 700, cpuTotalMw: 1200, gpuMw: 100, nspMw: 0, cpu0FreqKhz: 2000000, cpu1FreqKhz: 2800000, cpuTempC: 45.25, dtMs: 1000 },
  ]);
  assert.equal(stats.avgCpu0Mhz, 1900);
  assert.equal(stats.avgCpu1Mhz, 2600);
  assert.ok(Math.abs(stats.avgCpuTempC - 44.25) < 0.01);

  const rec = C.buildSavedPowerRecord(3, stats, 1700000000000);
  const csv = C.serializePowerRecords({ 3: rec });
  assert.equal(csv.trim().split(',').length, 12);
  const restored = C.deserializePowerRecords(csv);
  assert.equal(restored[3].avgCpu0Mhz, 1900);
  assert.equal(restored[3].avgCpu1Mhz, 2600);
  assert.ok(Math.abs(restored[3].avgCpuTempC - 44.3) < 0.01);

  const legacy = C.deserializePowerRecords('1,3,3,500,700,1200,200,0,1700000000000\n');
  assert.equal(legacy[1].avgCpuTotalMw, 1200);
  assert.equal(legacy[1].avgCpu0Mhz, 0);
  assert.equal(legacy[1].avgCpu1Mhz, 0);
  assert.equal(legacy[1].avgCpuTempC, null);
});

test('SystemProbe identifies abnormal boot_reason and pstore_crash', () => {
  const normal = C.parseSystemProbe('boot_reason=reboot,userrequested\npstore_crash=none\n');
  assert.equal(normal.isAbnormalBoot, false);
  assert.equal(normal.hasPstoreCrash, false);

  const panic = C.parseSystemProbe('boot_reason=kernel_panic\npstore_crash=console-ramoops-0\n');
  assert.equal(panic.isAbnormalBoot, true);
  assert.equal(panic.hasPstoreCrash, true);
});

const liveFile = process.env.VOLTSET_LIVE_STATUS_FILE;
test('End-to-end live status snapshot parity (when VOLTSET_LIVE_STATUS_FILE is provided)', { skip: !liveFile }, async () => {
  const raw = fs.readFileSync(liveFile, 'utf8');
  const snap = await C.buildDeviceSnapshot(raw);
  assert.equal(snap.socModel, 'SM8850');
  assert.equal(snap.isSm8850, true);
  assert.ok(snap.pdpCdb && snap.pdpCdb.sets.length >= 7);
  assert.ok(snap.pdpHardwareLimits && snap.pdpHardwareLimits.isComplete);
  assert.ok(snap.scmiPoints.length >= 50);
  assert.ok(snap.qualityReport && snap.qualityReport.score > 1000);
  assert.ok(snap.systemProbe.qfpromAging);
  assert.equal(snap.systemProbe.qfpromAging.clus0FusedDelta, -2);
  assert.equal(snap.systemProbe.qfpromAging.clus1FusedDelta, 8);
  assert.deepEqual(snap.systemProbe.qptRails, ['cpu-m', 'cpu-l', 'gpu', 'nsp']);
  assert.equal(snap.compatibility.level, 'COMPATIBLE');
  assert.equal(snap.compatibility.passedCount, snap.compatibility.totalCount);
});
