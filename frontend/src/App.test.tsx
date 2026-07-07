import { screen } from '@testing-library/react';
import { test, expect } from 'vitest';
import { renderWithProviders } from './test/utils';
import App from './App';

test('renders the ColDP Editor brand', () => {
  renderWithProviders(<App />);
  expect(screen.getByText('ColDP Editor')).toBeInTheDocument();
});
