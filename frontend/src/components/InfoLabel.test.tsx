import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import InfoLabel from './InfoLabel';

test('shows the label but keeps the help text behind the (i) icon until opened', async () => {
  renderWithProviders(
    <InfoLabel label="Status" info="Accepted ↔ synonym uses Demote/Promote" />,
  );
  expect(screen.getByText('Status')).toBeInTheDocument();
  // the description is not rendered inline -- it lives behind the info icon
  expect(screen.queryByText(/Demote\/Promote/)).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'More information' }));
  expect(await screen.findByText(/Demote\/Promote/)).toBeInTheDocument();
});

test('accepts a custom accessible label for the info button', () => {
  renderWithProviders(<InfoLabel label="Status" info="x" iconLabel="About status" />);
  expect(screen.getByRole('button', { name: 'About status' })).toBeInTheDocument();
});
