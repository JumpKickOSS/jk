// SPDX-License-Identifier: Apache-2.0
// The Machine page, on the Windows engine: the host and every WSL distro with the JumpKick it has,
// whether its engine is paired with this front door, and an Install or Remove button that runs the
// one operation `jk self setup-wsl`, the setup decision and the MCP `machine` tool run
// (`/api/machine/environments`). A distro declined in the setup decision installs from here like
// any other. Progress is read every two seconds while an operation runs; each outcome is also in
// the Activity page. The Machine tab shows only where that API answers.
import { get, post } from './api.js';

export const MACHINE_POLL_MS = 2000;

export function machineData() {
  return {
    // GET /api/machine/environments: { host, environments }; null until read or when unavailable.
    machine: null,
    // Whether this engine serves the machine view at all (the Windows engine does).
    machineAvailable: false,
    // A refusal or failure to show above the table: { name, text } or null.
    machineError: null,
  };
}

/** One distro's state as the table reads it. */
export function stateLabel(row) {
  switch (row && row.state) {
    case 'installed':
      return row.jumpKick || 'JumpKick';
    case 'not-installed':
      return 'No JumpKick';
    case 'wsl1':
      return 'WSL 1 (JumpKick needs WSL 2)';
    default:
      return 'Stopped (not started to look)';
  }
}

/** True while {@code row}'s operation is queued or running. */
export function busy(row) {
  const op = row && row.operation;
  return !!op && (op.state === 'queued' || op.state === 'running');
}

/**
 * What the row's button does: 'remove' where JumpKick is installed, 'install' otherwise, and
 * nothing for WSL 1 or while an operation runs. A stopped distro may hold JumpKick or not; it is
 * offered Install, which reports what it finds.
 */
export function rowAction(row) {
  if (!row || busy(row) || row.state === 'wsl1') return null;
  return row.state === 'installed' ? 'remove' : 'install';
}

/** The pairing column: paired, listed but not paired, or nothing seen. */
export function pairingLabel(row) {
  const e = row && row.engine;
  if (!e) return row && row.state === 'installed' ? 'Engine not seen' : '';
  return e.paired ? 'Paired' : 'Not paired';
}

/** The operation column: its action, state and latest step, or empty. */
export function operationLabel(row) {
  const op = row && row.operation;
  if (!op) return '';
  const verb = op.action === 'remove' ? 'Remove' : 'Install';
  const state = { queued: 'queued', running: 'running', done: 'done', failed: 'failed' }[op.state] || op.state;
  return `${verb} ${state}: ${op.step}`;
}

export const machineMethods = {
  /** Reads the environments; marks the view unavailable on an engine without the API. */
  async loadMachine() {
    try {
      this.machine = await get('/api/machine/environments');
      this.machineAvailable = true;
    } catch {
      this.machineAvailable = false;
    }
    this.pollMachine();
  },
  /** Reads again in two seconds while an operation runs and the Machine page is open. */
  pollMachine() {
    const rows = (this.machine && this.machine.environments) || [];
    if (this._machineTimer || this.view !== 'machine' || !rows.some(busy)) return;
    this._machineTimer = setTimeout(() => {
      this._machineTimer = null;
      if (this.view === 'machine') this.loadMachine();
    }, MACHINE_POLL_MS);
  },
  /** Install into, or remove from, distro {@code name}: {@code action} is 'install' or 'remove'. */
  async machineAction(name, action) {
    if (action !== 'install' && action !== 'remove') return;
    this.machineError = null;
    try {
      const started = await post('/api/machine/environments/' + action, { distros: [name] });
      const refused = started && started.refused ? Object.entries(started.refused) : [];
      if (refused.length) this.machineError = { name, text: refused[0][1] };
    } catch (e) {
      // A 409 names each refusal in its body; anything else names its error.
      const refused = e && e.body && e.body.refused ? Object.values(e.body.refused) : [];
      const text = refused.length ? refused[0] : (e && e.error) || 'the engine did not take it';
      this.machineError = { name, text };
    }
    await this.loadMachine();
  },
};
