import { expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AiSuggestModal from './AiSuggestModal';

test('shows synonym suggestions and accepts one into the tree', async () => {
  let created: Record<string, unknown> | null = null;
  let linkedUrl = '';
  server.use(
    http.post('/api/projects/1/usages/9/ai/suggest', () =>
      HttpResponse.json({
        provider: 'anthropic',
        model: 'claude-opus-4-8',
        synonyms: [
          {
            scientificName: 'Aus vetus',
            authorship: 'Mill.',
            nomStatus: null,
            reference: { doi: '10.1/x', citation: 'Good 1859', verified: true },
          },
        ],
        vernacularNames: [],
        distributions: [],
        descriptions: [],
        references: [{ doi: '10.1/x', citation: 'Good 1859', verified: true }],
        etymology: null,
      }),
    ),
    http.post('/api/projects/1/usages', async ({ request }) => {
      created = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: 100 }, { status: 201 });
    }),
    http.put('/api/projects/1/usages/100/synonym-of/9', ({ request }) => {
      linkedUrl = request.url;
      return new HttpResponse(null, { status: 204 });
    }),
  );

  render(
    <AiSuggestModal
      pid={1}
      usageId={9}
      usageRank="species"
      usageName="Aus bus"
      opened
      onClose={vi.fn()}
    />,
  );

  // the suggestion renders once the (mocked) suggest call resolves
  expect(await screen.findByText('Aus vetus')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Add synonym' }));

  // accept routes through the existing create + synonym-link endpoints
  await waitFor(() =>
    expect(created).toMatchObject({
      scientificName: 'Aus vetus',
      authorship: 'Mill.',
      rank: 'species',
      status: 'SYNONYM',
    }),
  );
  expect(linkedUrl).toContain('/usages/100/synonym-of/9');
});
