import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import { server, http, HttpResponse } from '../../test/server';
import ClbClassificationPanel from './ClbClassificationPanel';

const preview = {
  currentParentId: null,
  currentParentName: null,
  steps: [
    { clbId: 'A', rank: 'kingdom', name: 'Animalia', authorship: null, matchId: null, inPlace: false, ambiguous: false },
    { clbId: 'F', rank: 'family', name: 'Felidae', authorship: null, matchId: 7, inPlace: true, ambiguous: false },
    { clbId: 'SF', rank: 'subfamily', name: 'Pantherinae', authorship: null, matchId: null, inPlace: false, ambiguous: false },
    { clbId: 'G', rank: 'genus', name: 'Panthera', authorship: 'Oken, 1816', matchId: null, inPlace: false, ambiguous: false },
  ],
};

function setup(onPost?: (body: unknown) => void) {
  server.use(
    http.get('/api/projects/3/usages/5/clb-classification', () => HttpResponse.json(preview)),
    http.post('/api/projects/3/usages/5/clb-classification', async ({ request }) => {
      onPost?.(await request.json());
      return HttpResponse.json({ parentId: 99, created: 1, moved: true });
    }),
  );
}

test('existing ranks are used, main missing ranks below them pre-selected, ranks above not creatable', async () => {
  setup();
  renderWithProviders(
    <ClbClassificationPanel pid={3} usageId={5} usageName="Panthera leo" datasetKey="3LXR" taxonId="T"
      onDone={() => {}} onCancel={() => {}} />,
  );
  expect(await screen.findByText('in tree')).toBeInTheDocument(); // Felidae
  expect(screen.getByText('not in tree')).toBeInTheDocument(); // Animalia, above Felidae
  expect(screen.queryByRole('checkbox', { name: 'Create Animalia' })).not.toBeInTheDocument();
  // pre-selection is applied once the preview has loaded
  await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Create Panthera' })).toBeChecked()); // main rank
  expect(screen.getByRole('checkbox', { name: 'Create Pantherinae' })).not.toBeChecked(); // intermediate
  expect(screen.getByText(/moves under/, { selector: 'p:not(:first-child)' })).toHaveTextContent('Panthera leo moves under Panthera (new).');
});

test('applying posts the selected ranks and reports back', async () => {
  let posted: unknown = null;
  const onDone = vi.fn();
  setup((b) => (posted = b));
  renderWithProviders(
    <ClbClassificationPanel pid={3} usageId={5} usageName="Panthera leo" datasetKey="3LXR" taxonId="T"
      onDone={onDone} onCancel={() => {}} />,
  );
  await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Create Panthera' })).toBeChecked());
  await userEvent.click(screen.getByRole('checkbox', { name: 'Create Pantherinae' }));
  await userEvent.click(screen.getByRole('button', { name: 'Wire into tree' }));
  await waitFor(() => expect(onDone).toHaveBeenCalled());
  expect(posted).toEqual({ datasetKey: '3LXR', taxonId: 'T', createClbIds: expect.arrayContaining(['G', 'SF']) });
});

test('unsaved copied values in the form block wiring', async () => {
  setup();
  renderWithProviders(
    <ClbClassificationPanel pid={3} usageId={5} usageName="Panthera leo" datasetKey="3LXR" taxonId="T"
      blockedReason="Save the values copied into the edit form first" onDone={() => {}} onCancel={() => {}} />,
  );
  expect(await screen.findByText(/save the values copied/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Wire into tree' })).toBeDisabled();
});
