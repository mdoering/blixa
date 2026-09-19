import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import { makeUsage } from '../test/fixtures';
import NameHeader from './NameHeader';

test('shows the full name with authorship, rank and status', () => {
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ scientificName: 'Homo sapiens', authorship: 'Linnaeus, 1758', rank: 'species' })}
      canEdit={false}
    />,
  );
  expect(screen.getByRole('heading', { name: 'Homo sapiens Linnaeus, 1758' })).toBeInTheDocument();
  expect(screen.getByText('species')).toBeInTheDocument();
  expect(screen.getByText('Accepted')).toBeInTheDocument();
});

test('a synonym links to its accepted name, shown with authorship', async () => {
  server.use(
    http.get('/api/projects/7/usages/50', () =>
      HttpResponse.json(makeUsage({ id: 50, scientificName: 'Homo rudolfensis', authorship: '(Alekseyev, 1986)' })),
    ),
  );
  const onNavigate = vi.fn();
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Australopithecus rudolfensis', acceptedParentIds: [50] })}
      canEdit={false}
      onNavigate={onNavigate}
    />,
  );
  expect(screen.getByText('Synonym of')).toBeInTheDocument();
  await userEvent.click(await screen.findByText('Homo rudolfensis (Alekseyev, 1986)'));
  expect(onNavigate).toHaveBeenCalledWith(50);
});

test('flags a linked "accepted" name that is not actually accepted', async () => {
  server.use(
    http.get('/api/projects/7/usages/50', () =>
      HttpResponse.json(makeUsage({ id: 50, scientificName: 'Homo rudolfensis', status: 'UNASSESSED' })),
    ),
  );
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Australopithecus rudolfensis', acceptedParentIds: [50] })}
      canEdit={false}
    />,
  );
  expect(await screen.findByText('Homo rudolfensis')).toBeInTheDocument();
  expect(screen.getByText('Unassessed')).toBeInTheDocument();
});

test('lists every accepted name of a pro parte synonym', async () => {
  server.use(
    http.get('/api/projects/7/usages/50', () => HttpResponse.json(makeUsage({ id: 50, scientificName: 'Aus bus' }))),
    http.get('/api/projects/7/usages/51', () => HttpResponse.json(makeUsage({ id: 51, scientificName: 'Aus cus' }))),
  );
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Aus dus', acceptedParentIds: [50, 51] })}
      canEdit={false}
    />,
  );
  expect(await screen.findByText('Aus bus')).toBeInTheDocument();
  expect(await screen.findByText('Aus cus')).toBeInTheDocument();
});

test('says so when a synonym has no accepted name at all', () => {
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Aus dus', acceptedParentIds: [] })}
      canEdit
    />,
  );
  expect(screen.getByText(/not linked to an accepted name/i)).toBeInTheDocument();
});

test('the change icon changes the accepted name of a synonym', async () => {
  server.use(
    http.get('/api/projects/7/usages/50', () => HttpResponse.json(makeUsage({ id: 50, scientificName: 'Panthera leo' }))),
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([])),
  );
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Felis leo', acceptedParentIds: [50] })}
      canEdit
    />,
  );
  await screen.findByText('Panthera leo');
  await userEvent.click(screen.getByRole('button', { name: 'Change accepted name' }));
  expect(await screen.findByRole('dialog')).toHaveTextContent('Change accepted name');
});

test('no change icon without edit rights', async () => {
  server.use(
    http.get('/api/projects/7/usages/50', () => HttpResponse.json(makeUsage({ id: 50, scientificName: 'Panthera leo' }))),
  );
  renderWithProviders(
    <NameHeader
      pid={7}
      usage={makeUsage({ id: 60, status: 'SYNONYM', scientificName: 'Felis leo', acceptedParentIds: [50] })}
      canEdit={false}
    />,
  );
  await screen.findByText('Panthera leo');
  expect(screen.queryByRole('button', { name: 'Change accepted name' })).not.toBeInTheDocument();
});
