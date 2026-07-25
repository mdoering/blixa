import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ClassificationBar from './ClassificationBar';
import type { NameUsage } from '../api/types';

function makeUsage(partial: Partial<NameUsage>): NameUsage {
  return {
    id: 200,
    parentId: null,
    status: 'ACCEPTED',
    scientificName: 'Panthera leo',
    authorship: null,
    rank: 'species',
    acceptedParentIds: null,
    synonymIds: null,
    version: 0,
    // remaining fields are unused by ClassificationBar
    namePhrase: null,
    referenceId: null,
    extinct: null,
    environment: null,
    temporalRangeStart: null,
    temporalRangeEnd: null,
    uninomial: null,
    genus: null,
    infragenericEpithet: null,
    specificEpithet: 'leo',
    infraspecificEpithet: null,
    cultivarEpithet: null,
    notho: null,
    combinationAuthorship: null,
    combinationExAuthorship: null,
    combinationAuthorshipYear: null,
    basionymAuthorship: null,
    basionymExAuthorship: null,
    basionymAuthorshipYear: null,
    sanctioningAuthor: null,
    nomStatus: null,
    publishedInReferenceId: null,
    publishedInYear: null,
    publishedInPage: null,
    publishedInPageLink: null,
    gender: null,
    genderAgreement: null,
    genusGender: null,
    genusId: null,
    genusName: null,
    etymology: null,
    nameType: null,
    parseState: null,
    remarks: null,
    formattedName: null,
    ...partial,
  } as NameUsage;
}

const sixDeep = [
  { id: 1, scientificName: 'Animalia', rank: 'kingdom' },
  { id: 2, scientificName: 'Chordata', rank: 'phylum' },
  { id: 3, scientificName: 'Mammalia', rank: 'class' },
  { id: 4, scientificName: 'Carnivora', rank: 'order' },
  { id: 5, scientificName: 'Felidae', rank: 'family' },
  { id: 100, scientificName: 'Panthera', rank: 'genus' },
];

test('shows the closest ancestors, collapsing the rest into an ellipsis', async () => {
  server.use(http.get('/api/projects/7/tree/path/100', () => HttpResponse.json(sixDeep)));
  const onNavigate = vi.fn();
  renderWithProviders(
    <ClassificationBar
      pid={7}
      usage={makeUsage({ status: 'ACCEPTED', parentId: 100 })}
      canEdit={false}
      onNavigate={onNavigate}
    />,
  );
  // last 4 (closest) shown, the two highest collapsed behind …
  expect(await screen.findByText('Panthera')).toBeInTheDocument();
  expect(screen.getByText('…')).toBeInTheDocument();
  expect(screen.queryByText('Animalia')).not.toBeInTheDocument();
  expect(screen.queryByText('Chordata')).not.toBeInTheDocument();
  expect(screen.getByText('Mammalia')).toBeInTheDocument();

  // entries are links that navigate the form
  await userEvent.click(screen.getByText('Felidae'));
  expect(onNavigate).toHaveBeenCalledWith(5);
});

test('the change icon reparents an accepted taxon (opens the move modal)', async () => {
  server.use(
    http.get('/api/projects/7/tree/path/100', () =>
      HttpResponse.json([{ id: 100, scientificName: 'Panthera', rank: 'genus' }]),
    ),
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([])),
  );
  renderWithProviders(
    <ClassificationBar
      pid={7}
      usage={makeUsage({ status: 'ACCEPTED', parentId: 100, scientificName: 'Panthera leo' })}
      canEdit
    />,
  );
  await screen.findByText('Panthera');
  await userEvent.click(screen.getByRole('button', { name: 'Change parent' }));
  expect(await screen.findByRole('dialog')).toHaveTextContent('Move');
});

test('the change icon changes the accepted name of a synonym', async () => {
  server.use(
    http.get('/api/projects/7/tree/path/50', () =>
      HttpResponse.json([{ id: 50, scientificName: 'Panthera', rank: 'genus' }]),
    ),
    http.get('/api/projects/7/tree/roots', () => HttpResponse.json([])),
  );
  renderWithProviders(
    <ClassificationBar
      pid={7}
      usage={makeUsage({ status: 'SYNONYM', parentId: null, acceptedParentIds: [50] })}
      canEdit
    />,
  );
  await screen.findByText('Panthera');
  await userEvent.click(screen.getByRole('button', { name: 'Change parent' }));
  expect(await screen.findByRole('dialog')).toHaveTextContent('Change accepted name');
});

test('renders nothing for an accepted root (no parent)', () => {
  renderWithProviders(
    <ClassificationBar pid={7} usage={makeUsage({ status: 'ACCEPTED', parentId: null })} canEdit />,
  );
  expect(screen.queryByRole('button', { name: 'Change parent' })).not.toBeInTheDocument();
  expect(screen.queryByText('…')).not.toBeInTheDocument();
  expect(screen.queryByText('>')).not.toBeInTheDocument();
});

test('shows no change icon without edit rights', async () => {
  server.use(
    http.get('/api/projects/7/tree/path/100', () =>
      HttpResponse.json([{ id: 100, scientificName: 'Panthera', rank: 'genus' }]),
    ),
  );
  renderWithProviders(
    <ClassificationBar pid={7} usage={makeUsage({ status: 'ACCEPTED', parentId: 100 })} canEdit={false} />,
  );
  await screen.findByText('Panthera');
  expect(screen.queryByRole('button', { name: 'Change parent' })).not.toBeInTheDocument();
});
