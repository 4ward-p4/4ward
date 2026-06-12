// Trace tree: pure HTML generation from trace proto data.
// No cross-module side effects — only reads state.p4info for name resolution.

import { state } from './state.js';
import { escapeHtml, base64ToHex, base64ToUint8Array, decodeParamValue } from './encoding.js';
import { renderPacketSections } from './dissect.js';

// Global counter for stamping data-event-idx on rendered trace events.
let _eventIdx = 0;

// ---------------------------------------------------------------------------
// Stage iteration helper (shared by playback flatten and render)
// ---------------------------------------------------------------------------

/** Iterate events, pairing ENTER/EXIT pipeline_stage markers into groups. */
export function forEachStage(events, startIndex, onStage, onEvent) {
  let i = startIndex;
  while (i < events.length) {
    const event = events[i];
    if (event.pipeline_stage && event.pipeline_stage.direction === 'ENTER') {
      const stageName = event.pipeline_stage.stage_name;
      const innerEvents = [];
      i++;
      while (i < events.length) {
        if (events[i].pipeline_stage && events[i].pipeline_stage.direction === 'EXIT'
            && events[i].pipeline_stage.stage_name === stageName) {
          i++;
          break;
        }
        innerEvents.push(events[i]);
        i++;
      }
      onStage(stageName, innerEvents, event);
    } else {
      onEvent(event);
      i++;
    }
  }
}

// ---------------------------------------------------------------------------
// Trace node rendering
// ---------------------------------------------------------------------------

export function renderTraceNode(node, isRoot) {
  if (isRoot) _eventIdx = 0;
  const cls = isRoot ? 'trace-node root' : 'trace-node';
  let html = `<div class="${cls}">`;

  const events = node.events || [];
  let i = 0;

  // Top-level events before the first stage.
  while (i < events.length && !(events[i].pipeline_stage && events[i].pipeline_stage.direction === 'ENTER')) {
    html += renderTraceEvent(events[i]);
    i++;
  }

  // Stages in an indented container.
  let hasStages = false;
  let stagesHtml = '';
  forEachStage(events, i,
    (stageName, innerEvents) => {
      hasStages = true;
      stagesHtml += renderStageGroup(stageName, innerEvents);
    },
    (event) => {
      stagesHtml += renderTraceEvent(event);
    },
  );
  if (hasStages) {
    html += `<div class="trace-stages">${stagesHtml}</div>`;
  }

  // Outcome
  if (node.packet_outcome) {
    html += renderPacketOutcome(node.packet_outcome, node);
  } else if (node.continuations) {
    html += renderContinuations(node.continuations);
  } else if (node.choice) {
    html += renderChoice(node.choice);
  }

  html += '</div>';
  return html;
}

function renderStageGroup(name, events) {
  const esc = escapeHtml(name);
  if (events.length === 0) {
    return `<div class="trace-stage" data-stage="${esc}">
      <div class="trace-collapse empty">
        <span class="stage-name">${esc}</span>
      </div>
    </div>`;
  }

  return `<div class="trace-stage" data-stage="${esc}">
    <div class="trace-collapse" onclick="this.classList.toggle('collapsed')">
      <span class="stage-name">${esc}</span>
    </div>
    <div class="trace-stage-body">
      ${renderStageEvents(events)}
    </div>
  </div>`;
}

function formatActionParams(ae) {
  const params = Object.entries(ae.params || {}).map(([k, v]) =>
    `${k}=${decodeParamValue(v)}`
  ).join(', ');
  return params ? `(${params})` : '()';
}

function isActionEffect(event) {
  return event.mark_to_drop || event.extern_call || event.clone || event.clone_session_lookup
    || event.log_message || event.assertion;
}

function renderStageEvents(events) {
  let html = '';
  let i = 0;
  while (i < events.length) {
    if (events[i].action_execution) {
      const actionEvent = events[i];
      i++;
      const effects = [];
      while (i < events.length && isActionEffect(events[i])) {
        effects.push(events[i]);
        i++;
      }
      html += renderActionWithEffects(actionEvent, effects);
    } else {
      html += renderTraceEvent(events[i]);
      i++;
    }
  }
  return html;
}

function renderActionWithEffects(event, effects) {
  const ae = event.action_execution;
  const idx = _eventIdx++;
  const line = event.source_info?.line || 0;
  const attr = `data-event-idx="${idx}"${line ? ` data-line="${line}"` : ''}`;
  let detail = '';
  if (effects.length > 0) {
    const effectsHtml = effects.map(e => renderTraceEvent(e)).join('');
    detail = `<div class="trace-entry-detail" onclick="event.stopPropagation(); this.classList.toggle('expanded')">${effectsHtml}</div>`;
  }

  return `<div ${attr} class="trace-event action">Executed ${ae.action_name}${formatActionParams(ae)}${detail}</div>`;
}

function renderTraceEvent(event) {
  const idx = _eventIdx++;
  const line = event.source_info?.line || 0;
  const attr = `data-event-idx="${idx}"${line ? ` data-line="${line}"` : ''}`;
  let text = '';

  if (event.packet_ingress) {
    return `<div data-event-idx="${idx}" class="trace-event ingress">Received packet on port ${event.packet_ingress.dataplane_ingress_port}</div>`;
  }
  if (event.pipeline_stage) {
    const s = event.pipeline_stage;
    const dir = s.direction === 'ENTER' ? '\u2192' : '\u2190';
    const cls = s.direction === 'ENTER' ? 'stage-enter' : 'stage-exit';
    return `<div data-event-idx="${idx}" class="trace-event ${cls}">${dir} ${s.stage_name}</div>`;
  }
  if (event.parser_transition) {
    const pt = event.parser_transition;
    const condition = pt.select_value
      ? `${pt.select_expression ? escapeHtml(pt.select_expression) + ' = ' : ''}${escapeHtml(pt.select_value)}`
      : '';
    text = condition
      ? `Parsed ${pt.from_state} \u2192 ${pt.to_state} on ${condition}`
      : `Parsed ${pt.from_state} \u2192 ${pt.to_state}`;
  } else if (event.table_lookup) {
    const tl = event.table_lookup;
    const cls = tl.hit ? 'table-hit' : 'table-miss';
    const result = tl.hit ? 'hit' : 'miss';
    const detail = tl.hit && tl.matched_entry ? formatMatchedEntry(tl) : '';
    return `<div ${attr} class="trace-event ${cls}">Applied ${tl.table_name}: ${result} \u2192 ${tl.action_name}${detail}</div>`;
  } else if (event.action_execution) {
    const ae = event.action_execution;
    text = `Executed ${ae.action_name}${formatActionParams(ae)}`;
  } else if (event.branch) {
    const result = event.branch.taken ? 'true' : 'false';
    const frag = event.source_info?.source_fragment || '';
    text = frag ? `Branched on ${escapeHtml(frag)} \u2192 ${result}` : `Branched \u2192 ${result}`;
  } else if (event.extern_call) {
    const ec = event.extern_call;
    text = `Called ${ec.extern_instance_name}.${ec.method}()`;
  } else if (event.mark_to_drop) {
    text = 'Marked to drop';
  } else if (event.clone) {
    text = `Cloned to session ${event.clone.session_id}`;
  } else if (event.clone_session_lookup) {
    const csl = event.clone_session_lookup;
    text = csl.session_found
      ? `Resolved clone session ${csl.session_id} \u2192 port ${csl.dataplane_egress_port}`
      : `Clone session ${csl.session_id} not found (dropped)`;
    const cls = csl.session_found ? 'clone-session-hit' : 'clone-session-miss';
    return `<div ${attr} class="trace-event ${cls}">${text}</div>`;
  } else if (event.log_message) {
    return `<div ${attr} class="trace-event log-msg">log_msg: ${escapeHtml(event.log_message.message)}</div>`;
  } else if (event.assertion) {
    const result = event.assertion.passed ? 'passed' : 'FAILED';
    const cls = event.assertion.passed ? 'assert-pass' : 'assert-fail';
    return `<div ${attr} class="trace-event ${cls}">assert: ${result}</div>`;
  } else if (event.assignment) {
    text = `${escapeHtml(event.assignment.target)} = ${escapeHtml(event.result_value || '?')}`;
  } else {
    return '';
  }

  const cls = event.parser_transition ? 'parser'
    : event.action_execution ? 'action'
    : event.branch ? 'branch'
    : event.extern_call ? 'extern'
    : event.mark_to_drop ? 'mark-to-drop'
    : event.clone ? 'clone'
    : event.assignment ? 'assignment' : '';

  const vars = formatVariableValues(event.variable_values);
  return `<div ${attr} class="trace-event ${cls}">${text}${vars}</div>`;
}

function formatVariableValues(vars) {
  if (!vars || Object.keys(vars).length === 0) return '';
  const entries = Object.entries(vars)
    .map(([k, v]) => `${escapeHtml(k)}=${escapeHtml(v)}`)
    .join(', ');
  return ` <span class="trace-vars">[${entries}]</span>`;
}

function formatMatchedEntry(tl) {
  const entry = tl.matched_entry;
  if (!entry) return '';

  const table = state.p4info?.tables?.find(t => t.preamble.id === entry.table_id);
  const action = entry.action?.action;
  const actionInfo = action ? state.p4info?.actions?.find(a => a.preamble.id === action.action_id) : null;

  const parts = [];

  for (const m of entry.match || []) {
    const mfInfo = table?.match_fields?.find(f => f.id === m.field_id);
    const name = mfInfo?.name || `field_${m.field_id}`;
    if (m.exact) {
      parts.push(`${name} = ${base64ToHex(m.exact.value)}`);
    } else if (m.lpm) {
      parts.push(`${name} = ${base64ToHex(m.lpm.value)}/${m.lpm.prefix_len}`);
    } else if (m.ternary) {
      parts.push(`${name} = ${base64ToHex(m.ternary.value)} & ${base64ToHex(m.ternary.mask)}`);
    } else if (m.optional) {
      parts.push(`${name} = ${base64ToHex(m.optional.value)}`);
    } else if (m.range) {
      parts.push(`${name} = ${base64ToHex(m.range.low)}..${base64ToHex(m.range.high)}`);
    }
  }

  if (action?.params?.length) {
    const paramStrs = action.params.map(p => {
      const pInfo = actionInfo?.params?.find(pp => pp.id === p.param_id);
      return `${pInfo?.name || `param_${p.param_id}`} = ${decodeParamValue(p.value)}`;
    });
    parts.push(...paramStrs);
  }

  if (entry.priority) {
    parts.push(`priority = ${entry.priority}`);
  }

  if (parts.length === 0) return '';
  const detail = parts.map(p => escapeHtml(p)).join('\n');
  return `<div class="trace-entry-detail" onclick="event.stopPropagation(); this.classList.toggle('expanded')">${detail}</div>`;
}

function renderPacketOutcome(outcome, traceNode) {
  if (outcome.output) {
    const o = outcome.output;
    const bytes = o.payload ? base64ToUint8Array(o.payload) : new Uint8Array(0);
    let detail = '';
    if (traceNode && bytes.length > 0) {
      detail = `<div class="trace-outcome-detail">${renderPacketSections(bytes, traceNode)}</div>`;
    }
    return `<div class="trace-outcome output" data-outcome><div class="trace-outcome-header" onclick="this.parentElement.classList.toggle('collapsed')">\u2192 output port ${o.dataplane_egress_port} (${bytes.length} bytes)</div>${detail}</div>`;
  }
  if (outcome.drop) {
    const reason = formatDropReason(outcome.drop.reason);
    return `<div class="trace-outcome drop" data-outcome>\u2715 drop (reason: ${reason})</div>`;
  }
  return '';
}

// All continuations happen within one real execution (AND-node).
function renderContinuations(continuations) {
  let html = `<div class="trace-fork-label">\u2442 continues as</div>`;
  for (const c of continuations.continuations || []) {
    html += `<div class="trace-branch-label">${formatContinuation(c)}</div>`;
    html += renderTraceNode(c.subtree, false);
  }
  return html;
}

// Exactly one alternative happens; the simulator explores all of them (OR-node).
function renderChoice(choice) {
  let html = `<div class="trace-fork-label">\u2442 one of (action selector)</div>`;
  for (const alt of choice.alternatives || []) {
    html += `<div class="trace-branch-label">member ${alt.member_id}</div>`;
    html += renderTraceNode(alt.subtree, false);
  }
  return html;
}

function formatContinuation(c) {
  const kind = (c.kind || 'unknown').toLowerCase().replace(/_/g, ' ');
  let label = kind;
  if (c.dataplane_egress_port !== undefined) label += ` port ${c.dataplane_egress_port}`;
  if (c.instance !== undefined) label += ` instance ${c.instance}`;
  return label;
}

function formatDropReason(reason) {
  switch (reason) {
    case 'MARK_TO_DROP': return 'mark_to_drop';
    case 'PARSER_REJECT': return 'parser reject';
    case 'PIPELINE_EXECUTION_LIMIT_REACHED': return 'execution limit';
    default: return reason || 'unknown';
  }
}
