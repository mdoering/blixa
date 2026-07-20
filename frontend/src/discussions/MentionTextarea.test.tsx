import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { useState } from 'react';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import MentionTextarea from './MentionTextarea';

function Harness() {
  const [v, setV] = useState('');
  return <MentionTextarea pid={3} aria-label="Body" value={v} onChange={setV} />;
}

test('typing #Xyz suggests names and inserts the stable #id on select', async () => {
  server.use(
    http.get('/api/projects/3/usages', () =>
      HttpResponse.json({ items: [{ id: 7, scientificName: 'Panthera leo' }], total: 1 }),
    ),
  );
  renderWithProviders(<Harness />);

  const box = screen.getByLabelText('Body');
  await userEvent.type(box, 'see #Pan');

  // the name suggestion appears once the debounced search resolves
  await userEvent.click(await screen.findByText('Panthera leo'));

  // it inserts #<id> (the stable usage id), not the typed "#Pan" string
  await waitFor(() => expect(box).toHaveValue('see #7 '));
});

test('a lowercase #tag does not trigger the name autocomplete', async () => {
  let queried = false;
  server.use(
    http.get('/api/projects/3/usages', () => {
      queried = true;
      return HttpResponse.json({ items: [], total: 0 });
    }),
  );
  renderWithProviders(<Harness />);

  await userEvent.type(screen.getByLabelText('Body'), 'issue #123 and #abc');
  // neither a numeric id nor a lowercase token opens the capital-letter name autocomplete
  await new Promise((r) => setTimeout(r, 300));
  expect(queried).toBe(false);
});
