import { describe, it, expect } from 'vitest';
import { renderWithProviders, screen } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectMetrics from './ProjectMetrics';

describe('ProjectMetrics', () => {
  it('renders headline totals, a by-rank breakdown, and changes since last release', async () => {
    server.use(
      http.get('/api/projects/3/metrics', () =>
        HttpResponse.json({
          acceptedByRank: { genus: 2, species: 10 },
          synonymsByRank: { species: 3 },
          supplementary: { reference: 5, typeMaterial: 1 },
          changesSinceLastRelease: { created: 4, updated: 2 },
          contributions: [],
        }),
      ),
    );
    renderWithProviders(<ProjectMetrics pid={3} />);

    // headline accepted total = 12 (2 + 10)
    expect(await screen.findByText('12')).toBeInTheDocument();
    // by-rank breakdown lists species
    expect(screen.getByText('species')).toBeInTheDocument();
    // changes-since-release entries
    expect(screen.getByText('created')).toBeInTheDocument();
  });
});
