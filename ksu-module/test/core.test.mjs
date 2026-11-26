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

test('Auto-restore config & status parsing round-trips cleanly', () => {
  const cfgText = C.serializeAutoRestoreConfig({ enabled: true, targetSet: 6 });
  assert.equal(cfgText, 'enabled=1\ntarget_set=6\n');
  const parsed = C.parseAutoRestoreConfig(cfgText);
  assert.equal(parsed.enabled, true);
  assert.equal(parsed.targetSet, 6);
  assert.deepEqual(C.parseAutoRestoreConfig(''), { enabled: false, targetSet: null });

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
