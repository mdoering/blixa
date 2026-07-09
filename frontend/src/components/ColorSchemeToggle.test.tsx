import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import ColorSchemeToggle from './ColorSchemeToggle';

test('toggles the color scheme and reflects the next target in its label', async () => {
  renderWithProviders(<ColorSchemeToggle />);
  // Default scheme is light → the button offers to switch to dark.
  const toggle = await screen.findByLabelText('Switch to dark mode');
  await userEvent.click(toggle);
  // After switching, it offers to go back to light.
  expect(await screen.findByLabelText('Switch to light mode')).toBeInTheDocument();
});
