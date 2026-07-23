import { expect, test } from 'vitest';
import {
  MISSING_COLOR,
  areaPopupHtml,
  colorForEstablishment,
  legendFor,
} from './distributionStyle';

test('colorForEstablishment matches the ChecklistBank palette', () => {
  expect(colorForEstablishment('native')).toBe('#87C55F');
  expect(colorForEstablishment('introduced')).toBe('#FE88B1');
  // normalised: case + non-letters stripped ("Native endemic" -> "nativeendemic")
  expect(colorForEstablishment('Native endemic')).toBe('#0F8554');
  expect(colorForEstablishment('native-reintroduced')).toBe('#C9DB74');
});

test('unknown establishment falls back to the Uncertain colour; missing -> MISSING_COLOR', () => {
  expect(colorForEstablishment('something weird')).toBe('#8BE0A4'); // uncertain
  expect(colorForEstablishment(null)).toBe(MISSING_COLOR);
  expect(colorForEstablishment('')).toBe(MISSING_COLOR);
});

test('areaPopupHtml lists every populated field of the distribution record', () => {
  const html = areaPopupHtml({
    name: 'Panthera leo',
    area: null,
    areaId: 'AB',
    gazetteer: 'tdwg',
    establishmentMeans: 'native',
    threatStatus: 'least concern',
    referenceId: 42,
    remarks: 'widespread',
  });
  expect(html).toContain('Panthera leo');
  expect(html).toContain('tdwg:AB');
  expect(html).toContain('native');
  expect(html).toContain('least concern');
  expect(html).toContain('widespread');
  expect(html).toContain('42');
});

test('areaPopupHtml omits empty fields and escapes HTML', () => {
  const html = areaPopupHtml({
    name: '<b>x</b>',
    area: 'Freeland',
    areaId: null,
    gazetteer: null,
    establishmentMeans: null,
    threatStatus: null,
    referenceId: null,
    remarks: null,
  });
  expect(html).toContain('&lt;b&gt;x&lt;/b&gt;');
  expect(html).not.toContain('Establishment');
  expect(html).not.toContain('Threat');
});

test('legendFor returns the categories present, in palette order, Unspecified last', () => {
  const legend = legendFor([
    { establishmentMeans: 'introduced' },
    { establishmentMeans: 'native' },
    { establishmentMeans: null },
    { establishmentMeans: 'totally unknown' }, // -> Uncertain
  ]);
  expect(legend.map((e) => e.label)).toEqual(['Native', 'Introduced', 'Uncertain', 'Unspecified']);
  expect(legend.find((e) => e.label === 'Unspecified')?.color).toBe(MISSING_COLOR);
});
