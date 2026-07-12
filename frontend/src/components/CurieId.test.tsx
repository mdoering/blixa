import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import CurieId, { resolveIdentifierUrl } from './CurieId';

test('resolveIdentifierUrl appends the encoded id to a plain base link', () => {
  const scopes = [{ scope: 'ipni', title: 'IPNI', link: 'https://www.ipni.org' }];
  expect(resolveIdentifierUrl('ipni', '77-1', scopes)).toBe('https://www.ipni.org/77-1');
});

test('resolveIdentifierUrl trims a trailing slash before appending the id', () => {
  const scopes = [{ scope: 'ipni', title: 'IPNI', link: 'https://www.ipni.org/' }];
  expect(resolveIdentifierUrl('ipni', '77-1', scopes)).toBe('https://www.ipni.org/77-1');
});

test('resolveIdentifierUrl substitutes a {id} placeholder instead of appending', () => {
  const scopes = [{ scope: 'x', title: null, link: 'https://example.org/name/{id}/details' }];
  expect(resolveIdentifierUrl('x', '42', scopes)).toBe('https://example.org/name/42/details');
});

test('resolveIdentifierUrl matches scope case-insensitively and URL-encodes the id', () => {
  const scopes = [{ scope: 'col', title: null, link: 'https://www.catalogueoflife.org' }];
  expect(resolveIdentifierUrl('COL', 'a b', scopes)).toBe(
    'https://www.catalogueoflife.org/a%20b',
  );
});

test('resolveIdentifierUrl returns null when the scope has no link or is unknown', () => {
  const scopes = [{ scope: 'tsn', title: 'ITIS TSN', link: null }];
  expect(resolveIdentifierUrl('tsn', '123', scopes)).toBeNull();
  expect(resolveIdentifierUrl('nope', '123', scopes)).toBeNull();
});

test('renders the CURIE as a link to the resolved URL when the scope has a resolver link', async () => {
  server.use(
    http.get('/api/coldp/id-scopes', () =>
      HttpResponse.json([{ scope: 'ipni', title: 'IPNI', link: 'https://www.ipni.org' }]),
    ),
  );
  renderWithProviders(<CurieId scope="ipni" id="77-1" />);

  const link = await screen.findByRole('link', { name: 'ipni:77-1' });
  expect(link).toHaveAttribute('href', 'https://www.ipni.org/77-1');
  expect(link).toHaveAttribute('target', '_blank');
});

test('renders plain text (no link) when the scope has no resolver link', async () => {
  server.use(
    http.get('/api/coldp/id-scopes', () =>
      HttpResponse.json([{ scope: 'tsn', title: 'ITIS TSN', link: null }]),
    ),
  );
  renderWithProviders(<CurieId scope="tsn" id="123" />);

  expect(await screen.findByText('tsn:123')).toBeInTheDocument();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
});

test('shows the edit icon only when editable and onEdit are both set, and clicking it calls onEdit', async () => {
  server.use(
    http.get('/api/coldp/id-scopes', () =>
      HttpResponse.json([{ scope: 'ipni', title: 'IPNI', link: 'https://www.ipni.org' }]),
    ),
  );
  const onEdit = vi.fn();
  renderWithProviders(<CurieId scope="ipni" id="77-1" editable onEdit={onEdit} />);

  await screen.findByText('ipni:77-1');
  const icon = screen.getByRole('button', { name: 'Edit identifier' });
  await userEvent.click(icon);
  expect(onEdit).toHaveBeenCalledTimes(1);
});

test('renders no edit icon when editable is false (default), even with an onEdit handler', async () => {
  const onEdit = vi.fn();
  renderWithProviders(<CurieId scope="ipni" id="77-1" onEdit={onEdit} />);

  await screen.findByText('ipni:77-1');
  expect(screen.queryByRole('button', { name: 'Edit identifier' })).not.toBeInTheDocument();
});

test('renders no edit icon when editable is true but no onEdit handler is given', async () => {
  renderWithProviders(<CurieId scope="ipni" id="77-1" editable />);

  await waitFor(() => expect(screen.getByText('ipni:77-1')).toBeInTheDocument());
  expect(screen.queryByRole('button', { name: 'Edit identifier' })).not.toBeInTheDocument();
});
