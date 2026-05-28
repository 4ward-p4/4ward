// Pure value encoding/decoding helpers.

export function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Encode a user-supplied value into a base64 string (protobuf bytes wire format in JSON).
 * Supports decimal, hex (0x...), and dotted notation (10.0.0.1, AA:BB:CC:DD:EE:FF).
 */
export function encodeValue(input, bitwidth) {
  const byteLen = Math.ceil(bitwidth / 8);
  let bytes;

  if (input.includes('.')) {
    // Dotted decimal (IPv4-style)
    const parts = input.split('.').map(Number);
    bytes = new Uint8Array(parts);
  } else if (input.includes(':')) {
    // Colon-separated hex (MAC-style)
    const parts = input.split(':').map(s => parseInt(s, 16));
    bytes = new Uint8Array(parts);
  } else {
    // Numeric: decimal or hex (BigInt handles 0x prefix natively)
    let n = BigInt(input);
    bytes = new Uint8Array(byteLen);
    for (let i = byteLen - 1; i >= 0; i--) {
      bytes[i] = Number(n & 0xFFn);
      n >>= 8n;
    }
  }

  // Pad to expected byte length
  if (bytes.length < byteLen) {
    const padded = new Uint8Array(byteLen);
    padded.set(bytes, byteLen - bytes.length);
    bytes = padded;
  }

  return uint8ArrayToBase64(bytes);
}

export function allOnes(bitwidth) {
  const byteLen = Math.ceil(bitwidth / 8);
  const bytes = new Uint8Array(byteLen).fill(0xFF);
  // Mask off excess bits in the most significant byte
  const excessBits = byteLen * 8 - bitwidth;
  if (excessBits > 0) {
    bytes[0] = (0xFF >> excessBits);
  }
  return uint8ArrayToBase64(bytes);
}

export function uint8ArrayToBase64(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

export function base64ToUint8Array(b64) {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function bytesToHex(bytes, separator = ' ') {
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(separator);
}

export function base64ToHex(b64) {
  if (!b64) return '';
  return bytesToHex(base64ToUint8Array(b64));
}

export function decodeParamValue(b64) {
  if (!b64) return '0';
  const bytes = base64ToUint8Array(b64);
  let n = 0n;
  for (const b of bytes) n = (n << 8n) | BigInt(b);
  return n.toString();
}

// Field name patterns for contextual value formatting.
const MAC_PATTERN = /addr|mac/i;
const IPV4_PATTERN = /\b(ip|sip|dip|src_?addr|dst_?addr)/i;

/**
 * Format a field value for display based on field name and bitwidth.
 * Inverse of encodeValue: bytes → human-readable string.
 */
export function formatFieldValue(bytes, bitwidth, fieldName) {
  if (bytes.length === 0) return '0x0';
  if (bitwidth === 48 && MAC_PATTERN.test(fieldName)) {
    return bytesToHex(bytes, ':');
  }
  if (bitwidth === 32 && IPV4_PATTERN.test(fieldName)) {
    return Array.from(bytes).join('.');
  }
  return '0x' + bytesToHex(bytes, '');
}

/** Format bytes as a hex dump with offset markers every 16 bytes. */
export function formatHexDump(bytes) {
  if (bytes.length <= 16) return bytesToHex(bytes);
  const lines = [];
  for (let off = 0; off < bytes.length; off += 16) {
    const chunk = bytes.slice(off, off + 16);
    const offset = off.toString(16).padStart(4, '0');
    lines.push(`<span class="hex-offset">${offset}</span>  ${bytesToHex(chunk)}`);
  }
  return lines.join('\n');
}

/** Decode a raw table_entry proto into a display-friendly object using p4info metadata. */
export function decodeTableEntry(p4info, rawEntry) {
  const table = p4info?.tables?.find(t => t.preamble.id === rawEntry.table_id);
  const action = rawEntry.action?.action;
  const actionInfo = action ? p4info?.actions?.find(a => a.preamble.id === action.action_id) : null;
  return {
    tableName: table?.preamble.name || `table_${rawEntry.table_id}`,
    actionName: actionInfo?.preamble.name || 'unknown',
    matchFields: (rawEntry.match || []).map(m => {
      const mfInfo = table?.match_fields?.find(f => f.id === m.field_id);
      const name = mfInfo?.name || `field_${m.field_id}`;
      if (m.range) {
        return {
          name,
          value: `${base64ToHex(m.range.low)}..${base64ToHex(m.range.high)}`,
        };
      }
      const val = m.exact?.value || m.lpm?.value || m.ternary?.value || m.optional?.value || '';
      return { name, value: val ? base64ToHex(val) : '' };
    }),
    params: (action?.params || []).map(p => {
      const pInfo = actionInfo?.params?.find(pp => pp.id === p.param_id);
      return { name: pInfo?.name || `param_${p.param_id}`, value: p.value ? decodeParamValue(p.value) : '' };
    }),
    raw: rawEntry,
  };
}
