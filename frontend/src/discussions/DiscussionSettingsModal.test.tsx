import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DiscussionSettingsModal from './DiscussionSettingsModal';

test('shows the current token and regenerates it', async () => {
  let generated = false;
  server.use(
    http.get('/api/projects/3/discussion-token', () => HttpResponse.json({ token: 'secret-123' })),
    http.post('/api/projects/3/discussion-token', () => {
      generated = true;
      return HttpResponse.json({ token: 'secret-456' });
    }),
  );
  renderWithProviders(<DiscussionSettingsModal pid={3} opened onClose={() => {}} />);

  expect(await screen.findByDisplayValue('secret-123')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(generated).toBe(true));
});

test('offers to generate a token when none exists', async () => {
  server.use(
    http.get('/api/projects/3/discussion-token', () => HttpResponse.json({ token: null })),
  );
  renderWithProviders(<DiscussionSettingsModal pid={3} opened onClose={() => {}} />);

  expect(await screen.findByRole('button', { name: 'Generate token' })).toBeInTheDocument();
});
