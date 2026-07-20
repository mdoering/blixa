import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import FavoriteClbDatasetsEditor from './FavoriteClbDatasetsEditor';

test('searching and clicking a dataset adds it as a favorite', async () => {
  const onChange = vi.fn();
  server.use(
    http.get('/api/clb/datasets', () =>
      HttpResponse.json([{ key: '3LXR', title: 'Catalogue of Life', alias: 'COL' }]),
    ),
  );
  renderWithProviders(<FavoriteClbDatasetsEditor value={[]} onChange={onChange} />);

  await userEvent.type(screen.getByLabelText('Search CLB datasets to favorite'), 'life');
  await userEvent.click(await screen.findByRole('button', { name: /Catalogue of Life/ }));

  expect(onChange).toHaveBeenCalledWith([{ key: '3LXR', title: 'Catalogue of Life' }]);
});

test('removing a favorite calls onChange without it', async () => {
  const onChange = vi.fn();
  renderWithProviders(
    <FavoriteClbDatasetsEditor
      value={[{ key: '3LXR', title: 'Catalogue of Life' }]}
      onChange={onChange}
    />,
  );

  await userEvent.click(screen.getByRole('button', { name: /Remove favorite Catalogue of Life/ }));
  expect(onChange).toHaveBeenCalledWith([]);
});
