import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import CreateProjectModal from './CreateProjectModal';

test('shows the backend error message when create fails', async () => {
  server.use(
    http.post('/api/projects', () => HttpResponse.json({ error: 'slug already used' }, { status: 409 })),
  );
  renderWithProviders(<CreateProjectModal open onClose={() => {}} />);

  await userEvent.type(screen.getByPlaceholderText('lepidoptera'), 'mam');
  await userEvent.type(screen.getByPlaceholderText('Lepidoptera of the World'), 'Mammals');
  await userEvent.click(screen.getByRole('button', { name: /create/i }));

  expect(await screen.findByText('slug already used')).toBeInTheDocument();
});
