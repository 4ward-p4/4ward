// 4ward Playground — entry point.
// Vanilla JS with ES modules, no build step. Monaco editor loaded from CDN.

import { state } from './state.js';
import { api } from './api.js';
import { EXAMPLES } from './examples.js';
import { decodeTableEntry } from './encoding.js';
import { setStatus, log, switchTab, updateButtonStates, updateTabBadges, initResize, initGraphResize } from './ui.js';
import { initEditor, highlightCompileErrors, clearEditorDecorations } from './editor.js';
import { renderControlGraph } from './graph.js';
import { addTableEntry, deleteTableEntry, addCloneSession, deleteCloneSession, renderTableFields, renderTablesPanel, renderEntriesList, renderCloneSessionsList } from './tables.js';
import { sendPacket, applyPreset } from './packets.js';
import { switchTraceView, stepForward, stepBack, resetPlayback, applyPlaybackState } from './trace.js';

// ---------------------------------------------------------------------------
// Compile & Load (orchestrates across modules)
// ---------------------------------------------------------------------------

async function compileAndLoad() {
  const source = state.editor.getValue();
  setStatus('loading', 'Compiling\u2026');
  log('Compiling P4 program\u2026', 'info');
  const btn = document.getElementById('btn-compile');
  btn.disabled = true;
  btn.textContent = 'Compiling\u2026';
  clearEditorDecorations();
  state.playbackEvents = [];
  state.playbackPos = -1;
  state.controlGraph = null;
  state.activeGraphControl = null;
  document.getElementById('control-graph').classList.add('hidden');
  document.getElementById('graph-resize-handle').classList.add('hidden');

  try {
    const data = await api.compileAndLoad(source);
    state.p4info = data.p4info;
    state.headerTypes = data.header_types || null;
    state.entries = [];
    state.cloneSessions = [];
    renderCloneSessionsList();
    const nTables = (data.p4info.tables || []).length;
    const nActions = (data.p4info.actions || []).length;
    setStatus('loaded', `${nTables} table${nTables !== 1 ? 's' : ''}, ${nActions} action${nActions !== 1 ? 's' : ''}`);
    log('Pipeline loaded successfully', 'success');
    renderTablesPanel();
    updateButtonStates();

    // Auto-read entries (pipeline may have static entries from const entries).
    try {
      const readData = await api.read();
      if (readData.entities && readData.entities.length > 0) {
        for (const entity of readData.entities) {
          const te = entity.table_entry;
          if (!te) continue;
          state.entries.push({ ...decodeTableEntry(data.p4info, te), isStatic: true });
        }
        if (state.entries.length > 0) {
          log(`Pipeline loaded with ${state.entries.length} static entr${state.entries.length !== 1 ? 'ies' : 'y'}`, 'success');
        }
      }
    } catch (_) { /* static entry read is best-effort */ }

    renderEntriesList();
    updateTabBadges();

    // Render the control-flow graph (included in the compile response).
    if (data.control_graph) {
      state.controlGraph = data.control_graph;
      renderControlGraph();
    }
  } catch (e) {
    setStatus('error', 'Compilation failed');
    log(e.message, 'error');
    highlightCompileErrors(e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = 'Compile &amp; Load';
  }
}

// ---------------------------------------------------------------------------
// Event bindings & init
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
  // Tabs
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  // Buttons
  document.getElementById('btn-compile').addEventListener('click', compileAndLoad);
  document.getElementById('btn-add-entry').addEventListener('click', addTableEntry);
  document.getElementById('entry-default-action').addEventListener('change', renderTableFields);
  document.getElementById('btn-add-clone').addEventListener('click', addCloneSession);
  document.getElementById('btn-send-packet').addEventListener('click', sendPacket);

  // Playback controls
  document.getElementById('btn-step-back').addEventListener('click', stepBack);
  document.getElementById('btn-step-forward').addEventListener('click', stepForward);
  document.getElementById('btn-reset').addEventListener('click', resetPlayback);

  // Trace view toggle (Tree / JSON / Proto)
  document.querySelectorAll('.trace-view-btn').forEach(btn => {
    btn.addEventListener('click', () => switchTraceView(btn.dataset.view));
  });

  // Example selector (inline strings or file paths for larger examples).
  document.getElementById('example-select').addEventListener('change', async (e) => {
    const example = EXAMPLES[e.target.value];
    if (!example || !state.editor) return;
    state.loadingExample = true;
    if (example.startsWith('/')) {
      try {
        const resp = await fetch(example);
        state.editor.setValue(await resp.text());
      } catch (_) { /* ignore fetch errors */ }
    } else {
      state.editor.setValue(example);
    }
    state.loadingExample = false;
  });

  // Graph fullscreen toggle (browser Fullscreen API).
  const graphEl = document.getElementById('control-graph');
  document.getElementById('btn-graph-fullscreen').addEventListener('click', () => {
    if (document.fullscreenElement === graphEl) {
      document.exitFullscreen();
    } else {
      graphEl.requestFullscreen();
    }
  });

  // Resize
  initResize();
  initGraphResize();

  // Click trace events to navigate playback to that step.
  document.getElementById('trace-tree').addEventListener('click', (e) => {
    const el = e.target.closest('.trace-event[data-event-idx]');
    if (!el || !state.playbackEvents.length) return;
    const clickedIdx = parseInt(el.dataset.eventIdx, 10);
    const pos = state.playbackEvents.findIndex(pe => pe.eventIdx === clickedIdx);
    if (pos >= 0) {
      state.playbackPos = pos;
      applyPlaybackState();
    }
  });

  // Ctrl/Cmd+Enter on payload sends packet
  document.getElementById('pkt-payload').addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      sendPacket();
    }
  });

  // Packet preset buttons (event delegation).
  document.querySelectorAll('[data-preset]').forEach(btn => {
    btn.addEventListener('click', () => applyPreset(btn.dataset.preset));
  });

  // Table entry / clone session delete buttons (event delegation on dynamic HTML).
  document.getElementById('entries-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-delete-entry]');
    if (btn) deleteTableEntry(parseInt(btn.dataset.deleteEntry, 10));
  });
  document.getElementById('clone-sessions-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-delete-clone]');
    if (btn) deleteCloneSession(parseInt(btn.dataset.deleteClone, 10));
  });

  // Global keyboard shortcuts (suppressed when focus is in editor/input/textarea).
  function isEditing() {
    const el = document.activeElement;
    if (!el) return false;
    const tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable) return true;
    // Monaco uses a hidden textarea; its own API is the reliable check.
    if (state.editor?.hasTextFocus()) return true;
    return false;
  }

  document.addEventListener('keydown', (e) => {
    if (isEditing()) return;

    switch (e.key) {
      case 'ArrowRight': e.preventDefault(); stepForward(); break;
      case 'ArrowLeft': e.preventDefault(); stepBack(); break;
      case 'Escape': e.preventDefault(); resetPlayback(); break;
      case '1': switchTab('tables'); break;
      case '2': switchTab('packets'); break;
      case '3': switchTab('trace'); break;
    }
  });

  // Initialize Monaco editor
  const isMac = navigator.platform.includes('Mac');
  const mod = isMac ? '\u2318' : 'Ctrl';
  initEditor(compileAndLoad).then(() => {
    log(`Editor ready \u2014 ${mod}+Enter to compile & load \u00b7 \u2190\u2192 step trace \u00b7 1/2/3 switch tabs`, 'info');
  });
});
