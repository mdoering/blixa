import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import { within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PropertyKeysModal from './PropertyKeysModal';
import * as api from '../api/propertyKeys';

describe('PropertyKeysModal', () => {
  beforeEach(() => vi.restoreAllMocks());

  const facet = () =>
    vi.spyOn(api, 'getPropertyKeys').mockResolvedValue([
      { key: 'body mass', count: 2, description: 'Adult body mass' },
      { key: 'bodyMass', count: 1, description: null },
      { key: 'karyotype', count: 0, description: 'Chromosome set' },
    ]);

  it('lists every key with its count and description', async () => {
    facet();
    render(<PropertyKeysModal pid={1} opened onClose={() => {}} />);
    expect(await screen.findByText('body mass')).toBeInTheDocument();
    expect(screen.getByText('bodyMass')).toBeInTheDocument();
    // defined-but-unused key shows a zero count
    expect(screen.getByText('karyotype')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Adult body mass')).toBeInTheDocument();
  });

  it('saves an edited description via definePropertyKey', async () => {
    facet();
    const define = vi
      .spyOn(api, 'definePropertyKey')
      .mockResolvedValue({ key: 'bodyMass', count: 1, description: 'Mass of the adult' });

    render(<PropertyKeysModal pid={1} opened onClose={() => {}} />);
    const row = (await screen.findByText('bodyMass')).closest('tr') as HTMLElement;
    const input = within(row).getByPlaceholderText('Description');
    await userEvent.type(input, 'Mass of the adult');
    await userEvent.click(within(row).getByRole('button', { name: /save/i }));

    await waitFor(() =>
      expect(define).toHaveBeenCalledWith(1, 'bodyMass', 'Mass of the adult'),
    );
  });

  it('adds a brand-new key with a description', async () => {
    facet();
    const define = vi
      .spyOn(api, 'definePropertyKey')
      .mockResolvedValue({ key: 'ploidy', count: 0, description: 'Ploidy level' });

    render(<PropertyKeysModal pid={1} opened onClose={() => {}} />);
    await screen.findByText('body mass');
    await userEvent.type(screen.getByPlaceholderText('New key'), 'ploidy');
    await userEvent.type(screen.getByPlaceholderText('Description (optional)'), 'Ploidy level');
    await userEvent.click(screen.getByRole('button', { name: /add key/i }));

    await waitFor(() => expect(define).toHaveBeenCalledWith(1, 'ploidy', 'Ploidy level'));
  });

  it('removes a key definition via deletePropertyKey', async () => {
    facet();
    const del = vi.spyOn(api, 'deletePropertyKey').mockResolvedValue(undefined);
    render(<PropertyKeysModal pid={1} opened onClose={() => {}} />);
    const row = (await screen.findByText('karyotype')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: /remove definition/i }));
    await waitFor(() => expect(del).toHaveBeenCalledWith(1, 'karyotype'));
  });

  it('merges checked variant keys into a canonical, defaulting to the higher-count key', async () => {
    const getKeys = facet();
    const merge = vi.spyOn(api, 'mergePropertyKeys').mockResolvedValue({ updated: 3 });

    render(<PropertyKeysModal pid={1} opened onClose={() => {}} />);
    await screen.findByText('body mass');
    await userEvent.click(screen.getByRole('checkbox', { name: /^body mass/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /^bodyMass/ }));

    // canonical defaults to the higher-count checked key
    expect(screen.getByRole('radio', { name: 'body mass' })).toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: /merge 2 keys into/i }));
    await waitFor(() =>
      expect(merge).toHaveBeenCalledWith(1, 'body mass', ['body mass', 'bodyMass']),
    );
    await waitFor(() => expect(getKeys).toHaveBeenCalledTimes(2));
  });
});
