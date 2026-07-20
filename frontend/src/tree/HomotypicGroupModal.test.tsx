import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import HomotypicGroupModal from './HomotypicGroupModal';

const PROPOSAL = {
  groups: [
    {
      basionymUsageId: 1,
      memberUsageIds: [1, 2],
      relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym', alreadyExists: false }],
    },
  ],
};

describe('HomotypicGroupModal', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/projects/1/usages/1/homotypic/detect', () => HttpResponse.json(PROPOSAL)),
      http.get('/api/projects/1/usages/:id', ({ params }) =>
        HttpResponse.json({ id: Number(params.id), scientificName: `Name ${params.id}`, authorship: null, version: 0 }),
      ),
    );
  });

  it('shows detected relations and applies the checked ones', async () => {
    let posted: unknown = null;
    server.use(
      http.post('/api/projects/1/usages/1/homotypic/apply', async ({ request }) => {
        posted = await request.json();
        return HttpResponse.json({ homotypic: [], heterotypicGroups: [], misapplied: [] });
      }),
    );
    render(<HomotypicGroupModal pid={1} usageId={1} onClose={() => {}} />);
    expect(await screen.findByText(/basionym/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /apply/i }));
    await waitFor(() =>
      expect(posted).toEqual({ relations: [{ usageId: 2, relatedUsageId: 1, type: 'basionym' }] }),
    );
  });
});
