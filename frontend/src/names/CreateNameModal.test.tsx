import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import CreateNameModal from './CreateNameModal';

const abies = { id: 5, scientificName: 'Abies' };

async function pickRank(rank: string) {
  // getByLabelText also matches the (aria-labelledby'd) options listbox once it's open, so
  // scope to the input's role instead.
  await userEvent.click(screen.getByRole('textbox', { name: 'Rank' }));
  await userEvent.click(await screen.findByRole('option', { name: rank }));
}

test('child mode POSTs an ACCEPTED usage under the anchor and reports the new id', async () => {
  let postBody: Record<string, unknown> | undefined;
  server.use(
    http.post('/api/projects/3/usages', async ({ request }) => {
      postBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: 42, scientificName: 'Abies alba', version: 0 }, { status: 201 });
    }),
  );
  const onCreated = vi.fn();
  renderWithProviders(
    <CreateNameModal
      pid={3}
      mode="child"
      anchor={abies}
      opened
      onClose={() => {}}
      onCreated={onCreated}
    />,
  );

  // The header shows the context.
  expect(screen.getByText('Abies')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Scientific name'), 'Abies alba');
  await pickRank('species');
  await userEvent.click(screen.getByRole('button', { name: /create/i }));

  await waitFor(() => expect(postBody).toBeDefined());
  expect(postBody?.scientificName).toBe('Abies alba');
  expect(postBody?.rank).toBe('species');
  expect(postBody?.status).toBe('ACCEPTED');
  expect(postBody?.parentId).toBe(5);
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(42));
});

test('synonym mode POSTs a SYNONYM usage (no parent) then links it to the anchor', async () => {
  let postBody: Record<string, unknown> | undefined;
  let linkedPath: string | undefined;
  server.use(
    http.post('/api/projects/3/usages', async ({ request }) => {
      postBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: 99, scientificName: 'Pinaster abies', version: 0 }, { status: 201 });
    }),
    http.put('/api/projects/3/usages/99/synonym-of/5', ({ request }) => {
      linkedPath = new URL(request.url).pathname;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const onCreated = vi.fn();
  renderWithProviders(
    <CreateNameModal
      pid={3}
      mode="synonym"
      anchor={abies}
      opened
      onClose={() => {}}
      onCreated={onCreated}
    />,
  );

  await userEvent.type(screen.getByLabelText('Scientific name'), 'Pinaster abies');
  await pickRank('species');
  await userEvent.click(screen.getByRole('button', { name: /create/i }));

  await waitFor(() => expect(postBody).toBeDefined());
  expect(postBody?.status).toBe('SYNONYM');
  expect(postBody?.parentId).toBeUndefined();
  await waitFor(() => expect(linkedPath).toBe('/api/projects/3/usages/99/synonym-of/5'));
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(99));
});

test('root mode POSTs an ACCEPTED usage with no parent', async () => {
  let postBody: Record<string, unknown> | undefined;
  server.use(
    http.post('/api/projects/3/usages', async ({ request }) => {
      postBody = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: 7, scientificName: 'Plantae', version: 0 }, { status: 201 });
    }),
  );
  const onCreated = vi.fn();
  renderWithProviders(
    <CreateNameModal pid={3} mode="root" opened onClose={() => {}} onCreated={onCreated} />,
  );

  expect(screen.getByText('New root name')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Scientific name'), 'Plantae');
  await pickRank('kingdom');
  await userEvent.click(screen.getByRole('button', { name: /create/i }));

  await waitFor(() => expect(postBody).toBeDefined());
  expect(postBody?.status).toBe('ACCEPTED');
  expect(postBody?.parentId).toBeUndefined();
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(7));
});
