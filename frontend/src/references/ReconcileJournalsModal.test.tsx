import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import userEvent from '@testing-library/user-event';
import ReconcileJournalsModal from './ReconcileJournalsModal';
import * as referencesApi from '../api/references';

describe('ReconcileJournalsModal', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('facets journal names, defaults the canonical to the higher-count value, and merges', async () => {
    const facet = vi.spyOn(referencesApi, 'getContainerTitleFacet').mockResolvedValue([
      { value: 'J. Bot.', count: 2 },
      { value: 'Journal of Botany', count: 1 },
    ]);
    const merge = vi.spyOn(referencesApi, 'mergeContainerTitle').mockResolvedValue({ updated: 3 });

    render(<ReconcileJournalsModal pid={1} opened onClose={() => {}} />);

    expect(await screen.findByText('J. Bot. (2)')).toBeInTheDocument();
    expect(screen.getByText('Journal of Botany (1)')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('checkbox', { name: 'J. Bot. (2)' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Journal of Botany (1)' }));

    // Canonical defaults to the higher-count checked value, without any radio interaction.
    expect(screen.getByRole('radio', { name: 'J. Bot.' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Journal of Botany' })).not.toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: /merge 2 journal names into/i }));

    await waitFor(() =>
      expect(merge).toHaveBeenCalledWith(1, 'J. Bot.', ['J. Bot.', 'Journal of Botany']),
    );
    // Success invalidates the facet query, which refetches while the modal is still open.
    await waitFor(() => expect(facet).toHaveBeenCalledTimes(2));
  });

  it('lets a typed custom value override the radio pick', async () => {
    vi.spyOn(referencesApi, 'getContainerTitleFacet').mockResolvedValue([
      { value: 'J. Bot.', count: 2 },
      { value: 'Journal of Botany', count: 1 },
    ]);
    const merge = vi.spyOn(referencesApi, 'mergeContainerTitle').mockResolvedValue({ updated: 3 });

    render(<ReconcileJournalsModal pid={1} opened onClose={() => {}} />);

    await userEvent.click(await screen.findByRole('checkbox', { name: 'J. Bot. (2)' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Journal of Botany (1)' }));
    await userEvent.type(screen.getByLabelText(/custom canonical/i), 'J. Botany');

    await userEvent.click(screen.getByRole('button', { name: /merge 2 journal names into/i }));

    await waitFor(() =>
      expect(merge).toHaveBeenCalledWith(1, 'J. Botany', ['J. Bot.', 'Journal of Botany']),
    );
  });

  it('shows an empty state when there are no journal names to reconcile', async () => {
    vi.spyOn(referencesApi, 'getContainerTitleFacet').mockResolvedValue([]);
    render(<ReconcileJournalsModal pid={1} opened onClose={() => {}} />);
    expect(await screen.findByText('No journal names to reconcile.')).toBeInTheDocument();
  });
});
