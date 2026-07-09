import { createTheme, type MantineColorsTuple } from '@mantine/core';

// A neutral blue-gray "slate" ramp (index 0 lightest → 9 darkest); index 6 is the default filled
// shade in light mode. Deliberately not one of Mantine's default hues — the editor is brand-neutral.
const slate: MantineColorsTuple = [
  '#f8fafc',
  '#f1f5f9',
  '#e2e8f0',
  '#cbd5e1',
  '#94a3b8',
  '#64748b',
  '#475569',
  '#334155',
  '#1e293b',
  '#0f172a',
];

export const theme = createTheme({
  primaryColor: 'slate',
  primaryShade: { light: 6, dark: 8 },
  colors: { slate },
  defaultRadius: 'sm',
});
