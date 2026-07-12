import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { useLocation } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AppSidebar from './AppSidebar';

function LocationEcho() {
  return <div data-testid="loc">{useLocation().pathname}</div>;
}

test('inside a project it lists the section links and navigates on click', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'T', role: 'editor' })),
  );
  renderWithProviders(
    <>
      <AppSidebar projectId={3} collapsed={false} />
      <LocationEcho />
    </>,
    { route: '/projects/3/tree' },
  );
  expect(screen.getByText('Tree')).toBeInTheDocument();
  expect(screen.getByText('Names')).toBeInTheDocument();
  // "Project" appears twice: the group heading and the section item.
  expect(screen.getAllByText('Project').length).toBeGreaterThan(0);
  expect(screen.getByText('Members')).toBeInTheDocument();

  await userEvent.click(screen.getByText('Names'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/projects/3/names');
});

test('with no project it shows only the Projects item', () => {
  renderWithProviders(<AppSidebar projectId={null} collapsed={false} />, { route: '/' });
  expect(screen.getByText('Projects')).toBeInTheDocument();
  expect(screen.queryByText('Tree')).not.toBeInTheDocument();
});

test('shows a pending-count badge on Members for an owner with pending join requests', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'T', role: 'owner' })),
    http.get('/api/projects/3/join-requests/count', () => HttpResponse.json({ count: 2 })),
  );
  renderWithProviders(<AppSidebar projectId={3} collapsed={false} />, { route: '/projects/3/tree' });

  await waitFor(() => expect(screen.getByText('2')).toBeInTheDocument());
});

test('does not query join-request count for a non-owner', async () => {
  let counted = false;
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json({ id: 3, title: 'T', role: 'editor' })),
    http.get('/api/projects/3/join-requests/count', () => {
      counted = true;
      return HttpResponse.json({ count: 2 });
    }),
  );
  renderWithProviders(<AppSidebar projectId={3} collapsed={false} />, { route: '/projects/3/tree' });

  await screen.findByText('Members');
  expect(counted).toBe(false);
});
