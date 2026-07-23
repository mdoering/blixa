import { expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import BhlPageModal from './BhlPageModal';

test('shows name-suggested + all pages and picks one', async () => {
  const onPick = vi.fn();
  server.use(
    http.get('/api/projects/1/bhl/items/42/name-pages', () =>
      HttpResponse.json([
        { pageId: 9, pageNumber: '5', url: 'https://www.biodiversitylibrary.org/page/9', thumbnailUrl: null },
      ]),
    ),
    http.get('/api/projects/1/bhl/items/42/pages', () =>
      HttpResponse.json([
        { pageId: 9, pageNumber: '5', url: 'https://www.biodiversitylibrary.org/page/9', thumbnailUrl: null },
        { pageId: 10, pageNumber: '6', url: 'https://www.biodiversitylibrary.org/page/10', thumbnailUrl: null },
      ]),
    ),
  );

  render(
    <BhlPageModal pid={1} itemId={42} name="Aus bus" opened onClose={vi.fn()} onPick={onPick} />,
  );

  // the suggested section renders the name-matched page
  const pageLinks = await screen.findAllByText('p. 5');
  expect(pageLinks.length).toBeGreaterThan(0);

  const useButtons = await screen.findAllByRole('button', { name: 'Use this page' });
  await userEvent.click(useButtons[0]);
  await waitFor(() =>
    expect(onPick).toHaveBeenCalledWith({
      url: 'https://www.biodiversitylibrary.org/page/9',
      pageNumber: '5',
    }),
  );
});
