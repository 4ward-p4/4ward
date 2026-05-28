// Table entry CRUD and rendering, clone sessions.

import { state } from './state.js';
import { api } from './api.js';
import { encodeValue, allOnes } from './encoding.js';
import { log, updateTabBadges } from './ui.js';

const MATCH_TYPE = { EXACT: 'EXACT', LPM: 'LPM', TERNARY: 'TERNARY', OPTIONAL: 'OPTIONAL', RANGE: 'RANGE' };

function tableNeedsPriority(table) {
  return (table.match_fields || []).some(
    mf => mf.match_type === MATCH_TYPE.TERNARY || mf.match_type === MATCH_TYPE.RANGE
  );
}

function buildMatchFields(table) {
  const matchFields = [];
  const matchDisplay = [];

  for (const mf of table.match_fields || []) {
    const input = document.getElementById(`match-${mf.id}`);
    if (!input || !input.value.trim()) continue;
    const rawValue = input.value.trim();
    matchDisplay.push({ name: mf.name, value: rawValue });
    const fieldMatch = { field_id: mf.id };

    switch (mf.match_type) {
      case MATCH_TYPE.EXACT:
        fieldMatch.exact = { value: encodeValue(rawValue, mf.bitwidth) };
        break;
      case MATCH_TYPE.LPM: {
        const parts = rawValue.split('/');
        fieldMatch.lpm = {
          value: encodeValue(parts[0], mf.bitwidth),
          prefix_len: parseInt(parts[1] || mf.bitwidth, 10),
        };
        break;
      }
      case MATCH_TYPE.TERNARY: {
        const parts = rawValue.split('&&&');
        fieldMatch.ternary = {
          value: encodeValue(parts[0].trim(), mf.bitwidth),
          mask: parts[1] ? encodeValue(parts[1].trim(), mf.bitwidth) : allOnes(mf.bitwidth),
        };
        break;
      }
      case MATCH_TYPE.OPTIONAL:
        fieldMatch.optional = { value: encodeValue(rawValue, mf.bitwidth) };
        break;
      case MATCH_TYPE.RANGE: {
        const parts = rawValue.split('..').map(s => s.trim());
        if (parts.length !== 2 || !parts[0] || !parts[1]) {
          throw new Error(`${mf.name}: range matches use low..high`);
        }
        fieldMatch.range = {
          low: encodeValue(parts[0], mf.bitwidth),
          high: encodeValue(parts[1], mf.bitwidth),
        };
        break;
      }
      default:
        throw new Error(`${mf.name}: unsupported match type ${mf.match_type}`);
    }
    matchFields.push(fieldMatch);
  }

  return { matchFields, matchDisplay };
}

function buildActionParams(action) {
  const params = [];
  const paramDisplay = [];

  for (const param of action.params || []) {
    const input = document.getElementById(`param-${param.id}`);
    if (!input) continue;
    paramDisplay.push({ name: param.name, value: input.value.trim() });
    params.push({
      param_id: param.id,
      value: encodeValue(input.value.trim(), param.bitwidth),
    });
  }

  return { params, paramDisplay };
}

function buildTableEntry({ table, action, params, matchFields, isDefaultAction }) {
  const tableEntry = {
    table_id: table.preamble.id,
    action: {
      action: {
        action_id: action.preamble.id,
        params,
      },
    },
  };

  if (isDefaultAction) {
    tableEntry.is_default_action = true;
  } else {
    tableEntry.match = matchFields;
    if (tableNeedsPriority(table)) {
      tableEntry.priority = parseInt(document.getElementById('entry-priority').value, 10) || 1;
    }
  }

  return tableEntry;
}

function upsertDisplayedEntry(displayEntry) {
  if (!displayEntry.isDefault) {
    state.entries.push(displayEntry);
    return;
  }

  const existingDefaultIndex = state.entries.findIndex(
    entry => entry.isDefault && entry.raw.table_id === displayEntry.raw.table_id
  );
  if (existingDefaultIndex >= 0) {
    state.entries[existingDefaultIndex] = displayEntry;
  } else {
    state.entries.push(displayEntry);
  }
}

export async function addTableEntry() {
  const p4info = state.p4info;
  if (!p4info) return;

  const tableSelect = document.getElementById('entry-table');
  const actionSelect = document.getElementById('entry-action');
  const defaultActionInput = document.getElementById('entry-default-action');
  const table = p4info.tables[tableSelect.selectedIndex];
  const actionRef = table.action_refs[actionSelect.selectedIndex];
  const action = p4info.actions.find(a => a.preamble.id === actionRef.id);
  const isDefaultAction = defaultActionInput.checked;

  try {
    const { matchFields, matchDisplay } =
      isDefaultAction ? { matchFields: [], matchDisplay: [] } : buildMatchFields(table);
    const { params, paramDisplay } = buildActionParams(action);
    const tableEntry = buildTableEntry({ table, action, params, matchFields, isDefaultAction });

    const writeRequest = {
      device_id: '1',
      updates: [{
        type: isDefaultAction ? 'MODIFY' : 'INSERT',
        entity: { table_entry: tableEntry },
      }],
    };

    await api.write(writeRequest);

    const displayEntry = {
      tableName: table.preamble.name,
      actionName: action.preamble.name,
      matchFields: matchDisplay,
      params: paramDisplay,
      isDefault: isDefaultAction,
      raw: tableEntry,
    };

    upsertDisplayedEntry(displayEntry);

    renderEntriesList();
    updateTabBadges();
    log(`${isDefaultAction ? 'Default action updated' : 'Entry added'} for ${table.preamble.name}`, 'success');

    // Clear input fields for next entry
    if (!isDefaultAction) {
      for (const mf of table.match_fields || []) {
        const input = document.getElementById(`match-${mf.id}`);
        if (input) input.value = '';
      }
    }
    for (const param of action.params || []) {
      const input = document.getElementById(`param-${param.id}`);
      if (input) input.value = '';
    }
  } catch (e) {
    log(`Write failed: ${e.message}`, 'error');
  }
}

export async function deleteTableEntry(index) {
  const entry = state.entries[index];
  if (!entry) return;
  if (entry.isDefault) return;

  try {
    const deleteEntry = {
      table_id: entry.raw.table_id,
      match: entry.raw.match,
    };
    if (entry.raw.priority) deleteEntry.priority = entry.raw.priority;

    await api.write({
      device_id: '1',
      updates: [{
        type: 'DELETE',
        entity: { table_entry: deleteEntry },
      }],
    });

    state.entries.splice(index, 1);
    renderEntriesList();
    updateTabBadges();
    log(`Entry deleted from ${entry.tableName}`, 'success');
  } catch (e) {
    log(`Delete failed: ${e.message}`, 'error');
  }
}

export async function addCloneSession() {
  const sessionId = parseInt(document.getElementById('clone-session-id').value, 10);
  const egressPort = parseInt(document.getElementById('clone-egress-port').value, 10);

  if (isNaN(sessionId) || isNaN(egressPort)) {
    log('Enter valid session ID and egress port', 'error');
    return;
  }

  try {
    await api.write({
      device_id: '1',
      updates: [{
        type: 'INSERT',
        entity: {
          packet_replication_engine_entry: {
            clone_session_entry: {
              session_id: sessionId,
              replicas: [{ egress_port: egressPort }],
            },
          },
        },
      }],
    });

    state.cloneSessions.push({ sessionId, egressPort });
    renderCloneSessionsList();
    log(`Clone session ${sessionId} \u2192 port ${egressPort}`, 'success');
  } catch (e) {
    log(`Clone session failed: ${e.message}`, 'error');
  }
}

export async function deleteCloneSession(index) {
  const session = state.cloneSessions[index];
  if (!session) return;

  try {
    await api.write({
      device_id: '1',
      updates: [{
        type: 'DELETE',
        entity: {
          packet_replication_engine_entry: {
            clone_session_entry: {
              session_id: session.sessionId,
            },
          },
        },
      }],
    });

    state.cloneSessions.splice(index, 1);
    renderCloneSessionsList();
    log(`Clone session ${session.sessionId} deleted`, 'success');
  } catch (e) {
    log(`Delete failed: ${e.message}`, 'error');
  }
}

export function renderTablesPanel() {
  const p4info = state.p4info;
  document.getElementById('tables-empty').classList.toggle('hidden', !!p4info);
  document.getElementById('tables-loaded').classList.toggle('hidden', !p4info);

  if (!p4info) return;

  const tables = p4info.tables || [];
  const tableSelect = document.getElementById('entry-table');
  tableSelect.innerHTML = tables.map(t =>
    `<option value="${t.preamble.id}">${t.preamble.name}</option>`
  ).join('');

  document.getElementById('entry-default-action').checked = false;
  tableSelect.onchange = () => renderTableFields();
  renderTableFields();
}

export function renderTableFields() {
  const p4info = state.p4info;
  if (!p4info) return;

  const tableSelect = document.getElementById('entry-table');
  const table = p4info.tables[tableSelect.selectedIndex];
  if (!table) return;

  const defaultActionInput = document.getElementById('entry-default-action');
  const isDefaultAction = defaultActionInput.checked;

  const matchDiv = document.getElementById('match-fields');
  const matchFieldRows = (table.match_fields || [])
    .map(mf => {
      const label = `${mf.name} (${mf.match_type.toLowerCase()}, ${mf.bitwidth}b)`;
      const placeholder = matchPlaceholder(mf);
      return `
        <div class="form-row">
          <label for="match-${mf.id}">${label}</label>
          <input id="match-${mf.id}" class="input-full mono" placeholder="${placeholder}">
        </div>`;
    })
    .join('');
  matchDiv.innerHTML = isDefaultAction ? '' : matchFieldRows;

  const actionSelect = document.getElementById('entry-action');
  const actionRefs = table.action_refs || [];
  actionSelect.innerHTML = actionRefs.map(ref => {
    const action = p4info.actions.find(a => a.preamble.id === ref.id);
    return `<option value="${ref.id}">${action?.preamble.name || ref.id}</option>`;
  }).join('');

  actionSelect.onchange = () => renderActionParams();
  renderActionParams();

  document.getElementById('priority-row').style.display =
    !isDefaultAction && tableNeedsPriority(table) ? '' : 'none';
  document.getElementById('btn-add-entry').textContent =
    isDefaultAction ? 'Update Default Action' : 'Insert Entry';
}

function renderActionParams() {
  const p4info = state.p4info;
  if (!p4info) return;

  const actionSelect = document.getElementById('entry-action');
  const actionId = parseInt(actionSelect.value, 10);
  const action = p4info.actions.find(a => a.preamble.id === actionId);

  const paramsDiv = document.getElementById('action-params');
  if (!action || !action.params || action.params.length === 0) {
    paramsDiv.innerHTML = '';
    return;
  }

  paramsDiv.innerHTML = action.params.map(p =>
    `<div class="form-row">
      <label for="param-${p.id}">${p.name} (${p.bitwidth}b)</label>
      <input id="param-${p.id}" class="input-full mono" placeholder="0">
    </div>`
  ).join('');
}

function matchPlaceholder(mf) {
  switch (mf.match_type) {
    case MATCH_TYPE.EXACT: return `e.g. ${mf.bitwidth <= 16 ? '0x0800' : '10.0.0.1'}`;
    case MATCH_TYPE.LPM: return `e.g. 10.0.0.0/24`;
    case MATCH_TYPE.TERNARY: return `value &&& mask`;
    case MATCH_TYPE.OPTIONAL: return `exact value or leave empty`;
    case MATCH_TYPE.RANGE: return `low..high`;
    default: return '';
  }
}

export function renderEntriesList() {
  const list = document.getElementById('entries-list');
  if (state.entries.length === 0) {
    list.innerHTML = '<p class="empty-hint">No entries installed.</p>';
    return;
  }

  list.innerHTML = state.entries.map((entry, i) => {
    const staticCls = entry.isStatic ? ' static' : '';
    const staticBadge = entry.isStatic ? '<span class="entry-static-badge">const</span>' : '';
    const defaultBadge = entry.isDefault ? '<span class="entry-static-badge">default</span>' : '';
    const deleteBtn = entry.isStatic || entry.isDefault ? '' : `<button class="btn btn-danger btn-delete" data-delete-entry="${i}">&#x2715;</button>`;
    const matchText = entry.isDefault ? 'default action' : entry.matchFields.map(m => `${m.name}=${m.value}`).join(', ');
    return `<div class="entry-card${staticCls}">
      ${staticBadge}${defaultBadge}
      <div class="entry-table">${entry.tableName}</div>
      <div class="entry-match">${matchText}</div>
      <div class="entry-action">${'\u2192'} ${entry.actionName}(${entry.params.map(p => `${p.name}=${p.value}`).join(', ')})</div>
      ${deleteBtn}
    </div>`;
  }).join('');
}

export function renderCloneSessionsList() {
  const list = document.getElementById('clone-sessions-list');
  if (state.cloneSessions.length === 0) {
    list.innerHTML = '';
    return;
  }

  list.innerHTML = state.cloneSessions.map((s, i) =>
    `<div class="entry-card">
      <div class="entry-table">Session ${s.sessionId}</div>
      <div class="entry-action">${'\u2192'} port ${s.egressPort}</div>
      <button class="btn btn-danger btn-delete" data-delete-clone="${i}">&#x2715;</button>
    </div>`
  ).join('');
}
