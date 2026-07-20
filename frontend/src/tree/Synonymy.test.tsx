import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { render } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import Synonymy from './Synonymy';

const PAYLOAD = {
  homotypic: [
    { id: 2, scientificName: 'Ochlopoa annua', authorship: '(L.) H.Scholz', rank: 'species', status: 'SYNONYM', formattedName: 'Ochlopoa annua (L.) H.Scholz' },
  ],
  heterotypicGroups: [
    [
      { id: 3, scientificName: 'Aira pumila', authorship: 'Pursh', rank: 'species', status: 'SYNONYM', formattedName: 'Aira pumila Pursh' },
      { id: 4, scientificName: 'Catabrosa pumila', authorship: '(Pursh) Roem. & Schult.', rank: 'species', status: 'SYNONYM', formattedName: 'Catabrosa pumila (Pursh) Roem. & Schult.' },
    ],
  ],
  misapplied: [
    { id: 9, scientificName: 'Poa annua', authorship: 'auct.', rank: 'species', status: 'MISAPPLIED', formattedName: 'Poa annua auct.' },
  ],
};

describe('Synonymy', () => {
  beforeEach(() => {
    server.use(http.get('/api/projects/1/usages/1/synonymy', () => HttpResponse.json(PAYLOAD)));
  });

  it('renders homotypic recombinations, heterotypic groups nested, and misapplied', async () => {
    render(<Synonymy pid={1} usageId={1} canEdit={false} />);
    expect(await screen.findByText(/Ochlopoa annua/)).toBeInTheDocument();
    expect(screen.getByText(/Aira pumila/)).toBeInTheDocument();
    expect(screen.getByText(/Catabrosa pumila/)).toBeInTheDocument();
    expect(screen.getByText(/Poa annua auct\./)).toBeInTheDocument();
  });

  it('shows the Group synonyms button only when editable', async () => {
    const { rerender } = render(<Synonymy pid={1} usageId={1} canEdit={false} />);
    await screen.findByText(/Ochlopoa annua/);
    expect(screen.queryByRole('button', { name: /group synonyms/i })).not.toBeInTheDocument();
    rerender(<Synonymy pid={1} usageId={1} canEdit />);
    expect(await screen.findByRole('button', { name: /group synonyms/i })).toBeInTheDocument();
  });
});
