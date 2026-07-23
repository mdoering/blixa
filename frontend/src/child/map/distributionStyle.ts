// Distribution styling shared by MapView (maplibre paint + click popup) and DistributionMapPanel
// (legend). Colours mirror ChecklistBank's establishment-means palette so the editor's map reads the
// same as the public CoL/CLB distribution map. Kept free of maplibre-gl so it unit-tests in jsdom.

export interface EstablishmentMean {
  key: string;
  label: string;
  color: string;
}

// ChecklistBank's establishment-means palette (checklistbank DistributionsMap ESTABLISHMENT_MEANS).
export const ESTABLISHMENT_MEANS: EstablishmentMean[] = [
  { key: 'nativeendemic', label: 'Native endemic', color: '#0F8554' },
  { key: 'native', label: 'Native', color: '#87C55F' },
  { key: 'nativereintroduced', label: 'Native reintroduced', color: '#C9DB74' },
  { key: 'introduced', label: 'Introduced', color: '#FE88B1' },
  { key: 'introducedassistedcolonisation', label: 'Introduced assisted colonisation', color: '#DCB0F2' },
  { key: 'vagrant', label: 'Vagrant', color: '#F6CF71' },
  { key: 'uncertain', label: 'Uncertain', color: '#8BE0A4' },
];

// A coded area with no establishment means (CLB's MISSING_COLOR).
export const MISSING_COLOR = '#66C5CC';

const COLORS: Record<string, string> = Object.fromEntries(
  ESTABLISHMENT_MEANS.map((m) => [m.key, m.color]),
);
const LABELS: Record<string, string> = Object.fromEntries(
  ESTABLISHMENT_MEANS.map((m) => [m.key, m.label]),
);

// Lower-case + strip non-letters so "Native endemic", "native-endemic", "NativeEndemic" all match
// the "nativeendemic" key (CLB's normalizeKey).
function normalizeKey(v: string): string {
  return v.toLowerCase().replace(/[^a-z]/g, '');
}

// The canonical establishment key for a raw value, or null when unset. An unrecognised non-empty
// value maps to "uncertain" (CLB's resolveKey), so it still gets a colour rather than the missing one.
function resolveKey(em: string | null | undefined): string | null {
  if (em == null || em === '') return null;
  const k = normalizeKey(em);
  return COLORS[k] ? k : 'uncertain';
}

export function colorForEstablishment(em: string | null | undefined): string {
  const k = resolveKey(em);
  return k == null ? MISSING_COLOR : COLORS[k];
}

function escapeHtml(s: unknown): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// The full distribution record shown in a map click popover -- every populated field. Properties come
// from the GeoJSON feature (set in MapView from the MapAreaRecord); values may be strings/numbers.
export interface AreaPopupProps {
  name?: string | null;
  area?: string | null;
  areaId?: string | null;
  gazetteer?: string | null;
  establishmentMeans?: string | null;
  threatStatus?: string | null;
  referenceId?: number | string | null;
  remarks?: string | null;
}

export function areaPopupHtml(p: AreaPopupProps): string {
  const areaLabel = [
    p.area,
    p.areaId ? (p.gazetteer ? `${p.gazetteer}:${p.areaId}` : p.areaId) : null,
  ]
    .filter((v) => v != null && v !== '')
    .join(' · ');
  const fields: [string, unknown][] = [
    ['Area', areaLabel],
    ['Establishment', p.establishmentMeans],
    ['Threat status', p.threatStatus],
    ['Reference', p.referenceId != null && p.referenceId !== '' ? `#${p.referenceId}` : null],
    ['Remarks', p.remarks],
  ];
  const rows = fields
    .filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `<div><strong>${escapeHtml(k)}:</strong> ${escapeHtml(v)}</div>`)
    .join('');
  const title =
    p.name != null && p.name !== ''
      ? `<div style="font-weight:600;font-style:italic;margin-bottom:4px">${escapeHtml(p.name)}</div>`
      : '';
  return `<div style="min-width:180px">${title}${rows}</div>`;
}

export interface LegendEntry {
  label: string;
  color: string;
}

// The establishment categories actually present in a set of distributions, in palette order, with
// "Unspecified" (MISSING_COLOR) last when any distribution has no establishment means -- the map legend.
export function legendFor(distributions: { establishmentMeans?: string | null }[]): LegendEntry[] {
  const present = new Set<string>();
  let hasUnspecified = false;
  for (const d of distributions) {
    const k = resolveKey(d.establishmentMeans);
    if (k == null) hasUnspecified = true;
    else present.add(k);
  }
  const entries: LegendEntry[] = ESTABLISHMENT_MEANS.filter((m) => present.has(m.key)).map((m) => ({
    label: LABELS[m.key],
    color: m.color,
  }));
  if (hasUnspecified) entries.push({ label: 'Unspecified', color: MISSING_COLOR });
  return entries;
}
