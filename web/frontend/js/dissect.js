// Packet dissection: decode raw output packet bytes into named header fields
// using deparser emit trace events and header type definitions from the IR.

import { state } from './state.js';
import { escapeHtml, formatHexDump, bytesToHex, formatFieldValue } from './encoding.js';

/**
 * Collect deparser_emit events from a trace tree for a specific output packet.
 * Walks the tree looking for a PacketOutcome.output matching the given payload,
 * collecting emit events along the path. Falls back to the first complete path
 * if no exact match is found.
 */
function collectEmitEvents(trace, targetPayload) {
  // Collect emit events at this level.
  const emits = [];
  for (const event of trace.events || []) {
    if (event.deparser_emit) emits.push(event.deparser_emit);
  }

  // Leaf node with a packet outcome — check if it matches.
  if (trace.packet_outcome?.output?.payload) {
    return { emits, match: trace.packet_outcome.output.payload === targetPayload };
  }

  // Continuations and choice alternatives are walked the same way here: we
  // only need the path leading to the matching output packet.
  const subtrees = [
    ...(trace.continuations?.continuations || []),
    ...(trace.choice?.alternatives || []),
  ].map((branch) => branch.subtree);

  // No subtrees — this is either a drop or has no outcome.
  if (subtrees.length === 0) {
    return { emits, match: false };
  }

  // If targetPayload is provided, look for the matching subtree.
  for (const subtree of subtrees) {
    const sub = collectEmitEvents(subtree, targetPayload);
    if (sub.match) {
      return { emits: [...emits, ...sub.emits], match: true };
    }
  }

  // No match found — fall back to the first subtree.
  const fallback = collectEmitEvents(subtrees[0], null);
  return { emits: [...emits, ...fallback.emits], match: false };
}

/**
 * Dissect a packet into decoded headers using deparser emit events.
 * Returns an array of {typeName, fields: [{name, bitwidth, value}]} objects,
 * plus a trailing `remainder` byte array for any unparsed bytes.
 *
 * @param {Uint8Array} payload - output packet bytes
 * @param {object} trace - trace tree node (must have `events` array)
 * @param {string} [targetPayload] - base64 payload to match a specific subtree
 */
export function dissectPacket(payload, trace, targetPayload) {
  if (!payload || !trace || !state.headerTypes) return null;

  const { emits } = collectEmitEvents(trace, targetPayload || null);
  if (emits.length === 0) return null;

  const headers = [];
  let offset = 0;

  for (const emit of emits) {
    const schema = state.headerTypes[emit.header_type];
    if (!schema || offset + emit.byte_length > payload.length) break;

    const headerBytes = payload.slice(offset, offset + emit.byte_length);
    const fields = [];
    let bitOffset = 0;

    for (const fieldDef of schema) {
      const fieldBits = fieldDef.bitwidth;
      if (fieldBits <= 0) continue;

      // Extract field bytes from the header's packed bits.
      const fieldBytes = extractBits(headerBytes, bitOffset, fieldBits);
      fields.push({
        name: fieldDef.name,
        bitwidth: fieldBits,
        value: formatFieldValue(fieldBytes, fieldBits, fieldDef.name),
      });
      bitOffset += fieldBits;
    }

    headers.push({ typeName: emit.header_type, fields });
    offset += emit.byte_length;
  }

  const remainder = offset < payload.length ? payload.slice(offset) : null;
  return { headers, remainder };
}

/**
 * Extract `bitCount` bits starting at `bitOffset` from a byte array,
 * returning a new Uint8Array of ceil(bitCount/8) bytes.
 */
function extractBits(bytes, bitOffset, bitCount) {
  const outLen = Math.ceil(bitCount / 8);
  const out = new Uint8Array(outLen);

  for (let i = 0; i < bitCount; i++) {
    const srcByteIdx = Math.floor((bitOffset + i) / 8);
    const srcBitIdx = 7 - ((bitOffset + i) % 8); // MSB-first
    const dstByteIdx = Math.floor(i / 8);
    const dstBitIdx = 7 - (i % 8);

    if (srcByteIdx < bytes.length && (bytes[srcByteIdx] >> srcBitIdx) & 1) {
      out[dstByteIdx] |= (1 << dstBitIdx);
    }
  }

  return out;
}

/**
 * Render a dissected packet as HTML. Shows each header with field names
 * and values.
 */
export function renderDissectedPacket(dissection) {
  if (!dissection) return null;

  let html = '';
  for (const header of dissection.headers) {
    const fieldsHtml = header.fields.map(f =>
      `<div class="dissect-field"><span class="dissect-field-name">${escapeHtml(f.name)}</span> <span class="dissect-field-value">${escapeHtml(f.value)}</span></div>`
    ).join('');
    html += `<div class="dissect-header"><div class="dissect-header-name">${escapeHtml(header.typeName)}</div>${fieldsHtml}</div>`;
  }

  if (dissection.remainder && dissection.remainder.length > 0) {
    const hex = bytesToHex(dissection.remainder);
    html += `<div class="dissect-header"><div class="dissect-header-name">payload</div><div class="dissect-field"><span class="dissect-field-value">${hex}</span></div></div>`;
  }

  return html;
}

/**
 * Render decoded + raw hex sections for an output packet.
 * Shared by the Packets tab and the trace outcome view.
 */
export function renderPacketSections(bytes, trace, targetPayload) {
  const dissection = dissectPacket(bytes, trace, targetPayload);
  const decoded = renderDissectedPacket(dissection);
  const hex = formatHexDump(bytes);
  const decodedSection = decoded
    ? `<div class="output-section"><div class="output-section-label">Decoded</div><div class="output-decoded">${decoded}</div></div>`
    : '';
  return `${decodedSection}<div class="output-section"><div class="output-section-label">Raw</div><div class="output-hex">${hex}</div></div>`;
}
