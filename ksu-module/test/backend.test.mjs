import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { createRequire } from 'node:module';

const C = createRequire(import.meta.url)('../webroot/core.js');
const shell = fs.readFileSync(new URL('../../app/src/main/assets/backend/voltset.sh', import.meta.url), 'utf8');
const bootScript = fs.readFileSync(new URL('../boot-completed.sh', import.meta.url), 'utf8');
const app = fs.readFileSync(new URL('../webroot/app.js', import.meta.url), 'utf8');
const BOOT = '00000000-0000-0000-0000-000000000001';
const DATA = '/data/adb/voltset';
const CONF = DATA + '/persistent/auto-restore.conf';
const STATUS = DATA + '/persistent/auto-restore.status';
const PDP_CDB = '/dev/block/by-name/pdp_cdb_a';
const CDT = '/dev/block/by-name/cdt';

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'voltset-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const at = (name) => path.join(dir, name);
  const write = (name, bytes, mode) => {
    fs.mkdirSync(path.dirname(at(name)), { recursive: true });
    fs.writeFileSync(at(name), bytes, { mode });
  };
  const stub = (name, body) => write('/bin/' + name, '#!/bin/sh\n' + body + '\n', 0o700);
  const translate = (script) => script
    .replaceAll('/system/bin/', '/bin/')
    .replace(/\/(?:data\/adb|dev\/block|sys\/|proc\/|lib\/modules)/g, (prefix) => dir + prefix)
    .replaceAll('PATH=/system/bin', 'PATH="' + at('/bin') + ':$PATH"');
  const moduleDir = '/data/adb/modules/voltset';
  const bundle = at(moduleDir + '/backend');
  const script = bundle + '/voltset.sh';
  write(moduleDir + '/backend/voltset.sh', translate(shell)
    // Regular files stand in for block devices in the fixture.
    .replaceAll('[ -b ', '[ -f '));
  write(moduleDir + '/boot-completed.sh', translate(bootScript));
  write('/proc/sys/kernel/random/boot_id', BOOT + '\n');
  write('/proc/mounts', '');
  write(PDP_CDB, 'Set table A');
  write(CDT, Buffer.alloc(1024));
  stub('id', 'echo 0');
  stub('mount', 'echo mount >> "' + at('/mount.log') + '"');
  stub('umount', 'echo umount >> "' + at('/mount.log') + '"');
  stub('insmod', 'exit 97');
  stub('rmmod', 'exit 98');
  stub('blockdev', 'wc -c < "$2"');
  stub('getprop', [
    'case "$1" in',
    '  ro.boot.slot_suffix) echo _a ;;',
    '  ro.soc.model) echo SM8850 ;;',
    '  ro.product.device|ro.product.model|ro.build.version.incremental) echo fixture ;;',
    '  ro.boot.dtb_idx) echo 2 ;;',
    '  ro.boot.bootreason) echo reboot,userrequested ;;',
    '  sys.boot.reason) echo "$BOOT_REASON" ;;',
    'esac',
  ].join('\n'));
  const run = (args, env = {}) => {
    const result = spawnSync('/bin/sh', args, {
      encoding: 'utf8',
      env: { ...process.env, PATH: at('/bin') + ':' + process.env.PATH, ...env },
      maxBuffer: 16 * 1024 * 1024,
    });
    assert.ifError(result.error);
    return result;
  };
  return {
    at, write, stub, run, translate,
    backend: (...args) => run([script, bundle, ...args]),
    boot: (args = [], env = {}) => run([at(moduleDir + '/boot-completed.sh'), ...args], env),
    status: () => C.parseAutoRestoreStatus(fs.readFileSync(at(STATUS), 'utf8')),
    fingerprint: () => createHash('sha256').update(fs.readFileSync(at(PDP_CDB))).digest('hex'),
  };
}

test('boot hook only writes to the current SDAM fixture after all checks and one read-back', (t) => {
  const f = fixture(t);
  const nvmem = '/sys/devices/0-00/sdam@7100/sdam2/nvmem';
  const data = Buffer.alloc(128);
  data[81] = 0xa9; // CPUSS 2, Global 1, both validity bits set.
  f.write(nvmem, data);
  f.write('/sys/devices/0-00/sdam@7100/uevent', 'DRIVER=qcom,spmi-sdam\nOF_FULLNAME=/pmic/sdam@7100\n');
  f.write('/sys/devices/0-00/uevent', 'OF_NAME=pmk8850\n');
  fs.mkdirSync(f.at('/sys/bus/nvmem/devices'), { recursive: true });
  fs.symlinkSync(f.at(path.dirname(nvmem)), f.at('/sys/bus/nvmem/devices/sdam2'));

  const readByte = () => fs.readFileSync(f.at(nvmem))[81];
  const quick = f.backend('quick_status');
  assert.equal(quick.status, 0, quick.stderr);
  assert.match(quick.stdout, /^SDAM_CPUSS_IDX=2$/m);
  assert.doesNotMatch(quick.stdout, /SCMI|PDP_CDB|PROBE/);

  const arm = f.backend('set_auto_restore', '1', '6', BOOT, f.fingerprint());
  assert.equal(arm.status, 0, arm.stderr);
  const dry = f.boot(['--dry-run']);
  assert.equal(dry.status, 0, dry.stderr);
  assert.match(dry.stdout, /action=dry_run_would_write/);
  assert.equal(readByte(), 0xa9);
  assert.equal(fs.existsSync(f.at(STATUS)), false);

  const applied = f.boot();
  assert.equal(applied.status, 0, applied.stderr);
  assert.equal(f.status().action, 'written');
  assert.equal(readByte(), 0xe9);
  assert.equal(fs.readFileSync(f.at(DATA + '/persistent/last-set'), 'utf8'), BOOT + ' 6\n');
  assert.equal(f.boot().status, 0);
  assert.equal(f.status().action, 'written');

  f.write('/proc/sys/kernel/random/boot_id', '00000000-0000-0000-0000-000000000002\n');
  assert.equal(f.boot().status, 0);
  assert.equal(f.status().action, 'skipped_already_target');
});

test('boot hook skips old configs, firmware changes, active CDT Key 7 and bad CDT data', (t) => {
  const f = fixture(t);
  const enabled = 'enabled=1\ntarget_set=6\n';
  const setNode = '/sys/kernel/debug/qcom_voltage_adj/set_overrides';
  f.write(setNode, 'untouched');
  f.write('/sys/kernel/debug/qcom_voltage_adj/get_overrides', 'CPUSS rails index: 2\nGlobal rails index: 1\n');
  f.write(CONF, enabled);
  const expectSkip = (action) => {
    const result = f.boot(['--dry-run']);
    assert.equal(result.status, 0, result.stderr);
    assert.ok(result.stdout.includes('action=' + action), result.stdout);
    assert.equal(fs.readFileSync(f.at(setNode), 'utf8'), 'untouched');
  };
  expectSkip('skipped_firmware');
  const hash = f.fingerprint();
  f.write(CONF, enabled + 'pdp_cdb_sha256=' + hash + '\n');
  f.write(PDP_CDB, 'Set table B');
  expectSkip('skipped_firmware');
  const stale = f.backend('set_auto_restore', '1', '6', BOOT, hash);
  assert.notEqual(stale.status, 0);
  f.write(PDP_CDB, 'Set table A');

  const cdt = Buffer.alloc(1024);
  cdt.write('CDT');
  cdt.writeUInt16LE(18, 14);
  cdt.writeUInt16LE(12, 16);
  cdt[18] = 6;
  cdt[27] = 1;
  cdt[28] = 7;
  cdt[29] = 1;
  f.write(CDT, cdt);
  expectSkip('skipped_cdt');
  cdt[29] = 0;
  f.write(CDT, cdt);
  expectSkip('dry_run_would_write');
  cdt[0] = 0;
  f.write(CDT, cdt);
  expectSkip('skipped_cdt');
  fs.unlinkSync(f.at(CDT));
  expectSkip('skipped_cdt');
});

test('an abnormal boot pauses auto-restore across later normal boots', (t) => {
  const f = fixture(t);
  f.write(CONF, 'enabled=1\ntarget_set=6\npdp_cdb_sha256=' + f.fingerprint() + '\n');
  const dry = f.boot(['--dry-run'], { BOOT_REASON: 'Kernel_Panic' });
  assert.match(dry.stdout, /action=skipped_bootreason/);
  assert.match(fs.readFileSync(f.at(CONF), 'utf8'), /^enabled=1/m);
  const skipped = f.boot([], { BOOT_REASON: 'reboot,software,watchdog' });
  assert.equal(skipped.status, 0, skipped.stderr);
  assert.equal(f.status().action, 'skipped_bootreason');
  assert.equal(fs.readFileSync(f.at(CONF), 'utf8'), 'enabled=0\ntarget_set=6\n');
  f.write('/proc/sys/kernel/random/boot_id', '00000000-0000-0000-0000-000000000002\n');
  assert.equal(f.boot().status, 0);
  assert.equal(f.status().bootId, BOOT);
});

test('the shared debugfs fallback cannot mount before locking and is cleaned on error', (t) => {
  const f = fixture(t);
  f.stub('flock', 'exit 1');
  assert.match(f.backend('status').stderr, /另一项 Root 操作/);
  assert.equal(fs.existsSync(f.at('/mount.log')), false);

  fs.unlinkSync(f.at('/bin/flock'));
  assert.match(f.backend('quick_status', 'bad_argument').stderr, /参数无效/);
  assert.equal(fs.readFileSync(f.at('/mount.log'), 'utf8'), 'mount\numount\n');
});

test('GPU info uses the active partition fallback and the namespace helper for cmd-db', (t) => {
  const f = fixture(t);
  f.write('/sys/class/block/sdb42/uevent', 'DEVNAME=sdb42\nPARTNAME=vendor_boot_a\n');
  f.write('/dev/block/sdb42', Buffer.alloc(4096));
  f.write('/data/adb/modules/voltset/backend/bin/vsetns', [
    '#!/bin/sh',
    'echo namespace >> "' + f.at('/mount.log') + '"',
    'printf "0 gfx.lvl 0 0 0050\\n" > "' + f.at('/sys/kernel/debug/cmd-db') + '"',
    'exec "$@"',
    '',
  ].join('\n'));
  fs.mkdirSync(f.at('/sys/kernel/debug'), { recursive: true });
  const info = f.backend('gpu_info', '1');
  assert.equal(info.status, 0, info.stderr);
  assert.ok(info.stdout.includes('source=' + f.at('/dev/block/sdb42') + '\n'));
  assert.match(info.stdout, /^gfx=0 gfx\.lvl 0 0 0050$/m);
  assert.equal(fs.readFileSync(f.at('/mount.log'), 'utf8'), 'namespace\n');
});

test('PDP reads keep bytes beyond 1 MiB and cap the raw fallback at 4 MiB', (t) => {
  const f = fixture(t);
  const length = 4 * 1024 * 1024;
  const firmware = Buffer.alloc(length + 4096);
  firmware[length - 1] = 177;
  f.write('/dev/block/by-name/pdp_a', firmware);
  f.stub('gzip', 'exit 1');
  const status = f.backend('status');
  assert.equal(status.status, 0, status.stderr);
  const { blocks, values } = C.parseBackendOutput(status.stdout);
  const prefix = Buffer.from(blocks.PDP_FW_B64, 'base64');
  assert.equal(prefix.length, length);
  assert.equal(prefix[length - 1], 177);
  assert.equal(values.PDP_CDB_SHA256, f.fingerprint());
});

test('QPT maps mid and large frequency values to discovered policies', (t) => {
  const f = fixture(t);
  for (const [policy, max, freq] of [[2, 1500000, 600000], [8, 2700000, 1500000], [14, 3800000, 2500000]]) {
    const cpu = '/sys/devices/system/cpu/cpufreq/policy' + policy;
    f.write(cpu + '/cpuinfo_max_freq', max + '\n');
    f.write(cpu + '/scaling_cur_freq', freq + '\n');
  }
  const sample = f.backend('power_sample');
  assert.equal(sample.status, 0, sample.stderr);
  assert.match(sample.stdout, /^FREQ_CPU0_KHZ=1500000$/m);
  assert.match(sample.stdout, /^FREQ_CPU1_KHZ=2500000$/m);
});

test('WebUI can read the first status of a new boot and cleans up a short chunk', async (t) => {
  const f = fixture(t);
  f.write(DATA + '/session/boot', 'previous boot\n');
  f.write(DATA + '/session/stale', 'previous boot data');
  let corruptRead = false;
  const window = {
    VoltSetCore: C,
    addEventListener() {},
    ksu: {
      exec(command, _options, callback) {
        const result = f.run(['-c', f.translate(command)]);
        let out = result.stdout.replaceAll(f.at(DATA + '/webui-status.'), DATA + '/webui-status.');
        if (corruptRead && command.startsWith('dd ')) out = out.slice(0, -1);
        window[callback](result.status, out, result.stderr);
      },
    },
  };
  const document = {
    getElementById: () => ({}),
    querySelectorAll: () => [],
    addEventListener() {},
  };
  vm.runInNewContext(app.replace('  refresh();\n})();', '  window.readStatus = runBackendStatusChunked;\n})();'), {
    document, window, TextDecoder, setTimeout, clearTimeout,
  });
  const first = await window.readStatus();
  assert.match(first, new RegExp('BOOT_ID=' + BOOT));
  assert.equal(fs.existsSync(f.at(DATA + '/session/stale')), false);
  const listTransfers = () => fs.readdirSync(f.at(DATA)).filter((name) => name.startsWith('webui-status.'));
  assert.deepEqual(listTransfers(), []);
  corruptRead = true;
  await assert.rejects(window.readStatus(), /分块读取状态失败/);
  assert.deepEqual(listTransfers(), []);
});

function powerPage() {
  const node = () => ({ textContent: '', classList: { toggle() {} } });
  const nodes = new Map([['refreshBtn', node()], ['modalBackdrop', node()]]);
  let redraws = 0;
  nodes.set('mainContent', {
    set innerHTML(html) {
      redraws++;
      for (const id of html.matchAll(/\bid="([^"]+)"/g)) nodes.set(id[1], node());
    },
  });
  const document = {
    getElementById: (id) => nodes.get(id),
    querySelectorAll: () => [],
    addEventListener() {},
  };
  const window = { VoltSetCore: C, addEventListener() {} };
  vm.runInNewContext(app.replace('  refresh();\n})();', [
    '  window.powerPage = { state, render, startQptMonitor, stopQptMonitor };',
    '})();',
  ].join('\n')), { document, window, setTimeout, clearTimeout, setInterval, clearInterval, performance });
  const page = window.powerPage;
  page.state.tab = 'power';
  page.state.snapshot = { bootCpussIndex: 6, systemProbe: { qptRails: ['cpu-m'] } };
  page.render();
  return { window, page, nodes, redraws: () => redraws };
}

test('WebUI uses one spawn and updates QPT readings without replacing the controls', async () => {
  const { window, page, nodes, redraws } = powerPage();
  const lease = DATA + '/qpt-web.ABC12345';
  const calls = [];
  let child;
  window.ksu = {
    exec(command, _options, cb) {
      calls.push(command);
      window[cb](0, command.includes('mktemp') ? lease : '', '');
    },
    spawn(command, _args, _options, cb) {
      calls.push(command);
      child = window[cb];
    },
  };
  const stopButton = nodes.get('qptToggleBtn');
  const saveButton = nodes.get('qptSaveBtn');
  const activeTask = page.startQptMonitor();
  await new Promise((resolve) => setImmediate(resolve));
  try {
    assert.equal(calls.length, 2);
    assert.match(calls[1], /power_stream/);
    for (let i = 1; i <= 4; i++) {
      child.stdout.emit('data', 'QPT_SAMPLE_NS=' + i * 1e9 + '\tQPT_RAIL=cpu-m:' + i * 200000);
    }
    assert.equal(redraws(), 1);
    assert.equal(nodes.get('qptToggleBtn'), stopButton);
    assert.equal(nodes.get('qptSaveBtn'), saveButton);
    assert.equal(nodes.get('qptMidPower').textContent, '200 mW');
    assert.match(nodes.get('qptSubtitle').textContent, /采样 3 次/);
    assert.equal(saveButton.disabled, false);
    assert.equal(calls.length, 2);
  } finally {
    page.stopQptMonitor();
    child?.emit('exit', 0);
    await activeTask;
  }
  assert.equal(page.state.qptMonitoring, false);
  assert.equal(stopButton.textContent, '开始监测');
  assert.equal(calls[2], "rm -f '" + lease + "'");
});

test('stopping while an older WebUI host is enabling QPT restores the previous switch state', async () => {
  const { window, page } = powerPage();
  let enableFinished;
  let disableCount = 0;
  window.ksu = {
    exec(command, _options, cb) {
      if (command.includes("'power_enable'")) enableFinished = () => window[cb](0, 'QPT_CHANGED=1', '');
      else {
        assert.match(command, /'power_disable'/);
        disableCount++;
        window[cb](0, '', '');
      }
    },
  };
  const activeTask = page.startQptMonitor();
  page.stopQptMonitor();
  enableFinished();
  await activeTask;
  assert.equal(disableCount, 1);
  assert.equal(page.state.qptMonitoring, false);
});

test('backend reads/writes 12-col power records, rejects malformed CSV, and reads auto_restore state', (t) => {
  const f = fixture(t);
  f.write(DATA + '/persistent/auto-restore.conf', 'enabled=1\ntarget_set=6\n');
  f.write(DATA + '/persistent/auto-restore.status', 'boot_id=' + BOOT + '\naction=refreshed\n');
  f.write('/sys/fs/pstore/console-ramoops-0', 'panic trace\n');
  f.stub('getprop', [
    'case "$1" in',
    '  ro.boot.slot_suffix) echo _a ;;',
    '  ro.soc.model) echo SM8850 ;;',
    '  ro.product.device|ro.product.model|ro.build.version.incremental) echo fixture ;;',
    '  ro.boot.dtb_idx) echo 2 ;;',
    '  ro.boot.bootreason|sys.boot.reason) echo "${BOOT_REASON:-reboot,userrequested}" ;;',
    'esac',
  ].join('\n'));

  const empty = f.backend('read_power_records');
  assert.equal(empty.status, 0, empty.stderr);
  assert.match(empty.stdout, /POWER_RECORDS_EMPTY=1/);

  const validCsv = '3,3,32,500,700,1200,150,0,1700000000000,1900,2600,432\n1,2,20,400,600,1000,100,0,1700000001000\n';
  const b64Valid = Buffer.from(validCsv, 'utf8').toString('base64');
  const writeOk = f.backend('write_power_records', b64Valid);
  assert.equal(writeOk.status, 0, writeOk.stderr);

  const readBack = f.backend('read_power_records');
  assert.equal(readBack.status, 0, readBack.stderr);
  assert.match(readBack.stdout, /POWER_RECORDS_BEGIN/);
  assert.match(readBack.stdout, /3,3,32,500,700,1200,150,0,1700000000000,1900,2600,432/);

  const b64Bad = Buffer.from('99,not_a_number\n', 'utf8').toString('base64');
  const writeBad = f.backend('write_power_records', b64Bad);
  assert.notEqual(writeBad.status, 0);

  const ar = f.backend('read_auto_restore');
  assert.equal(ar.status, 0, ar.stderr);
  assert.match(ar.stdout, /KSU_MODULE_INSTALLED=1/);
  assert.match(ar.stdout, /target_set=6/);
  assert.match(ar.stdout, /action=refreshed/);

  const st = f.run(
    [f.at('/data/adb/modules/voltset/backend/voltset.sh'), f.at('/data/adb/modules/voltset/backend'), 'status'],
    { BOOT_REASON: 'kernel_panic' },
  );
  assert.equal(st.status, 0, st.stderr);
  assert.match(st.stdout, /boot_reason=kernel_panic/);
  assert.match(st.stdout, /pstore_crash=console-ramoops-0/);
});

test('backend gpu_info emits dtbo_present and power_sample emits TEMP_CPU_MC', (t) => {
  const f = fixture(t);
  f.write('/dev/block/by-name/vendor_boot_a', 'vb');
  f.write('/dev/block/by-name/dtbo_a', 'dtbo');
  f.write('/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies', '1100000000 700000000\n');
  f.write('/sys/class/powercap/qpt:0:0/name', 'cpu-m\n');
  f.write('/sys/class/powercap/qpt:0:0/energy_uj', '1000000\n');
  f.write('/sys/class/thermal/thermal_zone0/type', 'cpu-0-0\n');
  f.write('/sys/class/thermal/thermal_zone0/temp', '42500\n');

  const gpuInfo = f.backend('gpu_info', '0');
  assert.equal(gpuInfo.status, 0, gpuInfo.stderr);
  assert.match(gpuInfo.stdout, /dtbo_present=1/);

  const sample = f.backend('power_sample');
  assert.equal(sample.status, 0, sample.stderr);
  assert.match(sample.stdout, /QPT_RAIL=cpu-m:1000000/);
  assert.match(sample.stdout, /TEMP_CPU_MC=42500/);
});

test('WebUI renders SVG sparkline points and exposes revertBootSetBtn on pending reboot', async () => {
  const attrs = { cpu: null, gpu: null };
  const makeNode = (id) => ({
    textContent: '',
    hidden: false,
    classList: { toggle() {} },
    setAttribute(k, v) {
      if (id === 'qptCpuPath' && k === 'points') attrs.cpu = v;
      if (id === 'qptGpuPath' && k === 'points') attrs.gpu = v;
    },
  });
  const nodes = new Map([['refreshBtn', makeNode('refreshBtn')], ['modalBackdrop', makeNode('modalBackdrop')]]);
  nodes.set('mainContent', {
    set innerHTML(html) {
      for (const m of html.matchAll(/\bid="([^"]+)"/g)) nodes.set(m[1], makeNode(m[1]));
    },
  });
  const document = { getElementById: (id) => nodes.get(id), querySelectorAll: () => [], addEventListener() {} };
  const window = { VoltSetCore: C, addEventListener() {} };
  vm.runInNewContext(app.replace('  refresh();\n})();', [
    '  window.testPage = { state, render, updateQptDisplay };',
    '})();',
  ].join('\n')), { document, window, setTimeout, clearTimeout, setInterval, clearInterval, performance });

  const page = window.testPage;
  page.state.tab = 'power';
  page.state.snapshot = { bootCpussIndex: 3, systemProbe: { qptRails: ['cpu-m', 'gpu'] } };
  page.state.qptIntervals = [
    { cpuMidMw: 300, cpuLargeMw: 500, cpuTotalMw: 800, gpuMw: 100, dtMs: 500 },
    { cpuMidMw: 400, cpuLargeMw: 600, cpuTotalMw: 1000, gpuMw: 200, cpuTempC: 42.5, dtMs: 500 },
  ];
  page.render();
  page.updateQptDisplay();
  assert.equal(nodes.get('qptSparklineWrap').hidden, false);
  assert.match(nodes.get('qptSparklinePeak').textContent, /1000 mW/);
  assert.ok(attrs.cpu && attrs.cpu.includes(' '));
  assert.ok(attrs.gpu && attrs.gpu.includes(' '));

  // Pending reboot banner renders revertBootSetBtn and targets running bootCpussIndex (3).
  page.state.tab = 'sets';
  page.state.snapshot = {
    pendingReboot: true,
    pendingSetIndex: 6,
    bootCpussIndex: 3,
    sdamCpussIndex: 6,
    activeBootSet: { setId: 3 },
    sdamTargetSet: { setId: 6 },
    compatibility: { level: 'SUPPORTED', items: [], passedCount: 8, totalCount: 8, usableUndervoltSetCount: 6, minCxOffsetMv: -64 },
    cdtStatus: { state: 'STOCK', key7Enabled: false, detail: 'stock' },
    bootSet: { state: 'PENDING', runningIndex: 3, targetIndex: 6 },
    pdpCdb: { sets: [], overrideSetId: null },
    pdpCdbError: null,
    baselinePoints: [],
    scmiPoints: [],
    voltageDiffRows: [],
    setEstimates: {},
    systemProbe: {},
    canSelectSet: (id) => id === 3 || id === 1,
    setBlockedReason: () => null,
    expectedDeltaSummary: () => '-24 mV',
    canCaptureStockBaseline: false,
  };
  page.render();
  assert.ok(nodes.get('revertBootSetBtn'));
  assert.equal(typeof nodes.get('revertBootSetBtn').onclick, 'function');
});
