// Display metadata per taxonomic status (upper-case enum-name wire form): the full label, a compact
// 3-letter abbreviation for narrow table columns, and a badge colour shared across the app.
export const STATUS_META: Record<string, { abbr: string; color: string; label: string }> = {
  ACCEPTED: { abbr: 'Acc', color: 'green', label: 'Accepted' },
  SYNONYM: { abbr: 'Syn', color: 'gray', label: 'Synonym' },
  MISAPPLIED: { abbr: 'Mis', color: 'orange', label: 'Misapplied' },
  UNASSESSED: { abbr: 'Una', color: 'blue', label: 'Unassessed' },
};

export function statusMeta(status: string): { abbr: string; color: string; label: string } {
  return STATUS_META[status] ?? { abbr: status.slice(0, 3), color: 'gray', label: status };
}
