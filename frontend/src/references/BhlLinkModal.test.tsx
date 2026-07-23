import { expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import BhlLinkModal from './BhlLinkModal';

test('searches BHL and links a picked item to the reference', async () => {
  let linkedUrl = '';
  const onLinked = vi.fn();
  server.use(
    http.get('/api/projects/1/bhl/publication-search', () =>
      HttpResponse.json([
        {
          itemId: 42,
          title: 'Species Plantarum',
          authors: 'Linnaeus',
          year: '1753',
          url: 'https://www.biodiversitylibrary.org/item/42',
        },
      ]),
    ),
    http.put('/api/projects/1/references/5/bhl-item/42', ({ request }) => {
      linkedUrl = request.url;
      return HttpResponse.json({ id: 5, bhlItemId: 42 });
    }),
  );

  render(
    <BhlLinkModal
      pid={1}
      refId={5}
      prefill="Species Plantarum"
      opened
      onClose={vi.fn()}
      onLinked={onLinked}
    />,
  );

  await userEvent.click(screen.getByRole('button', { name: 'Search' }));
  expect(await screen.findByText('Species Plantarum')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Link' }));
  await waitFor(() => expect(onLinked).toHaveBeenCalledWith(42));
  expect(linkedUrl).toContain('/references/5/bhl-item/42');
});
