import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectSwitcher from './ProjectSwitcher';

test('shows the current project from the route as its value', async () => {
  server.use(
    http.get('/api/projects', () =>
      HttpResponse.json([
        { id: 3, title: 'Felidae', role: 'owner' },
        { id: 9, title: 'Birds', role: 'editor' },
      ]),
    ),
  );
  renderWithProviders(<ProjectSwitcher />, { route: '/projects/3/tree' });
  // Mantine Select renders the selected option's label in its input.
  expect(await screen.findByDisplayValue('Felidae')).toBeInTheDocument();
});
