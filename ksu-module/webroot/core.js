(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  root.VoltSetCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const CFDB_MAGIC = 0x0003dbcf;
  const CFDB_HEADER_SIZE = 16;
  const CFDB_ENTRY_SIZE = 16;
  const DCVS_RECORD_SIZE = 0x9c;
  const SENTINEL_CORNER = 0x7fffffff;
  const PDP_CDB_RECORD_SIZE = 13;
  const HASH_OVERRIDE = 0xb47a3c0b;
  const HASH_CLUS0 = 0x0e675698;
  const HASH_CLUS1 = 0x7ac09099;

  function u16Le(bytes, offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  function i16Le(bytes, offset) {
    const v = u16Le(bytes, offset);
    return (v & 0x8000) !== 0 ? v - 0x10000 : v;
  }

  function u32Le(bytes, offset) {
    return (
      (bytes[offset] & 0xff) |
      ((bytes[offset + 1] & 0xff) << 8) |
      ((bytes[offset + 2] & 0xff) << 16) |
      ((bytes[offset + 3] & 0xff) << 24)
    ) >>> 0;
  }

  function parseBackendOutput(output) {
    const values = Object.create(null);
    const blocks = Object.create(null);
    let activeBlock = null;
    let linesBuf = [];
    for (const rawLine of String(output || '').split(/\r?\n/)) {
      if (activeBlock !== null) {
        if (rawLine === `${activeBlock}_END`) {
          blocks[activeBlock] = linesBuf.join('\n') + (linesBuf.length ? '\n' : '');
          linesBuf = [];
          activeBlock = null;
        } else {
          linesBuf.push(rawLine);
        }
      } else {
        const eq = rawLine.indexOf('=');
        if (eq > 0) {
          values[rawLine.slice(0, eq)] = rawLine.slice(eq + 1);
        } else if (rawLine.endsWith('_BEGIN')) {
          activeBlock = rawLine.slice(0, -'_BEGIN'.length);
        }
      }
    }
    return { values, blocks };
  }

  function base64ToBytes(b64) {
    const clean = String(b64 || '').replace(/\s+/g, '');
    if (!clean) return new Uint8Array(0);
    if (typeof Buffer === 'function' && typeof process !== 'undefined' && process.versions && process.versions.node) {
      return new Uint8Array(Buffer.from(clean, 'base64'));
    }
    const bin = atob(clean);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      out[i] = bin.charCodeAt(i);
    }
    return out;
  }

  async function gunzipBytes(bytes) {
    if (typeof process !== 'undefined' && process.versions && process.versions.node) {
      // eslint-disable-next-line global-require
      const zlib = require('zlib');
      return new Uint8Array(zlib.gunzipSync(bytes));
    }
    if (typeof DecompressionStream === 'function') {
      const ds = new DecompressionStream('gzip');
      const stream = new Blob([bytes]).stream().pipeThrough(ds);
      const buf = await new Response(stream).arrayBuffer();
      return new Uint8Array(buf);
    }
    throw new Error('当前环境缺少 gzip 解压支持');
  }

  async function decodeBlockBytes(blocks, gzKey, rawKey) {
    if (blocks[gzKey]) {
      const compressed = base64ToBytes(blocks[gzKey]);
      return gunzipBytes(compressed);
    }
    if (blocks[rawKey]) {
      return base64ToBytes(blocks[rawKey]);
    }
    return null;
  }

  // ---- CFDB v3 ----
  function locateCfdb(bytes, label) {
    if (u32Le(bytes, 0) === CFDB_MAGIC) {
      return { cfdbOffset: 0, cfdbLength: bytes.length };
    }
    if (
      bytes.length < 52 ||
      bytes[0] !== 0x7f ||
      bytes[1] !== 0x45 ||
      bytes[2] !== 0x4c ||
      bytes[3] !== 0x46 ||
      bytes[4] !== 1 ||
      bytes[5] !== 1
    ) {
      throw new Error(`无法识别的 ${label} 格式（非 ELF32 LE 且非 CFDB）`);
    }
    const phoff = u32Le(bytes, 28);
    const phentsize = u16Le(bytes, 42);
    const phnum = u16Le(bytes, 44);
    if (phentsize < 32 || phoff + phnum * phentsize > bytes.length) {
      throw new Error('ELF32 Program Header 越界');
    }
    for (let i = 0; i < phnum; i++) {
      const ph = phoff + i * phentsize;
      const pType = u32Le(bytes, ph);
      if (pType !== 1) continue;
      const offset = u32Le(bytes, ph + 4);
      const filesz = u32Le(bytes, ph + 16);
      if (filesz < CFDB_HEADER_SIZE || offset + filesz > bytes.length) continue;
      if (u32Le(bytes, offset) === CFDB_MAGIC) {
        return { cfdbOffset: offset, cfdbLength: filesz };
      }
    }
    throw new Error('ELF32 中未找到包含 CFDB v3 的 PT_LOAD 段');
  }

  function parseCfdb(bytes, label, maxEntryCount = 16384) {
    if (!bytes || bytes.length < CFDB_HEADER_SIZE) {
      throw new Error(`${label} 数据长度不足 (${bytes ? bytes.length : 0} B)`);
    }
    const { cfdbOffset, cfdbLength } = locateCfdb(bytes, label);
    if (cfdbOffset < 0 || cfdbLength < CFDB_HEADER_SIZE || cfdbOffset + cfdbLength > bytes.length) {
      throw new Error(`CFDB 段边界异常 (offset=${cfdbOffset}, len=${cfdbLength}, total=${bytes.length})`);
    }
    if (u32Le(bytes, cfdbOffset) !== CFDB_MAGIC) {
      throw new Error('缺少 CFDB v3 魔数');
    }
    const entryCount = u32Le(bytes, cfdbOffset + 4);
    if (entryCount > maxEntryCount) {
      throw new Error(`CFDB 目录项数量异常 (${entryCount})`);
    }
    if (CFDB_HEADER_SIZE + entryCount * CFDB_ENTRY_SIZE > cfdbLength) {
      throw new Error('CFDB 目录表超出段长度');
    }
    const entries = [];
    for (let i = 0; i < entryCount; i++) {
      const base = cfdbOffset + CFDB_HEADER_SIZE + i * CFDB_ENTRY_SIZE;
      const hash = u32Le(bytes, base);
      const valueOrOffset = u32Le(bytes, base + 4);
      const typeAndSize = u32Le(bytes, base + 8);
      entries.push({
        hash,
        valueOrOffset,
        type: typeAndSize & 0xff,
        size: typeAndSize >>> 8,
      });
    }
    return {
      entries,
      sliceBlob(entry) {
        const rel = entry.valueOrOffset;
        if (entry.size < 0 || rel + entry.size > cfdbLength) {
          throw new Error(`CFDB Blob 越界 (rel=${rel}, size=${entry.size})`);
        }
        return bytes.subarray(cfdbOffset + rel, cfdbOffset + rel + entry.size);
      },
    };
  }

  // ---- pdp_cdb ----
  function adjustModeFromCode(code) {
    if (code === 1) return 'FREQ';
    if (code === 2) return 'PSTATE';
    if (code === 3) return 'ALL';
    return 'UNKNOWN';
  }

  function parseRecords(blob) {
    const out = [];
    let pos = 0;
    while (pos + PDP_CDB_RECORD_SIZE <= blob.length) {
      const setId = u32Le(blob, pos);
      if (setId === 0) break;
      const rawModeCode = blob[pos + 4] & 0xff;
      const value = u32Le(blob, pos + 5);
      const cxMv = i16Le(blob, pos + 9);
      const mxMv = i16Le(blob, pos + 11);
      out.push({
        setId,
        mode: adjustModeFromCode(rawModeCode),
        rawModeCode,
        value,
        cxMv,
        mxMv,
      });
      pos += PDP_CDB_RECORD_SIZE;
    }
    return out;
  }

  function summarizeClusterSet(cluster, records) {
    let allCx = 0;
    let allMx = 0;
    const specifics = new Map();
    for (const rec of records) {
      if (rec.mode === 'ALL') {
        allCx += rec.cxMv;
        allMx += rec.mxMv;
      } else if (rec.mode === 'FREQ' || rec.mode === 'PSTATE') {
        const key = `${rec.mode}:${rec.value}`;
        const prev = specifics.get(key);
        if (!prev) {
          specifics.set(key, { mode: rec.mode, value: rec.value, cx: rec.cxMv, mx: rec.mxMv });
        } else {
          prev.cx += rec.cxMv;
          prev.mx += rec.mxMv;
        }
      }
    }
    const modeOrder = { FREQ: 1, PSTATE: 2 };
    const entries = Array.from(specifics.values())
      .sort((a, b) => (modeOrder[a.mode] - modeOrder[b.mode]) || (a.value - b.value))
      .map((item) => ({
        mode: item.mode,
        value: item.value,
        rawDeltaCxMv: item.cx,
        rawDeltaMxMv: item.mx,
        effectiveCxMv: allCx + item.cx,
        effectiveMxMv: allMx + item.mx,
      }));
    let minCx = allCx;
    let maxCx = allCx;
    let minMx = allMx;
    let maxMx = allMx;
    for (const e of entries) {
      if (e.effectiveCxMv < minCx) minCx = e.effectiveCxMv;
      if (e.effectiveCxMv > maxCx) maxCx = e.effectiveCxMv;
      if (e.effectiveMxMv < minMx) minMx = e.effectiveMxMv;
      if (e.effectiveMxMv > maxMx) maxMx = e.effectiveMxMv;
    }
    return {
      cluster,
      allCxMv: allCx,
      allMxMv: allMx,
      entries,
      minCxMv: minCx,
      maxCxMv: maxCx,
      minMxMv: minMx,
      maxMxMv: maxMx,
      isUniform: entries.length === 0,
      isNoOp: minCx === 0 && maxCx === 0 && minMx === 0 && maxMx === 0,
      predictedCxOffsetForFreq(freqKhz) {
        const exact = entries.find((e) => e.mode === 'FREQ' && e.value === freqKhz);
        return exact ? exact.effectiveCxMv : allCx;
      },
      predictedCxOffsetForBin(freqKhz, levelIndex) {
        let delta = allCx;
        for (const e of entries) {
          if (e.mode === 'FREQ' && e.value === freqKhz) delta += e.rawDeltaCxMv;
          else if (e.mode === 'PSTATE' && e.value === levelIndex) delta += e.rawDeltaCxMv;
        }
        return delta;
      },
    };
  }

  function parsePdpCdb(bytes) {
    const doc = parseCfdb(bytes, 'pdp_cdb', 4096);
    let overrideSetId = null;
    let clus0Blob = null;
    let clus1Blob = null;
    for (const entry of doc.entries) {
      if (entry.hash === HASH_OVERRIDE && entry.type === 0) {
        overrideSetId = entry.valueOrOffset;
      } else if (entry.hash === HASH_CLUS0 && entry.type === 2) {
        clus0Blob = doc.sliceBlob(entry);
      } else if (entry.hash === HASH_CLUS1 && entry.type === 2) {
        clus1Blob = doc.sliceBlob(entry);
      }
    }
    const clus0Records = clus0Blob ? parseRecords(clus0Blob) : [];
    const clus1Records = clus1Blob ? parseRecords(clus1Blob) : [];
    const idSet = new Set([1, 2, 3, 4, 5, 6, 7]);
    for (const r of clus0Records) if (r.setId > 0) idSet.add(r.setId);
    for (const r of clus1Records) if (r.setId > 0) idSet.add(r.setId);
    const observedIds = Array.from(idSet).sort((a, b) => a - b);

    const sets = observedIds.map((setId) => {
      const c0 = summarizeClusterSet(0, clus0Records.filter((r) => r.setId === setId));
      const c1 = summarizeClusterSet(1, clus1Records.filter((r) => r.setId === setId));
      let tag = 'CURVE';
      if (setId === 1 || (c0.isNoOp && c1.isNoOp)) tag = 'DEFAULT';
      else if (c0.isUniform && c1.isUniform) tag = 'UNIFORM';
      return {
        setId,
        clus0: c0,
        clus1: c1,
        tag,
        isAggressive: Math.min(c0.minCxMv, c1.minCxMv) <= -20,
        predictedCxOffsetForFreq(cluster, freqKhz) {
          if (cluster === 0) return c0.predictedCxOffsetForFreq(freqKhz);
          if (cluster === 1) return c1.predictedCxOffsetForFreq(freqKhz);
          return null;
        },
        predictedCxOffsetForBin(cluster, freqKhz, levelIndex) {
          if (cluster === 0) return c0.predictedCxOffsetForBin(freqKhz, levelIndex);
          if (cluster === 1) return c1.predictedCxOffsetForBin(freqKhz, levelIndex);
          return null;
        },
      };
    });
    return { overrideSetId, clus0Records, clus1Records, sets };
  }

  // ---- pdp firmware limits ----
  function parseDcvsBlob(blob) {
    const count = Math.floor(blob.length / DCVS_RECORD_SIZE);
    if (count < 2) return null;
    const tailOffset = (count - 1) * DCVS_RECORD_SIZE;
    if (u32Le(blob, tailOffset) !== SENTINEL_CORNER) return null;
    const entries = [];
    for (let i = 0; i < count - 1; i++) {
      const base = i * DCVS_RECORD_SIZE;
      const corner = u32Le(blob, base);
      if (corner < 1 || corner > 2048) return null;
      const flags = [];
      for (let bit = 0; bit < 8; bit++) {
        const rawFlag = blob[base + 6 + bit] & 0xff;
        if (rawFlag !== 0 && rawFlag !== 1) return null;
        flags.push(rawFlag === 1);
      }
      const freqKhz = u32Le(blob, base + 0x10);
      if (freqKhz < 100000 || freqKhz > 7000000) return null;
      const cxCeilMv = i16Le(blob, base + 0x7c);
      const cxFloorMv = i16Le(blob, base + 0x7e);
      if (cxFloorMv < 300 || cxFloorMv > 1600 || cxCeilMv < cxFloorMv || cxCeilMv > 1600) return null;
      const olvAcdMarginMv = i16Le(blob, base + 0x84);
      const mxCeilMv = i16Le(blob, base + 0x8c);
      const mxFloorMv = i16Le(blob, base + 0x8e);
      entries.push({
        corner,
        enableFlags: flags,
        limit: { freqKhz, cxFloorMv, cxCeilMv, olvAcdMarginMv, mxFloorMv, mxCeilMv },
      });
    }
    return { entries };
  }

  function parsePdpFirmware(bytes) {
    const doc = parseCfdb(bytes, 'pdp', 16384);
    const seen = new Set();
    const tables = [];
    for (const entry of doc.entries) {
      if (entry.type !== 2 || entry.size < 2 * DCVS_RECORD_SIZE || entry.size % DCVS_RECORD_SIZE !== 0) continue;
      if (seen.has(entry.valueOrOffset)) continue;
      seen.add(entry.valueOrOffset);
      const table = parseDcvsBlob(doc.sliceBlob(entry));
      if (table) tables.push(table);
    }
    if (!tables.length) {
      throw new Error('pdp 固件中未找到有效的 DCVS 硬件限位表');
    }
    return tables;
  }

  function buildLimitCandidate(matchedEntries, sortedPoints, extraCount) {
    let inBounds = 0;
    for (let i = 0; i < matchedEntries.length; i++) {
      const lim = matchedEntries[i].limit;
      const mv = sortedPoints[i].voltageMv;
      if (mv > 0 && mv >= lim.cxFloorMv && mv <= lim.cxCeilMv) inBounds++;
    }
    const limits = matchedEntries.map((e) => e.limit);
    return {
      limits,
      signature: limits.map((l) => `${l.cxFloorMv}:${l.cxCeilMv}`).join('|'),
      inBoundsCount: inBounds,
      extraCount,
    };
  }

  function resolveUnambiguous(candidates, domain, exactTableMatch) {
    if (!candidates.length) return null;
    const minExtra = Math.min(...candidates.map((c) => c.extraCount));
    const narrowest = candidates.filter((c) => c.extraCount === minExtra);
    const bestInBounds = Math.max(...narrowest.map((c) => c.inBoundsCount));
    const top = narrowest.filter((c) => c.inBoundsCount === bestInBounds);
    const sig = top[0].signature;
    if (top.some((c) => c.signature !== sig)) return null;
    const limitsByFreqKhz = new Map();
    for (const lim of top[0].limits) limitsByFreqKhz.set(lim.freqKhz, lim);
    return { domain, exactTableMatch, limitsByFreqKhz };
  }

  function matchClusterLimits(tables, domain, clusterPoints) {
    if (!clusterPoints.length || !tables.length) return null;
    const sortedPoints = clusterPoints.slice().sort((a, b) => a.freqKhz - b.freqKhz);
    const targetFreqs = sortedPoints.map((p) => p.freqKhz);
    const targetKey = targetFreqs.join(',');

    const exactCandidates = [];
    for (const table of tables) {
      for (let flexId = 0; flexId < 8; flexId++) {
        const active = table.entries.filter((e) => e.enableFlags[flexId]);
        if (active.map((e) => e.limit.freqKhz).join(',') !== targetKey) continue;
        exactCandidates.push(buildLimitCandidate(active, sortedPoints, 0));
      }
    }
    const exactResolved = resolveUnambiguous(exactCandidates, domain, true);
    if (exactResolved) return exactResolved;
    if (exactCandidates.length) return null;

    const subsetCandidates = [];
    for (const table of tables) {
      for (let flexId = 0; flexId < 8; flexId++) {
        const active = table.entries.filter((e) => e.enableFlags[flexId]);
        if (active.length < targetFreqs.length) continue;
        const byFreq = new Map();
        for (const e of active) byFreq.set(e.limit.freqKhz, e);
        const matched = targetFreqs.map((f) => byFreq.get(f)).filter(Boolean);
        if (matched.length !== targetFreqs.length) continue;
        subsetCandidates.push(buildLimitCandidate(matched, sortedPoints, active.length - targetFreqs.length));
      }
    }
    return resolveUnambiguous(subsetCandidates, domain, false);
  }

  function matchDeviceLimits(tables, baselinePoints, livePoints) {
    const pointsFor = (domain) => {
      const base = (baselinePoints || []).filter((p) => p.domain === domain);
      if (base.length) return base;
      return livePoints.filter((p) => p.domain === domain);
    };
    const clus0 = matchClusterLimits(tables, 0, pointsFor(0));
    const clus1 = matchClusterLimits(tables, 1, pointsFor(1));
    return {
      totalDcvsTablesFound: tables.length,
      clus0,
      clus1,
      isComplete: Boolean(clus0 && clus1),
    };
  }

  // ---- CDT ----
  function inspectCdt(bytes) {
    if (!bytes || !bytes.length) {
      return {
        state: 'UNAVAILABLE',
        version: null,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: '未能读取 CDT 分区',
      };
    }
    let allZero = true;
    for (let i = 0; i < bytes.length; i++) {
      if (bytes[i] !== 0) {
        allZero = false;
        break;
      }
    }
    if (allZero) {
      return {
        state: 'EMPTY',
        version: null,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: 'CDT 分区全零（原厂默认，Key 7 未开启）',
      };
    }
    const hasMagic = bytes.length >= 18 && bytes[0] === 0x43 && bytes[1] === 0x44 && bytes[2] === 0x54 && bytes[3] === 0x00;
    if (!hasMagic) {
      return { state: 'CORRUPT', version: null, key7Value: null, keyPairs: [], key7Enabled: false, detail: 'CDT 头魔数无效' };
    }
    const off0 = u16Le(bytes, 14);
    const size0 = u16Le(bytes, 16);
    if (off0 < 14 || size0 < 6 || off0 + size0 > bytes.length) {
      return {
        state: 'CORRUPT',
        version: null,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: `CDT Platform Info 块越界 (off=${off0}, size=${size0})`,
      };
    }
    const version = bytes[off0] & 0xff;
    if (version === 6 && size0 === 9) {
      return {
        state: 'STOCK',
        version,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: '原厂精简 v6 CDT（9 字节，Key 7 未开启）',
      };
    }
    const hdrLen = version === 3 ? 6 : version >= 6 ? 10 : 7;
    if (size0 < hdrLen) {
      return {
        state: 'CORRUPT',
        version: null,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: `CDT v${version} 块头长度不足 (${size0} < ${hdrLen})`,
      };
    }
    const keyCount = bytes[off0 + hdrLen - 1] & 0xff;
    if (hdrLen + keyCount * 2 > size0) {
      return {
        state: 'CORRUPT',
        version: null,
        key7Value: null,
        keyPairs: [],
        key7Enabled: false,
        detail: `CDT v${version} 键值数组越界 (keyCount=${keyCount}, size=${size0})`,
      };
    }
    const pairs = [];
    let key7Value = null;
    for (let i = 0; i < keyCount; i++) {
      const k = bytes[off0 + hdrLen + i * 2] & 0xff;
      const v = bytes[off0 + hdrLen + i * 2 + 1] & 0xff;
      pairs.push([k, v]);
      if (k === 7) key7Value = v;
    }
    if (key7Value !== null && key7Value !== 0) {
      return {
        state: 'SLT_ENABLED',
        version,
        key7Value,
        keyPairs: pairs,
        key7Enabled: true,
        detail: `检测到 CDT v${version} Key 7=${key7Value}（SLT+ACD 已开启，将与 OEM Set 叠加！）`,
      };
    }
    return {
      state: 'STOCK',
      version,
      key7Value,
      keyPairs: pairs,
      key7Enabled: false,
      detail: `原厂 v${version} CDT（Key 7=${key7Value !== null ? key7Value : '无'}，未开启）`,
    };
  }

  // ---- SCMI ----
  function powerCoefficient(domain, isSm8850) {
    if (domain === 0) return 100;
    if (domain === 1) return isSm8850 ? 150 : 136;
    throw new Error(`不支持的 SCMI domain: ${domain}`);
  }

  function estimateVoltage(powerUw, freqKhz, coefficient) {
    return Math.sqrt((powerUw * 1000000000.0) / (coefficient * freqKhz));
  }

  function powerForVoltage(freqKhz, voltageMv, coefficient) {
    return Math.floor((coefficient * freqKhz * voltageMv * voltageMv) / 1000000000);
  }

  function parseScmiCsv(csv, isSm8850, coefficients = {}) {
    const byKey = new Map();
    for (const rawLine of String(csv || '').split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#') || line.startsWith('domain,')) continue;
      const cols = line.split(',');
      if (cols.length < 7) continue;
      const domain = Number.parseInt(cols[0].trim(), 10);
      if (domain !== 0 && domain !== 1) continue;
      const powerUw = Number.parseInt(cols[3].trim(), 10);
      const freqKhz = Number.parseInt(cols[5].trim(), 10);
      const levelIndex = Number.parseInt(cols[6].trim(), 10);
      if (!Number.isFinite(freqKhz) || !Number.isFinite(powerUw) || !Number.isFinite(levelIndex) || freqKhz <= 0 || powerUw <= 0) {
        continue;
      }
      const verified = coefficients[domain];
      const coeff = verified !== undefined ? verified : powerCoefficient(domain, isSm8850);
      const rawMv = estimateVoltage(powerUw, freqKhz, coeff);
      const step = verified !== undefined ? 1 : 4;
      const roundedMv = Math.round(rawMv / step) * step;
      byKey.set(`${domain}:${freqKhz}`, {
        domain,
        levelIndex,
        freqKhz,
        powerUw,
        voltageMv: roundedMv,
        exactFormulaMatch: powerForVoltage(freqKhz, roundedMv, coeff) === powerUw,
      });
    }
    return Array.from(byKey.values()).sort((a, b) => a.domain - b.domain || a.freqKhz - b.freqKhz);
  }

  function uniqueVoltage(row, coefficient) {
    if (row.freqKhz < 1 || row.powerUw < 1 || row.powerUw > 0xffffffff) return null;
    const estimate = Math.round(estimateVoltage(row.powerUw, row.freqKhz, coefficient));
    if (estimate < 299 || estimate > 1301) return null;
    const matches = [];
    for (let mv = estimate - 2; mv <= estimate + 2; mv++) {
      if (powerForVoltage(row.freqKhz, mv, coefficient) === row.powerUw) matches.push(mv);
    }
    if (matches.length !== 1) return null;
    const v = matches[0];
    return v >= 300 && v <= 1300 ? v : null;
  }

  function solveClusterQuality(rows, domain) {
    const candidates = [];
    for (let coeff = 80; coeff <= 200; coeff++) {
      const voltages = [];
      let ok = true;
      for (const r of rows) {
        const v = uniqueVoltage(r, coeff);
        if (v === null) {
          ok = false;
          break;
        }
        voltages.push(v);
      }
      if (!ok) continue;
      let monotonic = true;
      for (let i = 1; i < voltages.length; i++) {
        if (voltages[i - 1] > voltages[i]) {
          monotonic = false;
          break;
        }
      }
      if (monotonic) candidates.push({ coefficient: coeff, voltages });
    }
    const aligned = candidates.filter((c) => c.voltages.every((v) => v % 4 === 0));
    const preferred = aligned.length ? aligned : candidates;
    if (preferred.length === 1) return preferred[0];
    const smCoeff = powerCoefficient(domain, true);
    return preferred.find((c) => c.coefficient === smCoeff) || null;
  }

  function scmiPointsEqual(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      if (
        a[i].domain !== b[i].domain ||
        a[i].levelIndex !== b[i].levelIndex ||
        a[i].freqKhz !== b[i].freqKhz ||
        a[i].powerUw !== b[i].powerUw ||
        a[i].voltageMv !== b[i].voltageMv ||
        a[i].exactFormulaMatch !== b[i].exactFormulaMatch
      ) {
        return false;
      }
    }
    return true;
  }

  function evaluateQualityReport(first, second) {
    if (!first.length || !scmiPointsEqual(first, second)) return null;
    const clusters = [];
    const points = [];
    const coefficients = {};
    for (const domain of [0, 1]) {
      const rows = first.filter((r) => r.domain === domain);
      if (rows.length < 2) return null;
      const sortedIdx = rows.map((r) => r.levelIndex).sort((a, b) => a - b);
      for (let i = 0; i < sortedIdx.length; i++) {
        if (sortedIdx[i] !== i) return null;
      }
      const solved = solveClusterQuality(rows, domain);
      if (!solved) return null;
      const { coefficient, voltages } = solved;
      let sum = 0;
      for (let i = 0; i < voltages.length; i++) {
        sum += (1100 - voltages[i]) * (i === voltages.length - 1 ? 95 : 5);
      }
      const score = Math.trunc(sum / voltages.length);
      clusters.push({ domain, coefficient, score });
      coefficients[domain] = coefficient;
      for (let i = 0; i < rows.length; i++) {
        points.push({ ...rows[i], voltageMv: voltages[i], exactFormulaMatch: true });
      }
    }
    const score = Math.trunc(clusters.reduce((acc, c) => acc + c.score, 0) / clusters.length);
    return { clusters, points, score, coefficients };
  }

  function diffScmi(current, baseline, activeSet) {
    const baselineByKey = new Map();
    if (baseline) {
      for (const p of baseline) baselineByKey.set(`${p.domain}:${p.freqKhz}`, p);
    }
    return current.map((point) => {
      const base = baselineByKey.get(`${point.domain}:${point.freqKhz}`) || null;
      const deltaMv =
        base && point.exactFormulaMatch && base.exactFormulaMatch ? point.voltageMv - base.voltageMv : null;
      return {
        domain: point.domain,
        freqKhz: point.freqKhz,
        currentMv: point.voltageMv,
        baselineMv: base ? base.voltageMv : null,
        deltaMv,
        expectedCdbCxMv: activeSet ? activeSet.predictedCxOffsetForFreq(point.domain, point.freqKhz) : null,
      };
    });
  }

  function summarizeByCluster(rows, domain) {
    const filtered = rows.filter((r) => r.domain === domain);
    const deltas = filtered.map((r) => r.deltaMv).filter((d) => d !== null && d !== undefined);
    return {
      domain,
      totalBins: filtered.length,
      loweredBins: deltas.filter((d) => d < 0).length,
      unchangedBins: deltas.filter((d) => d === 0).length,
      raisedBins: deltas.filter((d) => d > 0).length,
      minDeltaMv: deltas.length ? Math.min(...deltas) : null,
      maxDeltaMv: deltas.length ? Math.max(...deltas) : null,
    };
  }

  // ---- VoltageSetEstimator ----
  function estimateCluster(summary, baseline, limits) {
    if (!baseline.length) return null;
    const sorted = baseline.slice().sort((a, b) => a.freqKhz - b.freqKhz);
    if (sorted.some((p) => !p.exactFormulaMatch || !limits.limitsByFreqKhz.has(p.freqKhz))) {
      return null;
    }
    let prevMv = 0;
    let hasRoundingVariance = false;
    let floorClampedBins = 0;
    let monotonicityClampedBins = 0;
    let ceilingRiskBins = 0;
    let loweredBins = 0;
    const bins = [];
    for (const point of sorted) {
      const lim = limits.limitsByFreqKhz.get(point.freqKhz);
      const offset = summary.predictedCxOffsetForBin(point.freqKhz, point.levelIndex);
      if (offset % 4 !== 0) hasRoundingVariance = true;
      const atStockCeiling = point.voltageMv >= lim.cxCeilMv && offset < 0;
      if (atStockCeiling) ceilingRiskBins++;
      const raw = point.voltageMv + offset;
      const clampedByFloor = raw < lim.cxFloorMv;
      if (clampedByFloor) floorClampedBins++;
      const clamped = Math.min(Math.max(raw, lim.cxFloorMv), lim.cxCeilMv);
      const rem = clamped & 3;
      const aligned = rem !== 0 ? clamped + 4 - rem : clamped;
      const clampedByMonotonicity = prevMv > 0 && aligned < prevMv;
      if (clampedByMonotonicity) monotonicityClampedBins++;
      const finalMv = clampedByMonotonicity ? prevMv : aligned;
      prevMv = finalMv;
      const delta = finalMv - point.voltageMv;
      if (delta < 0) loweredBins++;
      bins.push({
        freqKhz: point.freqKhz,
        baselineMv: point.voltageMv,
        estimatedMv: finalMv,
        deltaMv: delta,
        cdbOffsetMv: offset,
        floorMv: lim.cxFloorMv,
        ceilMv: lim.cxCeilMv,
        clampedByFloor,
        clampedByMonotonicity,
        atStockCeiling,
      });
    }
    return {
      domain: limits.domain,
      totalBins: bins.length,
      loweredBins,
      minDeltaMv: Math.min(...bins.map((b) => b.deltaMv)),
      maxDeltaMv: Math.max(...bins.map((b) => b.deltaMv)),
      floorClampedBins,
      monotonicityClampedBins,
      ceilingRiskBins,
      hasRoundingVariance,
      bins,
    };
  }

  function estimateAllSets(sets, baselinePoints, limits) {
    if (!baselinePoints || !baselinePoints.length || !limits || !sets.length) return {};
    const out = {};
    for (const set of sets) {
      const est0 = limits.clus0 ? estimateCluster(set.clus0, baselinePoints.filter((p) => p.domain === 0), limits.clus0) : null;
      const est1 = limits.clus1 ? estimateCluster(set.clus1, baselinePoints.filter((p) => p.domain === 1), limits.clus1) : null;
      if (est0 || est1) {
        out[set.setId] = { setId: set.setId, clus0: est0, clus1: est1 };
      }
    }
    return out;
  }

  // ---- BootSetStatus ----
  function computeBootSetStatus({ firstSdamIndex = null, previousTargetIndex = null, stagedThisBootIndex = null, firmwareIndex = null }) {
    const rebootReadbackMismatch =
      previousTargetIndex !== null && firstSdamIndex !== null && previousTargetIndex !== firstSdamIndex;
    const runningIndex =
      firmwareIndex !== null
        ? firmwareIndex
        : firstSdamIndex !== null && firstSdamIndex >= 1 && firstSdamIndex <= 7 && !rebootReadbackMismatch
        ? firstSdamIndex
        : null;
    function pendingIndex(liveSdamIndex) {
      if (firmwareIndex !== null || liveSdamIndex === null || liveSdamIndex < 1 || liveSdamIndex > 7) return null;
      if (runningIndex !== null) return liveSdamIndex !== runningIndex ? liveSdamIndex : null;
      return liveSdamIndex === stagedThisBootIndex ? liveSdamIndex : null;
    }
    return {
      firstSdamIndex,
      previousTargetIndex,
      stagedThisBootIndex,
      firmwareIndex,
      rebootReadbackMismatch,
      runningIndex,
      pendingIndex,
    };
  }

  // ---- QPT Power Monitor ----
  function parseQptSample(output, timestampNs) {
    const energyUjByRail = Object.create(null);
    const powerUwByRail = Object.create(null);
    let cpu0FreqKhz = 0;
    let cpu1FreqKhz = 0;
    let gpuFreqHz = 0;
    for (const rawLine of String(output || '').split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;
      const eq = line.indexOf('=');
      if (eq <= 0) continue;
      const key = line.slice(0, eq).trim();
      const value = line.slice(eq + 1).trim();
      if (key === 'QPT_RAIL' || key === 'QPT_POWER_UW') {
        const colon = value.indexOf(':');
        if (colon > 0) {
          const rail = value.slice(0, colon).trim();
          const num = Number(value.slice(colon + 1).trim());
          if (rail && !rail.startsWith('debug-') && Number.isFinite(num) && num >= 0) {
            if (key === 'QPT_RAIL') energyUjByRail[rail] = num;
            else powerUwByRail[rail] = num;
          }
        }
      } else if (key === 'FREQ_CPU0_KHZ') {
        const n = Number.parseInt(value, 10);
        if (Number.isFinite(n) && n > 0) cpu0FreqKhz = n;
      } else if (key === 'FREQ_CPU1_KHZ') {
        const n = Number.parseInt(value, 10);
        if (Number.isFinite(n) && n > 0) cpu1FreqKhz = n;
      } else if (key === 'FREQ_GPU_HZ') {
        const n = Number.parseInt(value, 10);
        if (Number.isFinite(n) && n > 0) gpuFreqHz = n;
      }
    }
    return { timestampNs, energyUjByRail, powerUwByRail, cpu0FreqKhz, cpu1FreqKhz, gpuFreqHz };
  }

  function computeQptDelta(prev, curr) {
    const dtNs = curr.timestampNs - prev.timestampNs;
    if (dtNs < 50000000) return null;
    const railPowerMw = (name) => {
      const e1 = prev.energyUjByRail[name];
      const e2 = curr.energyUjByRail[name];
      if (e1 === undefined || e2 === undefined || e2 < e1) return null;
      if (e2 === e1) {
        const uw = curr.powerUwByRail && curr.powerUwByRail[name];
        if (uw !== undefined && uw > 0) return uw / 1000.0;
      }
      return ((e2 - e1) * 1000000.0) / dtNs;
    };
    const cpuMidMw = railPowerMw('cpu-m');
    const cpuLargeMw = railPowerMw('cpu-l');
    const gpuMw = railPowerMw('gpu');
    const nspMw = railPowerMw('nsp');
    if (cpuMidMw === null && cpuLargeMw === null && gpuMw === null && nspMw === null) return null;
    const cpuTotalMw = cpuMidMw === null && cpuLargeMw === null ? null : (cpuMidMw || 0) + (cpuLargeMw || 0);
    const socTotalMw = (cpuMidMw || 0) + (cpuLargeMw || 0) + (gpuMw || 0) + (nspMw || 0);
    return {
      cpuMidMw,
      cpuLargeMw,
      gpuMw,
      nspMw,
      cpuTotalMw,
      socTotalMw,
      dtMs: Math.trunc(dtNs / 1000000),
      cpu0FreqKhz: curr.cpu0FreqKhz || 0,
      cpu1FreqKhz: curr.cpu1FreqKhz || 0,
      gpuFreqHz: curr.gpuFreqHz || 0,
    };
  }

  function summarizeQptSession(samples) {
    if (!samples || !samples.length) return null;
    const totalMs = samples.reduce((acc, s) => acc + Math.max(s.dtMs || 0, 1), 0);
    const weightedMean = (fn) => {
      let sum = 0;
      let weight = 0;
      for (const s of samples) {
        const v = fn(s);
        if (v === null || v === undefined) continue;
        const w = Math.max(s.dtMs || 0, 1);
        sum += v * w;
        weight += w;
      }
      return weight > 0 ? sum / weight : null;
    };
    return {
      sampleCount: samples.length,
      elapsedSec: totalMs / 1000.0,
      avgCpuMidMw: weightedMean((s) => s.cpuMidMw),
      avgCpuLargeMw: weightedMean((s) => s.cpuLargeMw),
      avgCpuTotalMw: weightedMean((s) => s.cpuTotalMw),
      avgGpuMw: weightedMean((s) => s.gpuMw),
      avgNspMw: weightedMean((s) => s.nspMw),
    };
  }

  function buildSavedPowerRecord(setId, stats, recordedAtMs) {
    if (!stats || stats.avgCpuTotalMw === null || stats.avgCpuTotalMw === undefined) return null;
    return {
      setId,
      sampleCount: stats.sampleCount,
      elapsedSec: Math.max(1, Math.round(stats.elapsedSec)),
      avgCpuMidMw: Math.round(stats.avgCpuMidMw || 0),
      avgCpuLargeMw: Math.round(stats.avgCpuLargeMw || 0),
      avgCpuTotalMw: Math.round(stats.avgCpuTotalMw),
      avgGpuMw: Math.round(stats.avgGpuMw || 0),
      avgNspMw: Math.round(stats.avgNspMw || 0),
      recordedAtMs,
    };
  }

  function serializePowerRecords(recordsMap) {
    return Object.values(recordsMap || {})
      .sort((a, b) => a.setId - b.setId)
      .map((r) =>
        [
          r.setId,
          r.sampleCount,
          r.elapsedSec,
          r.avgCpuMidMw,
          r.avgCpuLargeMw,
          r.avgCpuTotalMw,
          r.avgGpuMw,
          r.avgNspMw,
          r.recordedAtMs,
        ].join(',')
      )
      .join('\n');
  }

  function deserializePowerRecords(raw) {
    const map = {};
    if (!raw || !String(raw).trim()) return map;
    for (const line of String(raw).split(/\r?\n/)) {
      const cols = line.trim().split(',');
      if (cols.length < 9) continue;
      const setId = Number.parseInt(cols[0], 10);
      const sampleCount = Number.parseInt(cols[1], 10);
      const elapsedSec = Number.parseInt(cols[2], 10);
      const mid = Number.parseInt(cols[3], 10);
      const large = Number.parseInt(cols[4], 10);
      const total = Number.parseInt(cols[5], 10);
      const gpu = Number.parseInt(cols[6], 10);
      const nsp = Number.parseInt(cols[7], 10);
      const ts = Number.parseInt(cols[8], 10);
      if (
        !(setId >= 1 && setId <= 7) ||
        !(sampleCount > 0) ||
        !(elapsedSec > 0) ||
        [mid, large, total, gpu, nsp].some((x) => !Number.isFinite(x) || x < 0)
      ) {
        continue;
      }
      map[setId] = {
        setId,
        sampleCount,
        elapsedSec,
        avgCpuMidMw: mid,
        avgCpuLargeMw: large,
        avgCpuTotalMw: total,
        avgGpuMw: gpu,
        avgNspMw: nsp,
        recordedAtMs: Number.isFinite(ts) && ts >= 0 ? ts : 0,
      };
    }
    return map;
  }

  // ---- Silicon Calibration ----
  function parseQfpromAging(raw) {
    const clean = raw && String(raw).trim();
    if (!clean || clean === 'none') return null;
    const parts = clean.split(',').map((s) => s.trim());
    if (parts.length < 3) return null;
    const parseHex = (p) => {
      const hex = p.replace(/^0[xX]/, '');
      if (!/^[0-9a-fA-F]+$/.test(hex)) return null;
      return Number.parseInt(hex, 16) >>> 0;
    };
    const a10 = parseHex(parts[0]);
    const a20 = parseHex(parts[1]);
    const a24 = parseHex(parts[2]);
    if (a10 === null || a20 === null || a24 === null) return null;

    const mag0 = (a10 >>> 12) & 0x7f;
    const clus0FusedDelta = (a10 & 0x80000) !== 0 ? -mag0 : mag0;
    const mag1 = (((a24 << 6) & 0xff) | (a20 >>> 26)) & 0x7f;
    const clus1FusedDelta = (a24 & 0x2) !== 0 ? -mag1 : mag1;
    const hex8 = (n) => `0x${(n >>> 0).toString(16).padStart(8, '0')}`;
    return {
      rawA10: a10,
      rawA20: a20,
      rawA24: a24,
      clus0FusedDelta,
      clus1FusedDelta,
      formattedHex: `${hex8(a10)} / ${hex8(a20)} / ${hex8(a24)}`,
    };
  }

  function parseSocInfo(raw) {
    const clean = raw && String(raw).trim();
    if (!clean || clean === 'none') return null;
    const kv = {};
    for (const token of clean.split(/\s+/)) {
      const eq = token.indexOf('=');
      if (eq > 0) {
        const k = token.slice(0, eq).trim();
        const v = token.slice(eq + 1).trim();
        if (v) kv[k] = v;
      }
    }
    const parsed = {
      chipId: kv.chip || null,
      foundryId: kv.foundry || null,
      featureCode: kv.feature || null,
      pcode: kv.pcode || null,
      oemVariant: kv.oem || null,
      pmicRev: kv.pmic_rev || null,
    };
    if (!parsed.chipId && !parsed.foundryId && !parsed.featureCode && !parsed.pcode && !parsed.oemVariant && !parsed.pmicRev) {
      return null;
    }
    return parsed;
  }

  function assessCprhDrift(bootCpussIndex, diffRows) {
    const nonZero = (diffRows || []).map((r) => r.deltaMv).filter((d) => d !== null && d !== undefined && d !== 0);
    if (bootCpussIndex !== 1 || !nonZero.length) {
      return { isLikelyCprhDrift: false, affectedBins: 0, minDeltaMv: 0, maxDeltaMv: 0 };
    }
    const matchesCprhStep = nonZero.every((d) => Math.abs(d) <= 8 && d % 4 === 0);
    return {
      isLikelyCprhDrift: matchesCprhStep,
      affectedBins: nonZero.length,
      minDeltaMv: Math.min(...nonZero),
      maxDeltaMv: Math.max(...nonZero),
    };
  }

  // ---- SystemProbeParser ----
  function cleanScalar(raw) {
    if (raw === undefined || raw === null) return null;
    const t = String(raw).trim();
    return t && t !== 'none' ? t : null;
  }

  function splitCsvBalanced(raw) {
    const clean = cleanScalar(raw);
    if (!clean) return [];
    const items = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < clean.length; i++) {
      const ch = clean[i];
      if (ch === '(') depth++;
      else if (ch === ')' && depth > 0) depth--;
      else if (ch === ',' && depth === 0) {
        const item = cleanScalar(clean.slice(start, i));
        if (item) items.push(item);
        start = i + 1;
      }
    }
    const tail = cleanScalar(clean.slice(start));
    if (tail) items.push(tail);
    return items;
  }

  function parseSystemProbe(probeBlock, adjDirPath = null, regmapNode = null) {
    const kv = {};
    if (probeBlock && String(probeBlock).trim()) {
      for (const line of String(probeBlock).split(/\r?\n/)) {
        const eq = line.indexOf('=');
        if (eq > 0) kv[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
      }
    }
    const info = {
      adjDirPath: cleanScalar(adjDirPath),
      regmapNode: cleanScalar(regmapNode),
      vadjPlatformDevice: cleanScalar(kv.vadj_platform_dev),
      vadjDriver: cleanScalar(kv.vadj_driver),
      vadjSpmiParent: cleanScalar(kv.vadj_spmi_parent),
      vadjDtPath: cleanScalar(kv.vadj_dt_path),
      vadjModuleLoaded: kv.vadj_module_loaded === '1',
      vadjModuleFiles: splitCsvBalanced(kv.vadj_module_files),
      debugfsMounts: splitCsvBalanced(kv.debugfs_mounts),
      debugfsVoltNodes: splitCsvBalanced(kv.debugfs_volt_nodes),
      pdpCdtPartitions: splitCsvBalanced(kv.pdp_cdt_partitions),
      byNameDirs: splitCsvBalanced(kv.by_name_dirs),
      scmiDrivers: splitCsvBalanced(kv.scmi_drivers),
      scmiDeviceCount: Math.max(0, Number.parseInt(kv.scmi_device_count || '0', 10) || 0),
      cpufreqPolicies: splitCsvBalanced(kv.cpufreq_policies),
      energyModelDomains: splitCsvBalanced(kv.energy_model_domains),
      socInfo: cleanScalar(kv.socinfo),
      qfpromAging: parseQfpromAging(kv.qfprom_aging),
      qptRails: splitCsvBalanced(kv.qpt_rails),
      sdam2NvmemPath: cleanScalar(kv.sdam2_nvmem),
      sdam2Pmic: cleanScalar(kv.sdam2_pmic),
    };
    info.hasAnyData = Boolean(
      info.adjDirPath ||
        info.regmapNode ||
        info.vadjPlatformDevice ||
        info.vadjModuleLoaded ||
        info.vadjModuleFiles.length ||
        info.debugfsMounts.length ||
        info.debugfsVoltNodes.length ||
        info.pdpCdtPartitions.length ||
        info.byNameDirs.length ||
        info.scmiDrivers.length ||
        info.scmiDeviceCount > 0 ||
        info.cpufreqPolicies.length ||
        info.energyModelDomains.length ||
        info.socInfo ||
        info.qfpromAging ||
        info.qptRails.length ||
        info.sdam2NvmemPath ||
        info.sdam2Pmic
    );
    return info;
  }

  // ---- CompatibilityChecker ----
  function evaluateCompatibility(input) {
    const items = [];
    const socUpper = String(input.socModel || '').trim().toUpperCase();
    if (input.isSm8850) {
      items.push({
        id: 'soc',
        title: 'SoC 平台与架构',
        status: 'PASS',
        summary: `已适配平台（${input.socModel}）`,
        detail: '已验证 SM8850 XBL -> PMK8850 SDAM2 (0x7151) -> PDP OEM_VOLTAGE_ADJUSTMENTS 链路与 SCMI 电压系数。',
        blocking: false,
      });
    } else if (/^(SM|QCS|SD|QCM)/.test(socUpper)) {
      items.push({
        id: 'soc',
        title: 'SoC 平台与架构',
        status: 'WARN',
        summary: `非 SM8850 高通平台（${input.socModel}）`,
        detail: '本工具主要面向 SM8850；若下方内核接口与 pdp_cdb 检查通过，可谨慎试用温和档位并验证重启后生效情况，SCMI mV 换算系数可能不准确。',
        blocking: false,
      });
    } else {
      items.push({
        id: 'soc',
        title: 'SoC 平台与架构',
        status: 'WARN',
        summary: `未知或非高通平台（${input.socModel}）`,
        detail: '未能识别为 SM8850，具体能否调压仍以下方内核接口与 pdp_cdb 分区实测结果为准。',
        blocking: false,
      });
    }

    if (input.adjAvailable && input.adjSetNodePresent) {
      const dbgPath = input.debugfsPath || '/sys/kernel/debug';
      const adjLoc = input.adjDirPath || dbgPath;
      let summary;
      let detail;
      if (input.adjBackend === 'NVMEM') {
        summary = `sysfs nvmem 兜底直通（CPUSS=${input.sdamCpussIndex ?? '?'} / Global=${input.sdamGlobalIndex ?? '?'}）`;
        detail = `未检测到 debugfs/qcom_voltage_adj，已通过已校验的 PMK8850 sdam@7100 (${input.adjDirPath || '/sys/bus/nvmem/devices/spmi_sdam2/nvmem'}) 偏移 0x51 读写 0x7151（不依赖 debugfs 与 qcom_voltage_adj 驱动）。`;
      } else if (input.debugfsPrivateNs) {
        summary = `读写节点完整（私有 NS 挂载，CPUSS=${input.sdamCpussIndex ?? '?'} / Global=${input.sdamGlobalIndex ?? '?'}）`;
        detail = `系统 init 未默认挂载 debugfs，已通过私有 mount namespace 临时挂载至 ${dbgPath}（不污染全局挂载表），在 ${adjLoc} 找到 get_overrides 与 set_overrides。`;
      } else {
        summary = `读写节点完整（CPUSS=${input.sdamCpussIndex ?? '?'} / Global=${input.sdamGlobalIndex ?? '?'}）`;
        detail = `已在 ${adjLoc} 找到 get_overrides 与 set_overrides，具备切换 Set 的内核前提。`;
      }
      items.push({ id: 'adj', title: '内核 qcom_voltage_adj 接口', status: 'PASS', summary, detail, blocking: true });
    } else if (input.adjAvailable && !input.adjSetNodePresent) {
      items.push({
        id: 'adj',
        title: '内核 qcom_voltage_adj 接口',
        status: 'FAIL',
        summary: '缺少 set_overrides 写节点',
        detail: '已读取到 get_overrides，但缺少 set_overrides，当前内核不允许修改 SDAM 调压索引。',
        blocking: true,
      });
    } else {
      items.push({
        id: 'adj',
        title: '内核 qcom_voltage_adj 接口',
        status: 'FAIL',
        summary: '未检测到 qcom_voltage_adj 接口',
        detail: input.adjError || '内核 debugfs 未暴露 qcom_voltage_adj 驱动节点，通常表示厂商内核已裁剪此功能。',
        blocking: true,
      });
    }

    const rmNode = input.regmapNode || '0-00';
    const regHexClean = input.reg7151Hex ? input.reg7151Hex.trim() : null;
    const regByte = regHexClean && /^[0-9a-fA-F]+$/.test(regHexClean) ? Number.parseInt(regHexClean, 16) : null;
    const expectedByte =
      input.sdamCpussIndex !== null && input.sdamGlobalIndex !== null
        ? (((input.sdamCpussIndex & 0x7) << 4) | (input.sdamGlobalIndex & 0x7) | 0x88)
        : null;
    if (regByte !== null && expectedByte !== null && regByte === expectedByte) {
      items.push({
        id: 'reg7151',
        title: 'PMIC SDAM 寄存器 (0x7151)',
        status: 'PASS',
        summary: `0x${regHexClean.toUpperCase()}（与 get_overrides 完全一致）`,
        detail: `PMIC regmap ${rmNode} 寄存器 0x7151 实时字节校验匹配目标格式 (CPUSS/Global valid 位均置 1)。`,
        blocking: false,
      });
    } else if (regByte !== null && expectedByte !== null) {
      items.push({
        id: 'reg7151',
        title: 'PMIC SDAM 寄存器 (0x7151)',
        status: 'WARN',
        summary: `0x${regHexClean.toUpperCase()} 与预期 0x${expectedByte.toString(16).toUpperCase().padStart(2, '0')} 不同`,
        detail: `此机型的 PMIC regmap (${rmNode}) 地址或位掩码布局可能与 Xiaomi SM8850 不同，切换档位后请通过 SCMI 或负载实测确认是否实际生效。`,
        blocking: false,
      });
    } else if (regByte !== null) {
      items.push({
        id: 'reg7151',
        title: 'PMIC SDAM 寄存器 (0x7151)',
        status: 'PASS',
        summary: `0x${regHexClean.toUpperCase()}`,
        detail: `已读取 PMIC regmap ${rmNode} SDAM 寄存器字节。`,
        blocking: false,
      });
    } else {
      items.push({
        id: 'reg7151',
        title: 'PMIC SDAM 寄存器 (0x7151)',
        status: 'WARN',
        summary: `regmap/${rmNode}/registers 不可读`,
        detail: `无法直接交叉核对 PMIC ${rmNode} 0x7151 寄存器字节（该接口对 qcom_voltage_adj 本身工作非必需）。`,
        blocking: false,
      });
    }

    const sets = input.pdpCdb ? input.pdpCdb.sets : [];
    const nonZeroSets = sets.filter((s) => !s.clus0.isNoOp || !s.clus1.isNoOp);
    const undervoltSets = sets.filter(
      (s) => s.clus0.minCxMv < 0 || s.clus1.minCxMv < 0 || s.clus0.minMxMv < 0 || s.clus1.minMxMv < 0
    );
    const minCx = sets.length ? Math.min(...sets.map((s) => Math.min(s.clus0.minCxMv, s.clus1.minCxMv))) : null;
    if (!input.pdpCdb) {
      items.push({
        id: 'pdp_cdb',
        title: 'pdp_cdb 固件调压表',
        status: 'FAIL',
        summary: '缺失或无法解析 pdp_cdb 分区',
        detail: input.pdpCdbError || '未找到或无法按 ELF32/CFDB v3 解码 pdp_cdb 分区，无法确定是否存在 OEM 电压档位。',
        blocking: true,
      });
    } else if (!nonZeroSets.length) {
      items.push({
        id: 'pdp_cdb',
        title: 'pdp_cdb 固件调压表',
        status: 'FAIL',
        summary: '所有 Set 档位均为 0 mV',
        detail: `已解出 ${input.pdpCdbPath || 'pdp_cdb'}，但所有组别未配置任何电压偏移，切换 Set 不会起作用。`,
        blocking: true,
      });
    } else if (!undervoltSets.length) {
      items.push({
        id: 'pdp_cdb',
        title: 'pdp_cdb 固件调压表',
        status: 'WARN',
        summary: `仅发现非负/加压配置 (${nonZeroSets.length} 组)`,
        detail: '分区中存在非 0 条目，但没有负值 CX/MX 降压配置，不适合用于降压目的。',
        blocking: false,
      });
    } else {
      items.push({
        id: 'pdp_cdb',
        title: 'pdp_cdb 固件调压表',
        status: 'PASS',
        summary: `${undervoltSets.length} 组可用降压档位（最低 CX ${minCx ?? 0} mV）`,
        detail: `已成功解码 ${input.pdpCdbPath || 'pdp_cdb'}（共 ${sets.length} 个 Set），包含真实有效的 CX/MX 偏移规则。`,
        blocking: true,
      });
    }

    const lockVal = input.pdpCdb ? input.pdpCdb.overrideSetId : null;
    if (!input.pdpCdb) {
      items.push({
        id: 'override_lock',
        title: 'pdp_cdb 强制覆盖锁',
        status: 'WARN',
        summary: '未能检测（依赖 pdp_cdb）',
        detail: '需先成功解析 pdp_cdb 后才能确认 OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE 状态。',
        blocking: false,
      });
    } else if (lockVal === null || lockVal === 0) {
      items.push({
        id: 'override_lock',
        title: 'pdp_cdb 强制覆盖锁',
        status: 'PASS',
        summary: `未锁定（${lockVal ?? 0}），由 SDAM 控制`,
        detail: `OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE = ${lockVal ?? 0}，固件会响应 qcom_voltage_adj 写入的 Set 索引。`,
        blocking: true,
      });
    } else {
      items.push({
        id: 'override_lock',
        title: 'pdp_cdb 强制覆盖锁',
        status: 'FAIL',
        summary: `固件强锁 Set ${lockVal}（禁止外部切换）`,
        detail: `pdp_cdb 已硬编码 OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE = ${lockVal}，固件启动时会无视 SDAM 写入的档位。`,
        blocking: true,
      });
    }

    const cdt = input.cdtStatus;
    if (cdt.key7Enabled) {
      items.push({
        id: 'cdt_key7',
        title: 'CDT Key 7 叠加风险检测',
        status: 'WARN',
        summary: `已启用 CDT Key 7=${cdt.key7Value}（危险叠加）`,
        detail: '当前 CDT 分区开启了 SLT+ACD 降压，它会与 OEM Set 在固件层累加（双重降压），建议先还回原厂 CDT 再切换高档位。',
        blocking: false,
      });
    } else if (cdt.state === 'EMPTY' || cdt.state === 'STOCK') {
      items.push({
        id: 'cdt_key7',
        title: 'CDT Key 7 叠加风险检测',
        status: 'PASS',
        summary: '未启用（安全）',
        detail: cdt.detail,
        blocking: false,
      });
    } else {
      items.push({
        id: 'cdt_key7',
        title: 'CDT Key 7 叠加风险检测',
        status: 'WARN',
        summary: 'CDT 状态未知或异常',
        detail: cdt.detail,
        blocking: false,
      });
    }

    const pageLabel = input.pageSizeBytes ? `${Math.trunc(input.pageSizeBytes / 1024)}K 页` : '未知页大小';
    if (!input.scmiError && input.scmiPoints.length && input.scmiPoints.every((p) => p.exactFormulaMatch)) {
      const isEm = input.scmiSource === 'ENERGY_MODEL';
      items.push({
        id: 'scmi',
        title: 'SCMI 实时电压读表（可选）',
        status: 'PASS',
        summary: isEm
          ? `正常 · ${input.scmiPoints.length} 个频点（energy_model 零模块，公式 100% 吻合）`
          : `正常 · ${input.scmiPoints.length} 个频点（${pageLabel}，公式 100% 吻合）`,
        detail: isEm
          ? `已直接从内核 debugfs/energy_model 读取 SCMI 功率表（无需加载 .ko 模块，原生兼容 ${pageLabel} 与任意内核版本），可用 Set 1 原厂基线精确检验实际 mV 变化。`
          : `内置模块 ${input.scmiModuleAsset || 'qcom-scmi-ro.ko'} 工作正常，可用 Set 1 原厂基线精确检验实际 mV 变化。`,
        blocking: false,
      });
    } else if (!input.scmiError && input.scmiPoints.length) {
      const exactCount = input.scmiPoints.filter((p) => p.exactFormulaMatch).length;
      items.push({
        id: 'scmi',
        title: 'SCMI 实时电压读表（可选）',
        status: 'WARN',
        summary: `已读取 ${input.scmiPoints.length} 个频点（${exactCount}/${input.scmiPoints.length} 公式吻合）`,
        detail: `电压表可读（来源：${input.scmiSource === 'ENERGY_MODEL' ? 'debugfs/energy_model' : input.scmiModuleAsset || 'SCMI 模块'}），但当前平台的功率系数可能与 SM8850 不同，部分反推 mV 数值仅供参考，不影响调压功能本身。`,
        blocking: false,
      });
    } else {
      const pageHint =
        input.pageSizeBytes && input.pageSizeBytes !== 4096
          ? `当前内核为 ${pageLabel}，内置模块仅针对 4K 页 GKI 编译；`
          : '';
      items.push({
        id: 'scmi',
        title: 'SCMI 实时电压读表（可选）',
        status: 'WARN',
        summary: '无法读取 SCMI 电压表（不影响调压）',
        detail: `${pageHint}未找到可用的 debugfs/energy_model，且回退模块 ${input.scmiModuleAsset || '无'} 不可用，原因：${input.scmiError || '无快照数据'}。这仅影响「SCMI 电压」实测对比，不影响切换 Set 档位。`,
        blocking: false,
      });
    }

    const fw = input.pdpHardwareLimits;
    const fw0 = fw && fw.clus0;
    const fw1 = fw && fw.clus1;
    if (fw && fw0 && fw1) {
      items.push({
        id: 'pdp_fw',
        title: 'pdp 固件硬件限位表（可选）',
        status: 'PASS',
        summary: `已匹配双簇 floor/ceil（PDP0 ${fw0.limitsByFreqKhz.size} 档 / PDP1 ${fw1.limitsByFreqKhz.size} 档）`,
        detail: `已从 ${input.pdpFwPath || 'pdp'}（${fw.totalDcvsTablesFound} 张 DCVS 表）按本机频点自动匹配硬件上下限，首页支持按个体体质预估各 Set 实降范围。`,
        blocking: false,
      });
    } else if (fw && (fw0 || fw1)) {
      const desc = [
        fw0 ? `PDP0 ${fw0.limitsByFreqKhz.size} 档` : null,
        fw1 ? `PDP1 ${fw1.limitsByFreqKhz.size} 档` : null,
      ]
        .filter(Boolean)
        .join(' / ');
      items.push({
        id: 'pdp_fw',
        title: 'pdp 固件硬件限位表（可选）',
        status: 'WARN',
        summary: `仅部分匹配（${desc}）`,
        detail:
          input.pdpFwError ||
          `已读取 ${input.pdpFwPath || 'pdp'}，仅部分集群完成唯一匹配，未匹配集群不影响 Set 切换，但切换前无法预估其钳位范围。`,
        blocking: false,
      });
    } else {
      items.push({
        id: 'pdp_fw',
        title: 'pdp 固件硬件限位表（可选）',
        status: 'WARN',
        summary: '未匹配到本机硬件限位（不影响调压）',
        detail:
          input.pdpFwError ||
          `未能从 ${input.pdpFwPath || 'pdp'} 匹配本机双簇 floor/ceil 表。这仅影响切换 Set 前的「本机预计实降范围」推算，不影响切换 Set 或 SCMI 实测。`,
        blocking: false,
      });
    }

    const hasBlockingFailure = items.some((i) => i.blocking && i.status === 'FAIL');
    const nonPassingCount = items.filter((i) => i.status !== 'PASS').length;
    const passedCount = items.filter((i) => i.status === 'PASS').length;
    let level;
    let headline;
    let subheadline;
    if (hasBlockingFailure) {
      const blockers = items.filter((i) => i.blocking && i.status === 'FAIL').map((i) => i.title).join('、');
      level = 'UNSUPPORTED';
      headline = '不支持本机 OEM Set 调压';
      subheadline = `存在阻塞性不兼容项：${blockers}`;
    } else if (nonPassingCount > 0) {
      level = 'PARTIAL';
      headline = `可用于调压（有 ${nonPassingCount} 项提示）`;
      subheadline = '核心条件已具备，建议先查看下方黄色提醒项后再温和测试。';
    } else {
      const maxSet = sets.length ? Math.max(...sets.map((s) => s.setId)) : 7;
      level = 'COMPATIBLE';
      headline = `完全兼容（${items.length}/${items.length} 项通过）`;
      subheadline = `支持调节 Set 1–${maxSet}，并可实时读取 SCMI 电压对比。`;
    }
    return {
      level,
      headline,
      subheadline,
      items,
      passedCount,
      totalCount: items.length,
      usableUndervoltSetCount: undervoltSets.length,
      minCxOffsetMv: minCx,
    };
  }

  function formatTextReport(snapshot, appVersion) {
    const compat = snapshot.compatibility;
    const lines = [];
    lines.push(`VoltSet ${appVersion} 设备兼容性检测报告`);
    lines.push(`结论: [${compat.level}] ${compat.headline}`);
    lines.push(`摘要: ${compat.subheadline}`);
    lines.push(`机型: ${snapshot.deviceModel} (${snapshot.deviceName}) | SoC: ${snapshot.socModel} | Slot: ${snapshot.slotSuffix || 'none'}`);
    lines.push(`内核: ${snapshot.kernelRelease} | 页大小: ${snapshot.pageSizeBytes ?? 'unknown'} B`);
    lines.push(`debugfs: ${snapshot.debugfsPath || 'none'}${snapshot.debugfsPrivateNs ? ' (private-ns)' : ''}`);
    lines.push(`SDAM: ${snapshot.sdamCpussIndex ?? '?'}/${snapshot.sdamGlobalIndex ?? '?'} 0x7151=${snapshot.reg7151Hex || '--'}`);
    lines.push(
      `开机档推定=${snapshot.bootSet.runningIndex ?? '?'} 固件锁档=${snapshot.bootSet.firmwareIndex ?? '—'} ` +
        `本次首次读回=${snapshot.bootSet.firstSdamIndex ?? '?'} 上次启动写入=${snapshot.bootSet.previousTargetIndex ?? '—'} ` +
        `当前待重启=${snapshot.pendingSetIndex ?? '—'}`
    );
    lines.push('');
    lines.push('[分项检测结果]');
    for (const item of compat.items) {
      lines.push(`- [${item.status}] ${item.title}: ${item.summary}`);
      lines.push(`  ${item.detail}`);
    }
    const probe = snapshot.systemProbe;
    if (probe && probe.hasAnyData) {
      lines.push('');
      lines.push('[全机只读自动探测]');
      const adjStr =
        snapshot.adjBackend === 'DEBUGFS'
          ? 'debugfs/qcom_voltage_adj'
          : snapshot.adjBackend === 'NVMEM'
          ? 'sysfs spmi_sdam2/nvmem (sdam@7100+0x51)'
          : 'none';
      lines.push(`调压读写通道: ${adjStr}`);
      lines.push(`debugfs 挂载点: ${probe.debugfsMounts.join(', ') || 'none'}`);
      lines.push(`qcom_voltage_adj 目录: ${probe.adjDirPath || 'none'}`);
      lines.push(`debugfs 调压节点: ${probe.debugfsVoltNodes.join(', ') || 'none'}`);
      const devSummary = probe.vadjPlatformDevice
        ? `${probe.vadjPlatformDevice} (driver=${probe.vadjDriver || 'unknown'}, spmi=${probe.vadjSpmiParent || 'unknown'})`
        : 'none';
      lines.push(`平台设备/驱动: ${devSummary}`);
      lines.push(`设备树路径: ${probe.vadjDtPath || 'none'}`);
      lines.push(`内核模块: ${probe.vadjModuleLoaded ? '已加载' : '未加载'} | 磁盘: ${probe.vadjModuleFiles.join(', ') || 'none'}`);
      lines.push(`PMIC regmap 节点: ${probe.regmapNode || 'none'}`);
      lines.push(
        `PMK8850 SDAM2 nvmem: ${
          probe.sdam2NvmemPath ? (probe.sdam2Pmic ? `${probe.sdam2NvmemPath} (${probe.sdam2Pmic})` : probe.sdam2NvmemPath) : 'none'
        }`
      );
      lines.push(`GPT 发现分区(pdp/cdt): ${probe.pdpCdtPartitions.join(', ') || 'none'}`);
      lines.push(`by-name 目录: ${probe.byNameDirs.join(', ') || 'none'}`);
      lines.push(`SCMI 接口: ${probe.scmiDeviceCount} 个设备 | ${probe.scmiDrivers.join(', ') || 'none'}`);
      lines.push(`CPU 调频簇: ${probe.cpufreqPolicies.join(', ') || 'none'}`);
      lines.push(
        `电压表来源: ${
          snapshot.scmiSource === 'ENERGY_MODEL'
            ? 'debugfs/energy_model (零内核模块)'
            : snapshot.scmiSource === 'KERNEL_MODULE'
            ? 'qcom-scmi-ro.ko'
            : 'none'
        }`
      );
      lines.push(`energy_model 域: ${probe.energyModelDomains.join(', ') || 'none'}`);
      lines.push(`硅片信息 (socinfo): ${probe.socInfo || 'none'}`);
      lines.push(
        `QFPROM 老化熔丝 (0xA10/0xA20): ${
          probe.qfpromAging
            ? `clus0=${probe.qfpromAging.clus0FusedDelta}, clus1=${probe.qfpromAging.clus1FusedDelta} (${probe.qfpromAging.formattedHex})`
            : 'none'
        }`
      );
      lines.push(`QPT 硬件功耗轨: ${probe.qptRails.join(', ') || 'none'}`);
    }
    const sets = snapshot.pdpCdb ? snapshot.pdpCdb.sets : [];
    if (sets.length) {
      lines.push('');
      lines.push('[pdp_cdb 档位摘要]');
      for (const s of sets) {
        lines.push(
          `Set ${s.setId} (${s.tag}): ` +
            `PDP0 CX ${s.clus0.minCxMv}..${s.clus0.maxCxMv} MX ${s.clus0.minMxMv}..${s.clus0.maxMxMv} | ` +
            `PDP1 CX ${s.clus1.minCxMv}..${s.clus1.maxCxMv} MX ${s.clus1.minMxMv}..${s.clus1.maxMxMv}`
        );
      }
    }
    return lines.join('\n');
  }

  // ---- Snapshot Builder ----
  function parseIntInRange(raw, min, max) {
    if (raw === undefined || raw === null) return null;
    const n = Number.parseInt(String(raw).trim(), 10);
    return Number.isFinite(n) && n >= min && n <= max ? n : null;
  }

  async function buildDeviceSnapshot(output) {
    const reply = parseBackendOutput(output);
    const kv = reply.values;
    const bootId = kv.BOOT_ID;
    if (!bootId) throw new Error('后端未返回启动 ID');
    const socModel = kv.SOC_MODEL || 'unknown';
    const deviceModel = kv.DEVICE_MODEL || 'unknown';
    const deviceName = kv.DEVICE_NAME || 'unknown';
    const slotSuffix = kv.SLOT || '';
    const kernelRelease = kv.KERNEL || 'unknown';
    const pageSizeBytes = kv.PAGE_SIZE ? Number.parseInt(kv.PAGE_SIZE, 10) || null : null;
    const scmiModuleAsset = cleanScalar(kv.SCMI_MODULE_ASSET);
    const isSm8850 = socModel.toUpperCase().startsWith('SM8850');

    const adjBackend = kv.ADJ_BACKEND === 'debugfs' ? 'DEBUGFS' : kv.ADJ_BACKEND === 'nvmem' ? 'NVMEM' : 'NONE';
    const adjAvailable = kv.ADJ_AVAILABLE === '1';
    const adjSetNodePresent = kv.ADJ_SET_NODE === '1';
    const debugfsPath = cleanScalar(kv.DEBUGFS);
    const debugfsPrivateNs = kv.DEBUGFS_PRIVATE_NS === '1';
    const adjDirPath = cleanScalar(kv.ADJ_DIR);
    const regmapNode = cleanScalar(kv.REGMAP_NODE);
    const adjError = kv.ADJ_ERROR || null;
    const sdamCpussIndex = kv.SDAM_CPUSS_IDX !== undefined ? Number.parseInt(kv.SDAM_CPUSS_IDX, 10) : null;
    const sdamGlobalIndex = kv.SDAM_GLOBAL_IDX !== undefined ? Number.parseInt(kv.SDAM_GLOBAL_IDX, 10) : null;
    const reg7151Hex = kv.REG_7151 && kv.REG_7151 !== 'unavailable' ? kv.REG_7151 : null;
    const pdpCdbPath = cleanScalar(kv.PDP_CDB_PATH);

    let pdpCdb = null;
    let pdpCdbError = kv.PDP_CDB_ERROR || null;
    if (reply.blocks.PDP_CDB_GZB64 || reply.blocks.PDP_CDB_B64) {
      try {
        const bytes = await decodeBlockBytes(reply.blocks, 'PDP_CDB_GZB64', 'PDP_CDB_B64');
        pdpCdb = parsePdpCdb(bytes);
      } catch (err) {
        pdpCdbError = err.message || 'pdp_cdb 解析失败';
      }
    } else if (kv.PDP_CDB_PATH === 'none' && !pdpCdbError) {
      pdpCdbError = '未找到 pdp_cdb 分区';
    }

    const cdtBytes = reply.blocks.CDT_B64 ? base64ToBytes(reply.blocks.CDT_B64) : null;
    const cdtStatus = inspectCdt(cdtBytes);

    const bootSet = computeBootSetStatus({
      firstSdamIndex: parseIntInRange(kv.FIRST_SDAM_IDX, 0, 7),
      previousTargetIndex: parseIntInRange(kv.PREVIOUS_TARGET_IDX, 1, 7),
      stagedThisBootIndex: parseIntInRange(kv.STAGED_THIS_BOOT_IDX, 1, 7),
      firmwareIndex: pdpCdb && pdpCdb.overrideSetId >= 1 && pdpCdb.overrideSetId <= 7 ? pdpCdb.overrideSetId : null,
    });

    const initialPoints = reply.blocks.SCMI ? parseScmiCsv(reply.blocks.SCMI, isSm8850) : [];
    const qualityReport = reply.blocks.SCMI_RECHECK
      ? evaluateQualityReport(initialPoints, parseScmiCsv(reply.blocks.SCMI_RECHECK, isSm8850))
      : null;
    const scmiPoints = qualityReport ? qualityReport.points : initialPoints;
    const scmiSource =
      kv.SCMI_SOURCE === 'energy_model' ? 'ENERGY_MODEL' : kv.SCMI_SOURCE === 'module' ? 'KERNEL_MODULE' : 'NONE';
    let scmiError = null;
    if (kv.SCMI_STATUS !== 'ok') scmiError = cleanScalar(kv.SCMI_ERROR) || '读取 SCMI 失败';
    else if (!scmiPoints.length) scmiError = 'SCMI 快照没有可解析的 CPU 频点';

    const baselineRaw = reply.blocks.BASELINE
      ? parseScmiCsv(reply.blocks.BASELINE, isSm8850, qualityReport ? qualityReport.coefficients : {})
      : [];
    const baselinePoints = baselineRaw.length ? baselineRaw : null;
    const baselineSource = !baselinePoints
      ? 'NONE'
      : kv.BASELINE_SOURCE === 'voltset'
      ? 'VOLTSET'
      : kv.BASELINE_SOURCE === 'suntuner'
      ? 'SUNTUNER'
      : 'NONE';

    const activeBootSet =
      bootSet.runningIndex !== null && pdpCdb
        ? pdpCdb.sets.find((s) => s.setId === bootSet.runningIndex) || null
        : null;
    const voltageDiffRows = diffScmi(scmiPoints, baselinePoints, activeBootSet);

    const pdpFwPath = cleanScalar(kv.PDP_FW_PATH);
    let pdpFwError = kv.PDP_FW_ERROR || null;
    let pdpHardwareLimits = null;
    if (reply.blocks.PDP_FW_GZB64 || reply.blocks.PDP_FW_B64) {
      try {
        const bytes = await decodeBlockBytes(reply.blocks, 'PDP_FW_GZB64', 'PDP_FW_B64');
        const tables = parsePdpFirmware(bytes);
        const matched = matchDeviceLimits(tables, baselinePoints, scmiPoints);
        pdpHardwareLimits = matched;
        if (!matched.isComplete && !pdpFwError) {
          if ((!baselinePoints || !baselinePoints.length) && !scmiPoints.length) {
            pdpFwError = '缺少 SCMI 频点列表，无法匹配对应体质档位的 pdp 硬件限位表';
          } else if (!matched.clus0 && !matched.clus1) {
            pdpFwError = `未能在 pdp（${tables.length} 张表）中唯一匹配到双簇频点对应的硬件限位`;
          } else if (!matched.clus0) {
            pdpFwError = '未能在 pdp 中唯一匹配到小核 PDP0 硬件限位';
          } else {
            pdpFwError = '未能在 pdp 中唯一匹配到大核 PDP1 硬件限位';
          }
        }
      } catch (err) {
        pdpFwError = err.message || 'pdp 固件表解析失败';
      }
    } else if (kv.PDP_FW_PATH === 'none' && !pdpFwError) {
      pdpFwError = '未找到 pdp 分区';
    }

    const setEstimates = estimateAllSets(pdpCdb ? pdpCdb.sets : [], baselinePoints, pdpHardwareLimits);
    const systemProbe = parseSystemProbe(reply.blocks.PROBE, adjDirPath, regmapNode);

    const compatibility = evaluateCompatibility({
      socModel,
      isSm8850,
      pageSizeBytes,
      debugfsPath,
      debugfsPrivateNs,
      adjBackend,
      adjAvailable,
      adjSetNodePresent,
      adjError,
      sdamCpussIndex: Number.isFinite(sdamCpussIndex) ? sdamCpussIndex : null,
      sdamGlobalIndex: Number.isFinite(sdamGlobalIndex) ? sdamGlobalIndex : null,
      reg7151Hex,
      pdpCdbPath,
      pdpCdb,
      pdpCdbError,
      pdpFwPath,
      pdpHardwareLimits,
      pdpFwError,
      cdtStatus,
      scmiModuleAsset,
      scmiSource,
      scmiError,
      scmiPoints,
      adjDirPath,
      regmapNode,
    });

    const snapshot = {
      bootId,
      socModel,
      deviceModel,
      deviceName,
      slotSuffix,
      kernelRelease,
      pageSizeBytes,
      isSm8850,
      debugfsPath,
      debugfsPrivateNs,
      adjBackend,
      adjAvailable,
      adjSetNodePresent,
      adjError,
      bootSet,
      bootCpussIndex: bootSet.runningIndex,
      sdamCpussIndex: Number.isFinite(sdamCpussIndex) ? sdamCpussIndex : null,
      sdamGlobalIndex: Number.isFinite(sdamGlobalIndex) ? sdamGlobalIndex : null,
      reg7151Hex,
      pdpCdbPath,
      pdpCdb,
      pdpCdbError,
      pdpFwPath,
      pdpHardwareLimits,
      pdpFwError,
      cdtPath: cleanScalar(kv.CDT_PATH),
      cdtStatus,
      scmiModuleAsset,
      scmiSource,
      scmiError,
      scmiPoints,
      qualityReport,
      baselineSource,
      baselinePoints,
      voltageDiffRows,
      setEstimates,
      systemProbe,
      compatibility,
    };

    return enrichSnapshotHelpers(snapshot);
  }

  function enrichSnapshotHelpers(snapshot) {
    const sdamIdx = snapshot.sdamCpussIndex;
    const pendingSetIndex = snapshot.bootSet.pendingIndex(sdamIdx);
    const pdpCdb = snapshot.pdpCdb;
    const activeBootSet =
      snapshot.bootCpussIndex !== null && pdpCdb
        ? pdpCdb.sets.find((s) => s.setId === snapshot.bootCpussIndex) || null
        : null;
    const sdamTargetSet =
      sdamIdx !== null && pdpCdb ? pdpCdb.sets.find((s) => s.setId === sdamIdx) || null : null;
    const canWriteOverrides = snapshot.adjAvailable && snapshot.adjSetNodePresent;
    const overrideLockedByFirmware = Boolean(pdpCdb && pdpCdb.overrideSetId !== null && pdpCdb.overrideSetId !== 0);
    const canSaveStockBaseline =
      snapshot.bootCpussIndex === 1 &&
      snapshot.bootSet.firstSdamIndex === 1 &&
      !snapshot.bootSet.rebootReadbackMismatch &&
      !snapshot.cdtStatus.key7Enabled &&
      (snapshot.cdtStatus.state === 'EMPTY' || snapshot.cdtStatus.state === 'STOCK') &&
      (!pdpCdb || pdpCdb.overrideSetId === null || pdpCdb.overrideSetId === 0 || pdpCdb.overrideSetId === 1) &&
      snapshot.scmiPoints.length > 0;

    snapshot.pendingSetIndex = pendingSetIndex;
    snapshot.pendingReboot = pendingSetIndex !== null;
    snapshot.activeBootSet = activeBootSet;
    snapshot.sdamTargetSet = sdamTargetSet;
    snapshot.canWriteOverrides = canWriteOverrides;
    snapshot.overrideLockedByFirmware = overrideLockedByFirmware;
    snapshot.canSaveStockBaseline = canSaveStockBaseline;
    snapshot.canSelectSet = function (setId) {
      return (
        canWriteOverrides &&
        snapshot.sdamCpussIndex !== null &&
        snapshot.sdamGlobalIndex !== null &&
        !overrideLockedByFirmware &&
        setId !== snapshot.sdamCpussIndex &&
        (setId === 1 ||
          (snapshot.compatibility.level !== 'UNSUPPORTED' &&
            Boolean(pdpCdb && pdpCdb.sets.some((s) => s.setId === setId))))
      );
    };
    return snapshot;
  }

  function formatSignedMv(mv) {
    if (mv > 0) return `+${mv} mV`;
    if (mv < 0) return `${mv} mV`;
    return '0 mV';
  }

  function formatRange(minMv, maxMv) {
    return minMv === maxMv ? formatSignedMv(minMv) : `${minMv} ~ ${formatSignedMv(maxMv)}`;
  }

  function formatFreqMhz(freqKhz) {
    const whole = Math.trunc(freqKhz / 1000);
    const tenths = Math.trunc((freqKhz % 1000) / 100);
    return tenths === 0 ? `${whole} MHz` : `${whole}.${tenths} MHz`;
  }

  function parseAutoRestoreConfig(raw) {
    const out = { enabled: false, targetSet: null };
    if (!raw || !String(raw).trim()) return out;
    for (const line of String(raw).split(/\r?\n/)) {
      const eq = line.indexOf('=');
      if (eq <= 0) continue;
      const k = line.slice(0, eq).trim();
      const v = line.slice(eq + 1).trim();
      if (k === 'enabled') out.enabled = v === '1';
      else if (k === 'target_set') {
        const n = Number.parseInt(v, 10);
        if (Number.isFinite(n) && n >= 2 && n <= 7) out.targetSet = n;
      }
    }
    return out;
  }

  function serializeAutoRestoreConfig(cfg) {
    const enabled = cfg && cfg.enabled && cfg.targetSet >= 2 && cfg.targetSet <= 7 ? 1 : 0;
    const target = cfg && cfg.targetSet >= 2 && cfg.targetSet <= 7 ? cfg.targetSet : '';
    return `enabled=${enabled}\ntarget_set=${target}\n`;
  }

  function parseAutoRestoreStatus(raw) {
    if (!raw || !String(raw).trim()) return null;
    const kv = Object.create(null);
    for (const line of String(raw).split(/\r?\n/)) {
      const eq = line.indexOf('=');
      if (eq > 0) kv[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
    }
    if (!kv.action && !kv.detail) return null;
    return {
      bootId: kv.boot_id || null,
      time: kv.time || null,
      action: kv.action || null,
      targetSet: parseIntInRange(kv.target_set, 1, 7),
      observedSet: parseIntInRange(kv.observed_set, 0, 7),
      detail: kv.detail || null,
    };
  }

  return {
    parseBackendOutput,
    base64ToBytes,
    gunzipBytes,
    parseCfdb,
    parsePdpCdb,
    parsePdpFirmware,
    matchDeviceLimits,
    inspectCdt,
    parseScmiCsv,
    evaluateQualityReport,
    diffScmi,
    summarizeByCluster,
    estimateAllSets,
    computeBootSetStatus,
    parseQptSample,
    computeQptDelta,
    summarizeQptSession,
    buildSavedPowerRecord,
    serializePowerRecords,
    deserializePowerRecords,
    parseQfpromAging,
    parseSocInfo,
    assessCprhDrift,
    parseSystemProbe,
    evaluateCompatibility,
    formatTextReport,
    buildDeviceSnapshot,
    enrichSnapshotHelpers,
    parseAutoRestoreConfig,
    serializeAutoRestoreConfig,
    parseAutoRestoreStatus,
    formatSignedMv,
    formatRange,
    formatFreqMhz,
  };
});
