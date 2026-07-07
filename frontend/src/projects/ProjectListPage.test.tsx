import { screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectListPage from './ProjectListPage';

test('lists the user projects with their role', async () => {
  server.use(
    http.get('/api/projects', () =>
      HttpResponse.json([{ id: 1, slug: 'aves', title: 'Birds', role: 'owner' }]),
    ),
  );
  renderWithProviders(<ProjectListPage />);
  expect(await screen.findByText('Birds')).toBeInTheDocument();
  expect(screen.getByText('owner')).toBeInTheDocument();
});

test('shows empty state when there are no projects', async () => {
  renderWithProviders(<ProjectListPage />); // default handler returns []
  await waitFor(() => expect(screen.getByText('No projects yet')).toBeInTheDocument());
});
