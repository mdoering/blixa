// The active work objective (an OPEN discussion id) per project, held client-side in localStorage.
// Shared by the header ActiveObjectiveSelector and the api() client, which sends X-Objective-Id on
// writes so the backend stamps each change (and lock) with it. "None" is the default and
// first-class: no stored id -> no header -> ungrouped changes, i.e. ordinary editing is unencumbered.
// Kept free of React so the api() client can read it at request time.

export function activeObjectiveKey(pid: number): string {
  return `coldp-active-objective-${pid}`;
}

export function readActiveObjectiveId(pid: number): number | null {
  try {
    const raw = localStorage.getItem(activeObjectiveKey(pid));
    if (!raw) return null;
    const n = Number(raw);
    return Number.isInteger(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}

export function writeActiveObjectiveId(pid: number, id: number | null): void {
  try {
    if (id == null) localStorage.removeItem(activeObjectiveKey(pid));
    else localStorage.setItem(activeObjectiveKey(pid), String(id));
  } catch {
    /* localStorage unavailable — the objective is optional, so degrade silently */
  }
}
