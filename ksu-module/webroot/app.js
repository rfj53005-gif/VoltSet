(function () {
  'use strict';

  const C = window.VoltSetCore;
  const APP_VERSION = 'v0.5.3 (KSU WebUI)';
  const RESOLVE_BUNDLE =
    'MOD=/data/adb/modules/voltset; [ -r "$MOD/backend/voltset.sh" ] || MOD=/data/adb/modules_update/voltset; BUNDLE="$MOD/backend"; SH="$BUNDLE/voltset.sh"';
  const POWER_RECORDS_FILE = '/data/adb/voltset/persistent/power-records.csv';
  const LOCAL_STORAGE_KEY = 'voltset_ksu_power_records_v1';
  const CHUNK_SIZE = 65536;

  const state = {
    tab: 'sets',
    loading: false,
    busyAction: null,
    error: null,
    snapshot: null,
    clusterFilter: null,
    expandedSets: new Set(),
    qptMonitoring: false,
    qptInstant: null,
    qptSession: null,
    qptError: null,
    savedPowerRecords: {},
    qptTurnedOnBySelf: false,
    qptTimer: null,
    qptLastSample: null,
    qptIntervals: [],
    qptZeroStreak: 0,
    qptPollingInFlight: false,
  };

  let execSeq = 0;
  let toastTimer = null;

  function esc(str) {
    return String(str === undefined || str === null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function showToast(msg) {
    const el = document.getElementById('toast');
    if (!el || !msg) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
  }

  function ksuExec(command, timeoutMs = 45000) {
    return new Promise((resolve, reject) => {
      if (!window.ksu || typeof window.ksu.exec !== 'function') {
        reject(new Error('请从 KernelSU 管理器打开此 WebUI 页面'));
        return;
      }
      const cb = `__voltsetKsuCb_${++execSeq}`;
      const timer = setTimeout(() => {
        delete window[cb];
        reject(new Error('KernelSU 命令执行超时'));
      }, timeoutMs);
      window[cb] = (errno, stdout, stderr) => {
        clearTimeout(timer);
        delete window[cb];
        resolve({ errno: Number(errno), stdout: String(stdout || ''), stderr: String(stderr || '') });
      };
      try {
        window.ksu.exec(command, '{}', cb);
      } catch (e) {
        clearTimeout(timer);
        delete window[cb];
        reject(e);
      }
    });
  }

  function extractBackendError(res, fallback) {
    const combined = `${res.stdout}\n${res.stderr}`.trim();
    const errs = combined
      .split(/\r?\n/)
      .filter((l) => l.startsWith('ERROR '))
      .map((l) => l.slice(6).trim())
      .filter(Boolean);
    if (errs.length) return errs.join('\n');
    return combined || `${fallback} (errno=${res.errno})`;
  }

  async function runBackendSmall(args, timeoutMs = 20000) {
    const quoted = args.map((a) => `'${String(a).replace(/'/g, `'\\''`)}'`).join(' ');
    const cmd = `${RESOLVE_BUNDLE}; [ -r "$SH" ] || { echo "ERROR 未找到后端脚本 $SH" >&2; exit 1; }; /system/bin/sh "$SH" "$BUNDLE" ${quoted}`;
    const res = await ksuExec(cmd, timeoutMs);
    if (res.errno !== 0) {
      throw new Error(extractBackendError(res, 'Root 后端执行失败'));
    }
    return res.stdout;
  }

  async function runBackendStatusChunked() {
    const tmpTxt = '/data/adb/voltset/session/webui-status.txt';
    const tmpB64 = '/data/adb/voltset/session/webui-status.b64';
    const prepCmd =
      `${RESOLVE_BUNDLE}; [ -r "$SH" ] || { echo "ERROR 未找到后端脚本 $SH" >&2; exit 1; }; ` +
      `mkdir -p /data/adb/voltset/session && ` +
      `if ! /system/bin/sh "$SH" "$BUNDLE" status > '${tmpTxt}'; then rm -f '${tmpTxt}'; exit 1; fi; ` +
      `base64 < '${tmpTxt}' | tr -d '\\n' > '${tmpB64}' && wc -c < '${tmpB64}'`;
    try {
      const prep = await ksuExec(prepCmd, 60000);
      if (prep.errno !== 0) {
        throw new Error(extractBackendError(prep, '读取状态失败'));
      }
      const totalBytes = Number.parseInt(prep.stdout.trim(), 10);
      if (!Number.isFinite(totalBytes) || totalBytes <= 0) {
        throw new Error('后端状态输出为空');
      }
      const chunks = [];
      const count = Math.ceil(totalBytes / CHUNK_SIZE);
      for (let i = 0; i < count; i++) {
        const readRes = await ksuExec(`dd if='${tmpB64}' bs=${CHUNK_SIZE} skip=${i} count=1 2>/dev/null`, 15000);
        if (readRes.errno !== 0) throw new Error('分块读取状态失败');
        chunks.push(readRes.stdout);
      }
      const bytes = C.base64ToBytes(chunks.join(''));
      return new TextDecoder('utf-8').decode(bytes);
    } finally {
      ksuExec(`rm -f '${tmpTxt}' '${tmpB64}' 2>/dev/null || true`, 5000).catch(() => {});
    }
  }

  async function loadSavedPowerRecords() {
    try {
      const res = await ksuExec(`cat '${POWER_RECORDS_FILE}' 2>/dev/null || true`, 5000);
      if (res.errno === 0 && res.stdout.trim()) {
        const parsed = C.deserializePowerRecords(res.stdout);
        state.savedPowerRecords = parsed;
        try {
          localStorage.setItem(LOCAL_STORAGE_KEY, C.serializePowerRecords(parsed));
        } catch (_) {}
        return;
      }
    } catch (_) {}
    try {
      const cached = localStorage.getItem(LOCAL_STORAGE_KEY);
      if (cached) state.savedPowerRecords = C.deserializePowerRecords(cached);
    } catch (_) {}
  }

  async function persistSavedPowerRecords(records) {
    state.savedPowerRecords = records;
    const csv = C.serializePowerRecords(records);
    try {
      localStorage.setItem(LOCAL_STORAGE_KEY, csv);
    } catch (_) {}
    try {
      const b64 = btoa(csv);
      await ksuExec(
        `mkdir -p /data/adb/voltset/persistent && printf '%s' '${b64}' | base64 -d > '${POWER_RECORDS_FILE}.tmp' && mv '${POWER_RECORDS_FILE}.tmp' '${POWER_RECORDS_FILE}'`,
        5000
      );
    } catch (_) {}
  }

  async function saveBaselineForSnapshot(snap) {
    const out = await runBackendSmall(['save_baseline', snap.bootId], 25000);
    const parsed = C.parseBackendOutput(out);
    const csv = parsed.blocks.BASELINE;
    if (!csv) throw new Error('未能取回已保存的原厂基线内容');
    const coeff = snap.qualityReport ? snap.qualityReport.coefficients : {};
    const points = C.parseScmiCsv(csv, snap.isSm8850, coeff);
    if (!points.some((p) => p.domain === 0) || !points.some((p) => p.domain === 1)) {
      throw new Error('原厂基线缺少完整的 CPU 集群数据');
    }
    const estimates = C.estimateAllSets(snap.pdpCdb ? snap.pdpCdb.sets : [], points, snap.pdpHardwareLimits);
    const updated = {
      ...snap,
      baselineSource: 'VOLTSET',
      baselinePoints: points,
      voltageDiffRows: C.diffScmi(snap.scmiPoints, points, snap.activeBootSet),
      setEstimates: estimates,
    };
    updated.compatibility = C.evaluateCompatibility(updated);
    return C.enrichSnapshotHelpers(updated);
  }

  async function refresh() {
    if (state.loading || state.busyAction) return;
    state.loading = true;
    state.error = null;
    render();
    try {
      await loadSavedPowerRecords();
      const raw = await runBackendStatusChunked();
      let snap = await C.buildDeviceSnapshot(raw);
      if (snap.canSaveStockBaseline && snap.baselineSource !== 'VOLTSET') {
        try {
          snap = await saveBaselineForSnapshot(snap);
        } catch (_) {}
      }
      state.snapshot = snap;
      state.error = null;
    } catch (e) {
      state.error = e.message || '读取设备状态失败';
    } finally {
      state.loading = false;
      render();
    }
  }

  // ---- QPT Power Monitoring ----
  async function startQptMonitor() {
    if (state.qptMonitoring) return;
    state.qptMonitoring = true;
    state.qptLastSample = null;
    state.qptIntervals = [];
    state.qptInstant = null;
    state.qptSession = null;
    state.qptError = null;
    state.qptZeroStreak = 0;
    state.qptTurnedOnBySelf = false;
    render();

    try {
      const out = await runBackendSmall(['power_enable'], 8000);
      const parsed = C.parseBackendOutput(out);
      state.qptTurnedOnBySelf = parsed.values.QPT_CHANGED === '1';
    } catch (e) {
      state.qptError = e.message || '无法开启 QPT 硬件功耗遥测';
      render();
    }

    await pollQptOnce();
    if (state.qptMonitoring) {
      state.qptTimer = setInterval(pollQptOnce, 1000);
    }
  }

  async function pollQptOnce() {
    if (!state.qptMonitoring || state.qptPollingInFlight) return;
    state.qptPollingInFlight = true;
    try {
      const out = await runBackendSmall(['power_sample'], 6000);
      const nowNs = Math.round(performance.now() * 1000000);
      const sample = C.parseQptSample(out, nowNs);
      const prev = state.qptLastSample;
      state.qptLastSample = sample;
      if (prev) {
        const delta = C.computeQptDelta(prev, sample);
        if (delta) {
          if (state.qptIntervals.length >= 600) state.qptIntervals.shift();
          state.qptIntervals.push(delta);
          state.qptInstant = delta;
          state.qptSession = C.summarizeQptSession(state.qptIntervals);
          if ((delta.socTotalMw || 0) <= 0.001) {
            state.qptZeroStreak++;
          } else {
            state.qptZeroStreak = 0;
          }
          state.qptError =
            state.qptZeroStreak >= 3 ? 'QPT 硬件计数器未增长（当前固件可能未启用实时功耗遥测）' : null;
          render();
        }
      }
    } catch (e) {
      state.qptError = e.message || '读取硬件功耗失败';
      render();
    } finally {
      state.qptPollingInFlight = false;
    }
  }

  function stopQptMonitor() {
    if (state.qptTimer) {
      clearInterval(state.qptTimer);
      state.qptTimer = null;
    }
    const wasOnBySelf = state.qptTurnedOnBySelf;
    state.qptTurnedOnBySelf = false;
    if (state.qptMonitoring) {
      state.qptMonitoring = false;
      render();
    }
    if (wasOnBySelf) {
      runBackendSmall(['power_disable'], 5000).catch(() => {});
    }
  }

  function toggleQptMonitor() {
    if (state.qptMonitoring) stopQptMonitor();
    else startQptMonitor();
  }

  function resetQptSession() {
    state.qptLastSample = null;
    state.qptIntervals = [];
    state.qptInstant = null;
    state.qptSession = null;
    state.qptError = null;
    state.qptZeroStreak = 0;
    render();
  }

  async function saveCurrentPowerRecord() {
    const snap = state.snapshot;
    const setId = snap ? snap.bootCpussIndex : null;
    const stats = state.qptSession;
    if (!setId || !stats) {
      showToast('请先开启功耗监测并确认当前开机 Set 档位');
      return;
    }
    if (stats.sampleCount < 3) {
      showToast('请至少连续采样 3 次后再保存');
      return;
    }
    const rec = C.buildSavedPowerRecord(setId, stats, Date.now());
    if (!rec) return;
    const next = { ...state.savedPowerRecords, [setId]: rec };
    await persistSavedPowerRecords(next);
    showToast(`已记录 Set ${setId} CPU 均功耗：${rec.avgCpuTotalMw} mW`);
    render();
  }

  // ---- Modals & Set Actions ----
  function closeModal() {
    const backdrop = document.getElementById('modalBackdrop');
    if (backdrop) backdrop.classList.remove('open');
  }

  function openApplyModal(setId) {
    const snap = state.snapshot;
    if (!snap || !snap.canSelectSet(setId)) return;
    const target = snap.pdpCdb.sets.find((s) => s.setId === setId);
    if (!target) return;
    const globalIdx = snap.sdamGlobalIndex ?? 1;
    const rawDec = (target.setId << 4) | globalIdx;
    const targetHex = `0x${(rawDec | 0x88).toString(16).toUpperCase().padStart(2, '0')}`;
    const currentHex = snap.reg7151Hex ? `0x${snap.reg7151Hex.toUpperCase()}` : '--';
    const est = snap.setEstimates ? snap.setEstimates[target.setId] : null;
    const estLine =
      est && (est.clus0 || est.clus1)
        ? `<div class="good-text title-sm tnum">本机预计实降：小核 ${
            est.clus0 ? C.formatRange(est.clus0.minDeltaMv, est.clus0.maxDeltaMv) : '--'
          } · 大核 ${est.clus1 ? C.formatRange(est.clus1.minDeltaMv, est.clus1.maxDeltaMv) : '--'}</div>`
        : '';

    const warnHtml =
      snap.cdtStatus.key7Enabled || target.isAggressive
        ? `<div class="banner danger">
            ${
              snap.cdtStatus.key7Enabled
                ? `<div><strong>高风险：</strong>CDT Key 7 当前处于开启状态，会与 Set ${target.setId} 叠加降压，存在不开机/崩溃风险！</div>`
                : ''
            }
            ${
              target.isAggressive
                ? `<div>激进提示：Set ${target.setId} 最大 CX 降压达到 ${Math.min(
                    target.clus0.minCxMv,
                    target.clus1.minCxMv
                  )} mV，建议先从小档位逐步测试稳定性。</div>`
                : ''
            }
          </div>`
        : '';

    const modal = document.getElementById('modalContent');
    modal.innerHTML = `
      <div class="title-md">确认写入 Set ${target.setId}</div>
      <div class="tnum">即将向 qcom_voltage_adj 写入十进制 ${rawDec}（CPUSS=${target.setId}, Global=${globalIdx}），PMK8850 SDAM2 寄存器 0x7151 将从 ${esc(currentHex)} 变为 ${esc(targetHex)}。</div>
      <div class="subbox">
        <div class="label-xs">表定偏移（pdp_cdb）</div>
        <div class="tnum" style="font-size:13px">小核 PDP0：CX ${esc(C.formatRange(target.clus0.minCxMv, target.clus0.maxCxMv))}，MX ${esc(C.formatRange(target.clus0.minMxMv, target.clus0.maxMxMv))}</div>
        <div class="tnum" style="font-size:13px">大核 PDP1：CX ${esc(C.formatRange(target.clus1.minCxMv, target.clus1.maxCxMv))}，MX ${esc(C.formatRange(target.clus1.minMxMv, target.clus1.maxMxMv))}</div>
        ${estLine}
      </div>
      ${warnHtml}
      <div class="muted">注意：SDAM2 在正常重启与断电前都会保持，且 XBL 无死机看门狗自动回退；若新档位不稳定无法进系统，需长按电源硬重启或通过 9008/已备份方式排查。</div>
      <div class="row-between" style="justify-content:flex-end">
        <button id="modalCancel" type="button">取消</button>
        <button id="modalConfirm" class="primary" type="button">确认写入</button>
      </div>
    `;
    document.getElementById('modalBackdrop').classList.add('open');
    document.getElementById('modalCancel').onclick = closeModal;
    document.getElementById('modalConfirm').onclick = async () => {
      closeModal();
      await confirmApplySet(target.setId);
    };
  }

  async function confirmApplySet(targetSetId) {
    const snap = state.snapshot;
    if (!snap || snap.loading || state.busyAction) return;
    state.busyAction = `正在写入 Set ${targetSetId}…`;
    render();
    try {
      const out = await runBackendSmall([
        'set_override',
        snap.bootId,
        targetSetId,
        snap.sdamCpussIndex,
        snap.sdamGlobalIndex,
      ]);
      const kv = C.parseBackendOutput(out).values;
      const newCpuss = Number.parseInt(kv.SDAM_CPUSS_IDX, 10);
      const newGlobal = Number.parseInt(kv.SDAM_GLOBAL_IDX, 10);
      const newReg = kv.REG_7151 && kv.REG_7151 !== 'unavailable' ? kv.REG_7151 : null;
      const updated = {
        ...snap,
        bootSet: C.computeBootSetStatus({
          firstSdamIndex: snap.bootSet.firstSdamIndex,
          previousTargetIndex: snap.bootSet.previousTargetIndex,
          stagedThisBootIndex: newCpuss,
          firmwareIndex: snap.bootSet.firmwareIndex,
        }),
        sdamCpussIndex: newCpuss,
        sdamGlobalIndex: newGlobal,
        reg7151Hex: newReg,
      };
      updated.compatibility = C.evaluateCompatibility(updated);
      state.snapshot = C.enrichSnapshotHelpers(updated);
      showToast(state.snapshot.pendingReboot ? `已写入 Set ${newCpuss}，下次开机核对` : `SDAM 已读回 Set ${newCpuss}`);
    } catch (e) {
      state.error = e.message || '写入结果未确认，请刷新核对';
    } finally {
      state.busyAction = null;
      render();
    }
  }

  function openRebootModal() {
    const snap = state.snapshot;
    if (!snap || !snap.pendingReboot) return;
    const modal = document.getElementById('modalContent');
    modal.innerHTML = `
      <div class="title-md">确认立即重启？</div>
      <div>设备将立即重启。下次打开 VoltSet 时会核对 SDAM 是否仍为 Set ${snap.pendingSetIndex}。</div>
      <div class="row-between" style="justify-content:flex-end">
        <button id="modalCancel" type="button">取消</button>
        <button id="modalConfirm" class="primary" type="button">立即重启</button>
      </div>
    `;
    document.getElementById('modalBackdrop').classList.add('open');
    document.getElementById('modalCancel').onclick = closeModal;
    document.getElementById('modalConfirm').onclick = async () => {
      closeModal();
      state.busyAction = '正在请求重启…';
      render();
      try {
        await runBackendSmall(['reboot', snap.bootId, snap.pendingSetIndex, snap.sdamGlobalIndex]);
        showToast('已请求重启');
      } catch (e) {
        state.error = e.message || '重启请求失败，请刷新后重试';
      } finally {
        state.busyAction = null;
        render();
      }
    };
  }

  async function captureStockBaselineManual() {
    const snap = state.snapshot;
    if (!snap || !snap.canSaveStockBaseline || state.busyAction || state.loading) return;
    const isRefresh = snap.baselineSource === 'VOLTSET';
    state.busyAction = isRefresh ? '正在刷新 Set 1 原厂基线…' : '正在采集 Set 1 原厂基线…';
    render();
    try {
      state.snapshot = await saveBaselineForSnapshot(snap);
      showToast(`${isRefresh ? '已刷新' : '已保存'}原厂基线（${state.snapshot.baselinePoints.length} 个频点）`);
    } catch (e) {
      showToast(e.message || '采集基线失败');
    } finally {
      state.busyAction = null;
      render();
    }
  }

  // ---- Rendering ----
  function renderHeroSetsCard(snap, controlsEnabled) {
    const running = snap.bootCpussIndex;
    const sdamIdx = snap.sdamCpussIndex;
    const regStr = snap.reg7151Hex ? `0x${snap.reg7151Hex.toUpperCase()}` : '--';
    const liveTitle = running !== null ? `Set ${running}` : snap.bootSet.rebootReadbackMismatch ? '待刷新' : '未确认';
    let liveDetail = '读取固件或开机首次记录中';
    if (snap.bootSet.firmwareIndex !== null) liveDetail = 'pdp_cdb 强制覆盖生效中';
    else if (running !== null) liveDetail = '由本次开机首次 SDAM 读回推定';
    else if (snap.bootSet.rebootReadbackMismatch) liveDetail = '重启后 SDAM 与上次写入不一致';

    const cdtChip = snap.cdtStatus.key7Enabled
      ? '<span class="chip danger">CDT Key7 已启用</span>'
      : snap.cdtStatus.state === 'CORRUPT'
      ? '<span class="chip danger">CDT 异常</span>'
      : snap.cdtStatus.state === 'EMPTY' || snap.cdtStatus.state === 'STOCK'
      ? '<span class="chip good">CDT Key7 未开启</span>'
      : '<span class="chip">CDT 未知</span>';

    const cdtBanner =
      snap.cdtStatus.key7Enabled || snap.cdtStatus.state === 'CORRUPT'
        ? `<div class="banner danger">
            <strong>${snap.cdtStatus.key7Enabled ? '警告：CDT Key 7（SLT+ACD）当前已开启！' : '警告：CDT 分区状态异常'}</strong>
            <div style="margin-top:4px">${esc(snap.cdtStatus.detail)}</div>
          </div>`
        : '';

    const pendingBanner = snap.pendingReboot
      ? `<div class="banner warn">
          <div class="row-between">
            <div>
              <strong>已设定下次开机为 Set ${snap.pendingSetIndex}</strong>
              <div style="margin-top:2px">当前硬件仍按 ${running !== null ? `Set ${running}` : '开机原状态'} 运行，需完整重启。</div>
            </div>
            <button type="button" id="rebootBtn" ${controlsEnabled ? '' : 'disabled'}>立即重启</button>
          </div>
        </div>`
      : '';

    const resetBtn =
      running !== null && running !== 1 && !snap.overrideLockedByFirmware
        ? `<button type="button" id="resetSet1Btn" ${controlsEnabled && snap.canSelectSet(1) ? '' : 'disabled'}>还原默认 Set 1</button>`
        : '';

    return `
      ${cdtBanner}
      <div class="card">
        <div class="row-between">
          <div>
            <div class="title-md">CPU 调压状态</div>
            <div class="muted">${esc(snap.deviceModel)} (${esc(snap.deviceName)})</div>
          </div>
          ${cdtChip}
        </div>
        <div class="grid-2">
          <div class="subbox primary">
            <div class="label-xs">当前硬件运行推定</div>
            <div class="val-lg tnum">${esc(liveTitle)}</div>
            <div class="label-xs">${esc(liveDetail)}</div>
          </div>
          <div class="subbox">
            <div class="label-xs">SDAM2 设定 (0x7151)</div>
            <div class="val-lg tnum">${sdamIdx !== null ? `Set ${sdamIdx}` : '--'}</div>
            <div class="label-xs tnum">${esc(regStr)} · Global ${snap.sdamGlobalIndex ?? '--'}</div>
          </div>
        </div>
        ${pendingBanner}
        ${resetBtn ? `<div class="row">${resetBtn}</div>` : ''}
      </div>
    `;
  }

  function renderClusterSummary(title, summary, measured, estimate) {
    if (measured && measured.minDeltaMv !== null) {
      return `
        <div class="subbox">
          <div class="label-xs">${title}</div>
          <div class="val-md tnum ${measured.minDeltaMv < 0 ? 'good-text' : ''}">实测 ${esc(C.formatRange(measured.minDeltaMv, measured.maxDeltaMv))}</div>
          <div class="label-xs tnum">表定 CX ${esc(C.formatRange(summary.minCxMv, summary.maxCxMv))} · MX ${esc(C.formatRange(summary.minMxMv, summary.maxMxMv))}</div>
          <div class="label-xs tnum">降压 ${measured.loweredBins}/${measured.totalBins} 档</div>
        </div>
      `;
    }
    if (estimate) {
      const prefix = estimate.hasRoundingVariance || estimate.ceilingRiskBins > 0 ? '预计约' : '预计';
      const notes = [];
      if (estimate.floorClampedBins > 0) notes.push(`${estimate.floorClampedBins} 档触及下限`);
      if (estimate.monotonicityClampedBins > 0) notes.push(`${estimate.monotonicityClampedBins} 档受单调限位`);
      if (estimate.ceilingRiskBins > 0) notes.push(`${estimate.ceilingRiskBins} 档原厂顶上限`);
      const micro = notes.length ? notes.join(' · ') : estimate.minDeltaMv < 0 ? `全 ${estimate.totalBins} 档按表定生效` : `保持原厂（共 ${estimate.totalBins} 档）`;
      return `
        <div class="subbox">
          <div class="label-xs">${title}</div>
          <div class="val-md tnum ${estimate.minDeltaMv < 0 ? 'good-text' : ''}">${prefix} ${esc(C.formatRange(estimate.minDeltaMv, estimate.maxDeltaMv))}</div>
          <div class="label-xs tnum">表定 CX ${esc(C.formatRange(summary.minCxMv, summary.maxCxMv))} · MX ${esc(C.formatRange(summary.minMxMv, summary.maxMxMv))}</div>
          <div class="label-xs tnum">${esc(micro)}</div>
        </div>
      `;
    }
    return `
      <div class="subbox">
        <div class="label-xs">${title}</div>
        <div class="val-md tnum ${summary.minCxMv < 0 ? 'good-text' : ''}">表定 CX ${esc(C.formatRange(summary.minCxMv, summary.maxCxMv))}</div>
        <div class="label-xs tnum">表定 MX ${esc(C.formatRange(summary.minMxMv, summary.maxMxMv))}</div>
      </div>
    `;
  }

  function renderClusterDetailBlock(title, summary, estimate) {
    const entriesHtml = summary.entries
      .map((e) => {
        const lbl = e.mode === 'FREQ' ? C.formatFreqMhz(e.value) : e.mode === 'PSTATE' ? `P-State #${e.value}` : `#${e.value}`;
        return `
          <div class="detail-row tnum">
            <span class="mono">${esc(lbl)}</span>
            <span class="muted" style="font-size:11px">叠加 ${esc(C.formatSignedMv(e.rawDeltaCxMv))}/${esc(C.formatSignedMv(e.rawDeltaMxMv))}</span>
            <strong class="${e.effectiveCxMv < 0 ? 'good-text' : ''}">CX ${esc(C.formatSignedMv(e.effectiveCxMv))} · MX ${esc(C.formatSignedMv(e.effectiveMxMv))}</strong>
          </div>
        `;
      })
      .join('');

    const limited = (estimate ? estimate.bins : []).filter((b) => b.deltaMv !== b.cdbOffsetMv || b.atStockCeiling);
    const limitedHtml = limited.length
      ? `<div class="muted" style="font-size:12px;font-weight:600;margin-top:6px">本机受限频点预览 (${limited.length} 档)</div>` +
        limited
          .map((b) => {
            const r = [];
            if (b.clampedByFloor) r.push(`触及下限 ${b.floorMv}`);
            if (b.clampedByMonotonicity) r.push('单调限位');
            if (b.atStockCeiling) r.push(`原厂顶上限 ${b.ceilMv}`);
            if (!r.length) r.push(b.cdbOffsetMv % 4 !== 0 && b.deltaMv !== b.cdbOffsetMv ? '4mV 对齐' : '步进收敛');
            return `<div class="detail-row tnum"><span class="mono">${esc(C.formatFreqMhz(b.freqKhz))}</span><span class="${
              b.deltaMv < 0 ? 'good-text' : ''
            }">${b.baselineMv}→${b.estimatedMv} (${esc(C.formatSignedMv(b.deltaMv))})</span><span class="warn-text" style="font-size:11px">${esc(
              r.join('·')
            )}</span></div>`;
          })
          .join('')
      : '';

    return `
      <div>
        <div class="title-sm" style="margin-bottom:4px">${title}</div>
        <div class="detail-row subbox tnum" style="flex-direction:row;justify-content:space-between">
          <span>全局基底 ALL</span>
          <strong>CX ${esc(C.formatSignedMv(summary.allCxMv))} / MX ${esc(C.formatSignedMv(summary.allMxMv))}</strong>
        </div>
        ${entriesHtml}
        ${limitedHtml}
      </div>
    `;
  }

  function renderSetsTab(snap, controlsEnabled) {
    const out = [renderHeroSetsCard(snap, controlsEnabled)];
    if (snap.pdpCdbError && !snap.pdpCdb) {
      out.push(`<div class="card warn-box"><div class="title-sm">无法解析 pdp_cdb 固件档位</div><div style="font-size:13px">${esc(snap.pdpCdbError)}</div></div>`);
    }
    const sets = snap.pdpCdb ? snap.pdpCdb.sets : [];
    const meas0 = snap.voltageDiffRows.length ? C.summarizeByCluster(snap.voltageDiffRows, 0) : null;
    const meas1 = snap.voltageDiffRows.length ? C.summarizeByCluster(snap.voltageDiffRows, 1) : null;

    for (const p of sets) {
      const bootLabel =
        p.setId === snap.bootCpussIndex
          ? snap.bootSet.firmwareIndex !== null
            ? '固件锁定'
            : '本次开机推定'
          : null;
      const isSdam = p.setId === snap.sdamCpussIndex;
      const isPending = isSdam && snap.pendingReboot;
      const est = snap.setEstimates ? snap.setEstimates[p.setId] : null;
      const tagChip =
        p.tag === 'DEFAULT'
          ? '<span class="chip">原厂默认</span>'
          : p.tag === 'UNIFORM'
          ? '<span class="chip good">全频均匀</span>'
          : '<span class="chip secondary">分频曲线</span>';
      const cardCls = isPending ? 'card pending' : bootLabel || isSdam ? 'card highlight' : 'card';
      const canExpand =
        p.clus0.entries.length ||
        p.clus1.entries.length ||
        p.clus0.allCxMv ||
        p.clus1.allCxMv ||
        p.clus0.allMxMv ||
        p.clus1.allMxMv ||
        est;

      const totalEntries = p.clus0.entries.length + p.clus1.entries.length;
      const openAttr = state.expandedSets.has(p.setId) ? 'open' : '';

      out.push(`
        <div class="${cardCls}">
          <div class="row wrap">
            <span class="title-md tnum" style="font-size:18px">Set ${p.setId}</span>
            ${tagChip}
            ${p.isAggressive ? '<span class="chip danger">激进</span>' : ''}
            ${bootLabel ? `<span class="chip primary">${bootLabel}</span>` : ''}
            ${isSdam ? `<span class="chip warn">${isPending ? '待重启' : 'SDAM 读回'}</span>` : ''}
          </div>
          <div class="grid-2">
            ${renderClusterSummary('小核 PDP0 (CPU0–5)', p.clus0, bootLabel ? meas0 : null, est ? est.clus0 : null)}
            ${renderClusterSummary('大核 PDP1 (CPU6–7)', p.clus1, bootLabel ? meas1 : null, est ? est.clus1 : null)}
          </div>
          ${
            canExpand
              ? `<details class="set-details" data-set-id="${p.setId}" ${openAttr}>
                  <summary>查看 pdp_cdb ${totalEntries > 0 ? `分频规则 ${totalEntries} 条` : '规则明细'}</summary>
                  <div class="detail-list">
                    ${renderClusterDetailBlock('小核 PDP0 明细', p.clus0, est ? est.clus0 : null)}
                    ${renderClusterDetailBlock('大核 PDP1 明细', p.clus1, est ? est.clus1 : null)}
                  </div>
                </details>`
              : ''
          }
          ${
            !isSdam
              ? `<div class="row" style="justify-content:flex-end">
                  <button type="button" class="primary apply-set-btn" data-set-id="${p.setId}" ${
                    controlsEnabled && snap.canSelectSet(p.setId) ? '' : 'disabled'
                  }>设为下次开机</button>
                </div>`
              : ''
          }
        </div>
      `);
    }
    return out.join('');
  }

  function formatMw(v) {
    return v !== null && v !== undefined ? `${Math.round(v)} mW` : '-- mW';
  }

  function renderPowerTab(snap) {
    const qptAvailable = snap.systemProbe && snap.systemProbe.qptRails && snap.systemProbe.qptRails.length > 0;
    const instant = state.qptInstant;
    const session = state.qptSession;
    const records = Object.values(state.savedPowerRecords || {}).sort((a, b) => a.setId - b.setId);
    const baseRec = state.savedPowerRecords ? state.savedPowerRecords[1] : null;
    const bootSetId = snap.bootCpussIndex;

    const subtitle = session
      ? `已采样 ${session.sampleCount} 次 · ${Math.round(session.elapsedSec)}s${bootSetId ? ` · Set ${bootSetId}` : ''}`
      : state.qptMonitoring
      ? '正在开启遥测并等待首次双点采样…'
      : qptAvailable
      ? '按需开启 /sys/class/powercap/qpt · 停止/切页自动关闭'
      : '未在本机检测到可用的 QPT powercap 功耗轨';

    const tilesHtml =
      instant || state.qptMonitoring
        ? `
          <div class="grid-2">
            <div class="subbox">
              <div class="label-xs">中核 cpu-m</div>
              <div class="val-lg tnum">${formatMw(instant ? instant.cpuMidMw : null)}</div>
              <div class="label-xs tnum">${instant && instant.cpu0FreqKhz > 0 ? `${Math.trunc(instant.cpu0FreqKhz / 1000)} MHz` : '--'}</div>
            </div>
            <div class="subbox">
              <div class="label-xs">大核 cpu-l</div>
              <div class="val-lg tnum">${formatMw(instant ? instant.cpuLargeMw : null)}</div>
              <div class="label-xs tnum">${instant && instant.cpu1FreqKhz > 0 ? `${Math.trunc(instant.cpu1FreqKhz / 1000)} MHz` : '--'}</div>
            </div>
            <div class="subbox">
              <div class="label-xs">GPU</div>
              <div class="val-lg tnum">${formatMw(instant ? instant.gpuMw : null)}</div>
              <div class="label-xs tnum">${instant && instant.gpuFreqHz > 0 ? `${Math.trunc(instant.gpuFreqHz / 1000000)} MHz` : '--'}</div>
            </div>
            <div class="subbox primary">
              <div class="label-xs">CPU 合计 (中核 + 大核)</div>
              <div class="val-lg tnum">${formatMw(instant ? instant.cpuTotalMw : null)}</div>
              <div class="label-xs tnum">${instant && instant.nspMw >= 1 ? `NSP ${Math.round(instant.nspMw)} mW` : 'SoC 硬件能量差值'}</div>
            </div>
          </div>
        `
        : '';

    const sessionHtml = session
      ? `
        <div class="subbox">
          <div class="tnum" style="font-size:13px;font-weight:600">
            本轮均值：CPU 合计 ${formatMw(session.avgCpuTotalMw)}（中核 ${formatMw(session.avgCpuMidMw)} / 大核 ${formatMw(
              session.avgCpuLargeMw
            )}） · GPU ${formatMw(session.avgGpuMw)}
          </div>
          <div class="row-between" style="margin-top:6px">
            <button type="button" class="ghost" id="qptResetBtn">重置统计</button>
            <button type="button" id="qptSaveBtn" ${bootSetId && session.sampleCount >= 3 ? '' : 'disabled'}>
              ${bootSetId ? `记录为 Set ${bootSetId} 均功耗` : '档位待确认'}
            </button>
          </div>
        </div>
      `
      : '';

    const recordsHtml = records.length
      ? `
        <div class="card">
          <div class="row-between">
            <div class="title-sm">各档位实测均功耗对比</div>
            <button type="button" class="ghost" id="qptClearRecordsBtn">清空</button>
          </div>
          ${records
            .map((r) => {
              let deltaBadge = '';
              if (r.setId !== 1 && baseRec && baseRec.avgCpuTotalMw > 0) {
                const dm = r.avgCpuTotalMw - baseRec.avgCpuTotalMw;
                const pct = ((dm * 100.0) / baseRec.avgCpuTotalMw).toFixed(1);
                const sign = dm > 0 ? '+' : '';
                const cls = dm < 0 ? 'chip good' : dm > 0 ? 'chip warn' : 'chip';
                deltaBadge = `<span class="${cls} tnum">${sign}${dm} mW (${sign}${pct}%)</span>`;
              }
              return `
                <div class="subbox row-between" style="flex-direction:row;align-items:center">
                  <div>
                    <div class="title-sm tnum">Set ${r.setId} · CPU ${r.avgCpuTotalMw} mW (${r.elapsedSec}s)</div>
                    <div class="label-xs tnum">中核 ${r.avgCpuMidMw} · 大核 ${r.avgCpuLargeMw} · GPU ${r.avgGpuMw} mW</div>
                  </div>
                  <div class="row">
                    ${deltaBadge}
                    <button type="button" class="ghost qpt-del-btn" data-set-id="${r.setId}">删除</button>
                  </div>
                </div>
              `;
            })
            .join('')}
        </div>
      `
      : `<div class="card"><div class="title-sm">跨档位功耗对比</div><div class="muted">先在某个档位（如 Set 1）开启监测至少 3 秒并点击「记录为 Set 1 均功耗」，切换到 Set 6 重启后再固定相同负载记录一次，即可直接查看 mW 与百分比差异。</div></div>`;

    return `
      <div class="card ${state.qptMonitoring ? 'highlight' : ''}">
        <div class="row-between">
          <div>
            <div class="title-md">硬件实时功耗 (QPT)</div>
            <div class="muted tnum">${esc(subtitle)}</div>
          </div>
          <button type="button" id="qptToggleBtn" class="${state.qptMonitoring ? '' : 'primary'}" ${
            qptAvailable || state.qptMonitoring ? '' : 'disabled'
          }>${state.qptMonitoring ? '停止监测' : '开始监测'}</button>
        </div>
        ${state.qptError ? `<div class="banner warn">${esc(state.qptError)}</div>` : ''}
        ${tilesHtml}
        ${sessionHtml}
      </div>
      ${recordsHtml}
    `;
  }

  function renderVoltageTab(snap, controlsEnabled) {
    const out = [];
    const rows = snap.voltageDiffRows || [];
    const pdp0 = C.summarizeByCluster(rows, 0);
    const pdp1 = C.summarizeByCluster(rows, 1);

    if (snap.isSm8850) {
      const qr = snap.qualityReport;
      out.push(`
        <div class="card primary-hero">
          <div class="row-between">
            <div class="title-md">体质电压表</div>
            ${qr ? '<button type="button" class="ghost" style="color:inherit" id="copyQualityBtn">复制结果</button>' : ''}
          </div>
          ${
            qr
              ? `<div class="row wrap" style="gap:18px">
                  <div><div style="font-size:12px;opacity:.85">综合参考分</div><div class="val-lg tnum" style="font-size:26px">${qr.score}</div></div>
                  ${qr.clusters
                    .map(
                      (cl) =>
                        `<div><div style="font-size:12px;opacity:.85">PDP${cl.domain} · C=${cl.coefficient}</div><div class="val-lg tnum" style="font-size:26px">${cl.score}</div></div>`
                    )
                    .join('')}
                </div>
                <div style="font-size:12px;opacity:.9">两次读取一致 · ${qr.points.length} 个频点 · ${
                  snap.scmiSource === 'ENERGY_MODEL' ? 'debugfs/energy_model 零模块' : 'qcom-scmi-ro.ko'
                }。分数随计划电压变化，仅供参考。</div>`
              : `<div style="font-size:13px">${snap.scmiError ? '读取 SCMI 计划电压后可查看体质参考分。' : '两次读表需通过校验才显示分数，可刷新重试。'}</div>`
          }
        </div>
      `);
    }

    const baseBadge =
      snap.baselineSource === 'VOLTSET'
        ? '<span class="chip good">VoltSet 原厂基线</span>'
        : snap.baselineSource === 'SUNTUNER'
        ? '<span class="chip good">已复用 SunTuner 基线</span>'
        : '<span class="chip warn">暂无原厂基线</span>';

    const bootText =
      snap.bootSet.firmwareIndex !== null
        ? `固件锁定 Set ${snap.bootSet.firmwareIndex}`
        : snap.bootCpussIndex !== null
        ? `本次开机推定 Set ${snap.bootCpussIndex}`
        : '本次开机档待确认';
    const srcText = snap.scmiSource === 'ENERGY_MODEL' ? ' · energy_model 零模块' : snap.scmiSource === 'KERNEL_MODULE' ? ' · 内核模块' : '';
    const cprh = C.assessCprhDrift(snap.bootCpussIndex, rows);

    out.push(`
      <div class="card">
        <div class="row-between">
          <div>
            <div class="title-md">SCMI 计划电压对比</div>
            <div class="muted">${esc(bootText + srcText)}</div>
          </div>
          ${baseBadge}
        </div>
        <div class="grid-2">
          <div class="subbox">
            <div class="label-xs">小核 PDP0 (CPU0–5)</div>
            <div class="val-md tnum ${pdp0.minDeltaMv < 0 ? 'good-text' : ''}">${
              pdp0.minDeltaMv !== null ? esc(C.formatRange(pdp0.minDeltaMv, pdp0.maxDeltaMv)) : `共 ${pdp0.totalBins} 档`
            }</div>
            <div class="label-xs tnum">${pdp0.loweredBins > 0 || pdp0.unchangedBins > 0 || pdp0.raisedBins > 0 ? `降压 ${pdp0.loweredBins}/${pdp0.totalBins} 档` : '等待基线对比'}</div>
          </div>
          <div class="subbox">
            <div class="label-xs">大核 PDP1 (CPU6–7)</div>
            <div class="val-md tnum ${pdp1.minDeltaMv < 0 ? 'good-text' : ''}">${
              pdp1.minDeltaMv !== null ? esc(C.formatRange(pdp1.minDeltaMv, pdp1.maxDeltaMv)) : `共 ${pdp1.totalBins} 档`
            }</div>
            <div class="label-xs tnum">${pdp1.loweredBins > 0 || pdp1.unchangedBins > 0 || pdp1.raisedBins > 0 ? `降压 ${pdp1.loweredBins}/${pdp1.totalBins} 档` : '等待基线对比'}</div>
          </div>
        </div>
        ${
          cprh.isLikelyCprhDrift
            ? `<div class="banner info">Set 1 检测到 ${cprh.affectedBins} 个频点出现 ${esc(
                C.formatRange(cprh.minDeltaMv, cprh.maxDeltaMv)
              )} 偏移：这是每次冷启 PDP0 CPRH 老化传感器重测并在 4 mV 步进取整引起的正常校准波动，可点击下方按钮以本次冷启刷新原厂基线。</div>`
            : ''
        }
        ${
          snap.canSaveStockBaseline
            ? `<button type="button" class="full" id="captureBaselineBtn" ${controlsEnabled ? '' : 'disabled'}>${
                snap.baselineSource === 'VOLTSET' ? '刷新 Set 1 原厂基线（以本次冷启为准）' : '在 Set 1 采集并保存为 VoltSet 原厂基线'
              }</button>`
            : snap.baselineSource === 'NONE'
            ? '<div class="muted">提示：切换回 Set 1 并重启后刷新，应用会自动保存原厂 SCMI 基线用于对比实际降压幅度。</div>'
            : ''
        }
      </div>
    `);

    if (snap.scmiError) {
      out.push(`<div class="card warn-box"><div class="title-sm">未能读取 SCMI 电压表</div><div style="font-size:13px">${esc(snap.scmiError)}<br>内核：${esc(snap.kernelRelease)}</div></div>`);
      return out.join('');
    }

    const filtered = state.clusterFilter === null ? rows : rows.filter((r) => r.domain === state.clusterFilter);
    const maxMv = Math.max(1, ...rows.map((r) => Math.max(r.currentMv, r.baselineMv || 0)));

    out.push(`
      <div class="filter-chips">
        <button type="button" class="filter-chip ${state.clusterFilter === null ? 'active' : ''}" data-filter="all">全部 (${rows.length})</button>
        <button type="button" class="filter-chip ${state.clusterFilter === 0 ? 'active' : ''}" data-filter="0">小核 PDP0 (${pdp0.totalBins})</button>
        <button type="button" class="filter-chip ${state.clusterFilter === 1 ? 'active' : ''}" data-filter="1">大核 PDP1 (${pdp1.totalBins})</button>
      </div>
    `);

    if (filtered.length) {
      const tableRows = [];
      filtered.forEach((r, idx) => {
        if (state.clusterFilter === null && (idx === 0 || filtered[idx - 1].domain !== r.domain)) {
          tableRows.push(`<div class="table-group">${r.domain === 0 ? 'PDP0 · CPU0–5' : 'PDP1 · CPU6–7'}</div>`);
        }
        const d = r.deltaMv;
        let pill = `<span class="chip tnum">${r.expectedCdbCxMv !== null ? `目标 ${C.formatSignedMv(r.expectedCdbCxMv)}` : '--'}</span>`;
        let barCls = 'bar-fill';
        if (d !== null && d !== undefined) {
          if (d < 0) {
            pill = `<span class="chip good tnum">${d} mV</span>`;
            barCls = 'bar-fill good';
          } else if (d > 0) {
            pill = `<span class="chip warn tnum">+${d} mV</span>`;
            barCls = 'bar-fill warn';
          } else {
            pill = '<span class="chip tnum">0 mV</span>';
          }
        }
        const pct = Math.min(100, Math.max(5, (r.currentMv * 100) / maxMv)).toFixed(1);
        tableRows.push(`
          <div class="table-row">
            <div class="table-cols tnum">
              <span class="mono" style="font-weight:600">${esc(C.formatFreqMhz(r.freqKhz))}</span>
              <span class="right muted">${r.baselineMv ?? '--'}</span>
              <span class="right" style="font-weight:600">${r.currentMv} mV</span>
              <span class="right">${pill}</span>
            </div>
            <div class="bar-track"><div class="${barCls}" style="width:${pct}%"></div></div>
          </div>
        `);
      });

      out.push(`
        <div class="card table-card">
          <div class="table-head">
            <span>频率</span><span class="right">原厂</span><span class="right">当前</span><span class="right">基线差值</span>
          </div>
          ${tableRows.join('')}
        </div>
      `);
    }

    return out.join('');
  }

  function renderCompatTab(snap) {
    const rep = snap.compatibility;
    const heroCls = rep.level === 'COMPATIBLE' ? 'banner good' : rep.level === 'PARTIAL' ? 'banner warn' : 'banner danger';
    const probe = snap.systemProbe;
    const soc = probe ? C.parseSocInfo(probe.socInfo) : null;
    const qf = probe ? probe.qfpromAging : null;
    const fmtDelta = (v) => (v > 0 ? `+${v}` : String(v));

    const siliconCard =
      soc || qf
        ? `
          <div class="card">
            <div class="title-sm">芯片体质与 CPRH 老化校准</div>
            ${
              soc
                ? `<div class="grid-2">
                    ${soc.chipId ? `<div class="subbox"><div class="label-xs">Chip ID</div><div class="val-md tnum">${esc(soc.chipId)}</div></div>` : ''}
                    ${soc.foundryId ? `<div class="subbox"><div class="label-xs">Foundry</div><div class="val-md tnum">${esc(soc.foundryId)}</div></div>` : ''}
                    ${soc.pcode ? `<div class="subbox"><div class="label-xs">PCode</div><div class="val-md tnum">${esc(soc.pcode)}</div></div>` : ''}
                    ${soc.pmicRev ? `<div class="subbox"><div class="label-xs">PMIC Rev</div><div class="val-md tnum">${esc(soc.pmicRev)}</div></div>` : ''}
                    ${soc.featureCode ? `<div class="subbox"><div class="label-xs">Feature</div><div class="val-md tnum">${esc(soc.featureCode)}</div></div>` : ''}
                    ${soc.oemVariant ? `<div class="subbox"><div class="label-xs">OEM</div><div class="val-md tnum">${esc(soc.oemVariant)}</div></div>` : ''}
                  </div>`
                : ''
            }
            ${
              qf
                ? `<div class="grid-2">
                    <div class="subbox"><div class="label-xs">小核 PDP0 老化熔丝</div><div class="val-md tnum">${esc(fmtDelta(qf.clus0FusedDelta))}</div></div>
                    <div class="subbox"><div class="label-xs">大核 PDP1 老化熔丝</div><div class="val-md tnum">${esc(fmtDelta(qf.clus1FusedDelta))}</div></div>
                  </div>
                  <div class="label-xs mono">原始熔丝 0xA10/0xA20/0xA24：${esc(qf.formattedHex)}</div>
                  <div class="muted" style="font-size:12px">说明：每次冷启时 PDP0 CPRH 会重测片上老化环形振荡器、减去上述出厂熔丝参考值并截断在 0..+15 mV 后四舍五入至 4 mV 步进；因此即使固定 Set 1，不同冷启间也属正常范围内的 ±4 mV 波动。</div>`
                : ''
            }
          </div>
        `
        : '';

    const probeCard =
      probe && probe.hasAnyData
        ? `
          <div class="card">
            <details ${rep.level === 'UNSUPPORTED' ? 'open' : ''}>
              <summary>全机只读自动探测（${probe.pdpCdtPartitions.length} 个分区 · ${probe.cpufreqPolicies.length} 个 CPU 簇）</summary>
              <div class="detail-list">
                <div class="probe-item"><span class="label-xs">qcom_voltage_adj 目录</span><span class="mono">${esc(probe.adjDirPath || '未发现')}</span></div>
                <div class="probe-item"><span class="label-xs">PMIC regmap / SDAM2 nvmem</span><span class="mono">${esc(probe.regmapNode || '未命中')} | ${esc(probe.sdam2NvmemPath || '未发现')}</span></div>
                <div class="probe-item"><span class="label-xs">CPU 调频簇</span><span class="mono">${esc(probe.cpufreqPolicies.join(', ') || '未读取')}</span></div>
                <div class="probe-item"><span class="label-xs">energy_model 零模块域</span><span class="mono">${esc(probe.energyModelDomains.join(', ') || '未发现')}</span></div>
                <div class="probe-item"><span class="label-xs">QFPROM 老化熔丝</span><span class="mono">${
                  qf ? `clus0=${fmtDelta(qf.clus0FusedDelta)}, clus1=${fmtDelta(qf.clus1FusedDelta)} (${qf.formattedHex})` : '未读取'
                }</span></div>
                <div class="probe-item"><span class="label-xs">QPT 硬件功耗轨</span><span class="mono">${esc(probe.qptRails.join(', ') || '未发现')}</span></div>
              </div>
            </details>
          </div>
        `
        : '';

    return `
      <div class="card">
        <div class="${heroCls}">
          <div class="title-md">${esc(rep.headline)}</div>
          <div style="font-size:13px;margin-top:3px">${esc(rep.subheadline)}</div>
        </div>
        <div class="grid-2" style="grid-template-columns:1fr 1fr 0.7fr">
          <div class="subbox"><div class="label-xs">SoC</div><div class="title-sm tnum">${esc(snap.socModel)}</div></div>
          <div class="subbox"><div class="label-xs">机型</div><div class="title-sm tnum">${esc(snap.deviceModel)}</div></div>
          <div class="subbox"><div class="label-xs">内核页</div><div class="title-sm tnum">${snap.pageSizeBytes ? `${Math.trunc(snap.pageSizeBytes / 1024)}K` : '--'}</div></div>
        </div>
        <div class="label-xs mono">内核：${esc(snap.kernelRelease)}</div>
        <div class="grid-2">
          <button type="button" id="compatRefreshBtn" ${state.loading || state.busyAction ? 'disabled' : ''}>重新检测</button>
          <button type="button" class="primary" id="copyCompatBtn">复制检测报告</button>
        </div>
      </div>
      <div class="card">
        <div class="title-sm">分项兼容性检测（${rep.passedCount}/${rep.totalCount} 项通过）</div>
        <div>
          ${rep.items
            .map((it) => {
              const b =
                it.status === 'PASS'
                  ? '<span class="chip good">通过</span>'
                  : it.status === 'WARN'
                  ? '<span class="chip warn">注意</span>'
                  : '<span class="chip danger">不兼容</span>';
              return `
                <div class="check-item">
                  <div class="row-between"><span class="title-sm">${esc(it.title)}</span>${b}</div>
                  <div style="font-size:13px;font-weight:600" class="tnum">${esc(it.summary)}</div>
                  <div class="muted" style="font-size:12px">${esc(it.detail)}</div>
                </div>
              `;
            })
            .join('')}
        </div>
      </div>
      ${siliconCard}
      ${probeCard}
    `;
  }

  function render() {
    const busy = state.loading || Boolean(state.busyAction);
    const bar = document.getElementById('progressBar');
    if (bar) bar.classList.toggle('active', busy);
    const refreshBtn = document.getElementById('refreshBtn');
    if (refreshBtn) refreshBtn.disabled = busy;
    const sub = document.getElementById('topbarSub');
    if (sub) {
      sub.textContent =
        state.busyAction ||
        (state.loading
          ? '正在检测设备状态…'
          : state.snapshot
          ? `${state.snapshot.socModel} · ${state.snapshot.deviceModel}`
          : 'SM8850 OEM 调压');
    }

    document.querySelectorAll('.nav-btn').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.tab === state.tab);
    });

    const main = document.getElementById('mainContent');
    if (!main) return;
    const snap = state.snapshot;
    if (!snap) {
      if (state.loading) {
        main.innerHTML = '<div class="empty-center">正在通过 KernelSU 读取档位和电压表…</div>';
      } else {
        main.innerHTML = `<div class="card danger-box"><div class="title-md">读取失败</div><div style="font-size:13px">${esc(
          state.error || '未知错误'
        )}</div><div><button type="button" class="primary" id="retryBtn">重试</button></div></div>`;
        const retryBtn = document.getElementById('retryBtn');
        if (retryBtn) retryBtn.onclick = refresh;
      }
      return;
    }

    const controlsEnabled = !busy && !state.error;
    const errorBanner = state.error
      ? `<div class="banner danger row-between"><span>显示上次读数 · ${esc(state.error)}</span><button type="button" id="bannerRetryBtn">重试</button></div>`
      : '';

    let bodyHtml = '';
    if (state.tab === 'sets') bodyHtml = renderSetsTab(snap, controlsEnabled);
    else if (state.tab === 'power') bodyHtml = renderPowerTab(snap);
    else if (state.tab === 'voltage') bodyHtml = renderVoltageTab(snap, controlsEnabled);
    else bodyHtml = renderCompatTab(snap);

    main.innerHTML = errorBanner + bodyHtml;
    bindInteractions();
  }

  function bindInteractions() {
    const snap = state.snapshot;
    const br = document.getElementById('bannerRetryBtn');
    if (br) br.onclick = refresh;
    const rebootBtn = document.getElementById('rebootBtn');
    if (rebootBtn) rebootBtn.onclick = openRebootModal;
    const resetBtn = document.getElementById('resetSet1Btn');
    if (resetBtn) resetBtn.onclick = () => openApplyModal(1);

    document.querySelectorAll('.apply-set-btn').forEach((btn) => {
      btn.onclick = () => openApplyModal(Number.parseInt(btn.dataset.setId, 10));
    });
    document.querySelectorAll('.set-details').forEach((det) => {
      det.ontoggle = () => {
        const id = Number.parseInt(det.dataset.setId, 10);
        if (det.open) state.expandedSets.add(id);
        else state.expandedSets.delete(id);
      };
    });

    const qptToggle = document.getElementById('qptToggleBtn');
    if (qptToggle) qptToggle.onclick = toggleQptMonitor;
    const qptReset = document.getElementById('qptResetBtn');
    if (qptReset) qptReset.onclick = resetQptSession;
    const qptSave = document.getElementById('qptSaveBtn');
    if (qptSave) qptSave.onclick = saveCurrentPowerRecord;
    const qptClear = document.getElementById('qptClearRecordsBtn');
    if (qptClear)
      qptClear.onclick = async () => {
        await persistSavedPowerRecords({});
        showToast('已清空档位功耗对比记录');
        render();
      };
    document.querySelectorAll('.qpt-del-btn').forEach((btn) => {
      btn.onclick = async () => {
        const id = Number.parseInt(btn.dataset.setId, 10);
        const next = { ...state.savedPowerRecords };
        delete next[id];
        await persistSavedPowerRecords(next);
        render();
      };
    });

    const capBtn = document.getElementById('captureBaselineBtn');
    if (capBtn) capBtn.onclick = captureStockBaselineManual;
    document.querySelectorAll('.filter-chip').forEach((chip) => {
      chip.onclick = () => {
        const v = chip.dataset.filter;
        state.clusterFilter = v === 'all' ? null : Number.parseInt(v, 10);
        render();
      };
    });

    const copyQ = document.getElementById('copyQualityBtn');
    if (copyQ && snap && snap.qualityReport) {
      copyQ.onclick = () => {
        const qr = snap.qualityReport;
        const lines = [
          `VoltSet 体质电压表 · ${snap.deviceModel}`,
          `SCMI 计划电压 · 综合参考分 ${qr.score}`,
          `读表来源：${snap.scmiSource === 'ENERGY_MODEL' ? 'debugfs/energy_model 零模块' : 'qcom-scmi-ro.ko'}`,
        ];
        if (snap.bootCpussIndex !== null) {
          lines.push(`开机档位 Set ${snap.bootCpussIndex}（${snap.bootSet.firmwareIndex === null ? '推定' : '固件锁定'}）`);
        }
        for (const cl of qr.clusters) {
          lines.push('', `PDP${cl.domain} · ${cl.score} 分 · C=${cl.coefficient}`);
          for (const pt of qr.points.filter((p) => p.domain === cl.domain)) {
            lines.push(`${C.formatFreqMhz(pt.freqKhz)}  ${pt.voltageMv} mV  ${pt.powerUw} µW`);
          }
        }
        copyText(lines.join('\n'), '已复制体质电压表');
      };
    }

    const compatRef = document.getElementById('compatRefreshBtn');
    if (compatRef) compatRef.onclick = refresh;
    const copyCompat = document.getElementById('copyCompatBtn');
    if (copyCompat && snap) {
      copyCompat.onclick = () => {
        copyText(C.formatTextReport(snap, APP_VERSION), '已复制设备兼容性报告到剪贴板');
      };
    }
  }

  async function copyText(text, successMsg) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        showToast(successMsg);
        return;
      }
    } catch (_) {}
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      showToast(successMsg);
    } catch (_) {
      showToast('复制失败，请手动长按选择');
    }
    document.body.removeChild(ta);
  }

  document.getElementById('refreshBtn').onclick = refresh;
  document.querySelectorAll('.nav-btn').forEach((btn) => {
    btn.onclick = () => {
      const nextTab = btn.dataset.tab;
      if (nextTab !== 'power') stopQptMonitor();
      state.tab = nextTab;
      render();
    };
  });
  document.getElementById('modalBackdrop').onclick = (e) => {
    if (e.target && e.target.id === 'modalBackdrop') closeModal();
  };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopQptMonitor();
  });
  window.addEventListener('pagehide', stopQptMonitor);

  refresh();
})();
