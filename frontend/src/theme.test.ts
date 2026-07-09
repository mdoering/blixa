import { expect, test } from 'vitest';
import { theme } from './theme';

test('theme uses the custom slate primary with a full 10-shade ramp', () => {
  expect(theme.primaryColor).toBe('slate');
  expect(theme.colors?.slate).toHaveLength(10);
});
