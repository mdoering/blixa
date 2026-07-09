import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import CurrentProjectName from './CurrentProjectName';

test('shows the active project title', async () => {
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ id: 3, title: 'Felidae (sample data)', role: 'owner' }),
    ),
  );
  renderWithProviders(<CurrentProjectName projectId={3} />);
  expect(await screen.findByText('Felidae (sample data)')).toBeInTheDocument();
});

test('renders nothing when not inside a project', () => {
  // Wrap in a host node so the assertion sees only this component's output, not provider chrome.
  renderWithProviders(
    <div data-testid="host">
      <CurrentProjectName projectId={null} />
    </div>,
  );
  expect(screen.getByTestId('host')).toBeEmptyDOMElement();
});
