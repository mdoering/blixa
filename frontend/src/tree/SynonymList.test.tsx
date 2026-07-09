import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import SynonymList from './SynonymList';

test('accepted view lists synonyms and unlinks a row (synonym -> this accepted)', async () => {
  let deleted = '';
  server.use(
    http.get('/api/projects/7/usages/9/synonyms', () =>
      HttpResponse.json([{ id: 11, scientificName: 'Xus', authorship: null }]),
    ),
    http.delete('/api/projects/7/usages/11/synonym-of/9', () => {
      deleted = '11->9';
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderWithProviders(<SynonymList pid={7} usageId={9} status="ACCEPTED" canEdit />);
  await screen.findByText('Xus');
  await userEvent.click(screen.getByLabelText('Unlink Xus'));
  await waitFor(() => expect(deleted).toBe('11->9'));
});

test('synonym view lists accepteds and unlinks (this synonym -> accepted)', async () => {
  let deleted = '';
  server.use(
    http.get('/api/projects/7/usages/11/accepted', () =>
      HttpResponse.json([{ id: 9, scientificName: 'Aus', authorship: null }]),
    ),
    http.delete('/api/projects/7/usages/11/synonym-of/9', () => {
      deleted = '11->9';
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderWithProviders(<SynonymList pid={7} usageId={11} status="SYNONYM" canEdit />);
  await screen.findByText('Aus');
  await userEvent.click(screen.getByLabelText('Unlink Aus'));
  await waitFor(() => expect(deleted).toBe('11->9'));
});

test('read-only hides the unlink control', async () => {
  server.use(
    http.get('/api/projects/7/usages/9/synonyms', () =>
      HttpResponse.json([{ id: 11, scientificName: 'Xus', authorship: null }]),
    ),
  );
  renderWithProviders(<SynonymList pid={7} usageId={9} status="ACCEPTED" />);
  await screen.findByText('Xus');
  expect(screen.queryByLabelText('Unlink Xus')).not.toBeInTheDocument();
});
