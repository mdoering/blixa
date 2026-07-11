import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import AppFooter from './AppFooter';

test('shows the app name, version, mode, and a repository link', () => {
  renderWithProviders(<AppFooter />);
  expect(screen.getByText(new RegExp(`Blixa.*v${__APP_VERSION__}`))).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /github/i })).toBeInTheDocument();
});
