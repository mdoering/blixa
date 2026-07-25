import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import userEvent from '@testing-library/user-event';
import BulkAddModal from './BulkAddModal';
import * as bulkApi from '../api/bulk';

const target = { id: 7, scientificName: 'Panthera' };

describe('BulkAddModal', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('previews then inserts', async () => {
    vi.spyOn(bulkApi, 'previewBulk').mockResolvedValue({
      valid: true, error: null, total: 2, accepted: 2, synonyms: 0, duplicates: 0,
      nodes: [
        { name: 'Panthera leo', rank: 'species', status: 'ACCEPTED', extinct: false, duplicate: false, children: [], synonyms: [] },
        { name: 'Panthera onca', rank: 'species', status: 'ACCEPTED', extinct: false, duplicate: false, children: [], synonyms: [] },
      ],
    });
    const insert = vi.spyOn(bulkApi, 'insertBulk').mockResolvedValue({ created: 2, synonymsLinked: 0, targetId: 7 });

    render(<BulkAddModal pid={1} target={target} opened onClose={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByLabelText(/names/i), 'Panthera leo\nPanthera onca');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(await screen.findByText('Panthera leo')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /insert 2 names/i }));
    await waitFor(() => expect(insert).toHaveBeenCalled());
  });

  it('locks to synonyms mode when fixedMode is set (no children/synonyms toggle)', async () => {
    const preview = vi.spyOn(bulkApi, 'previewBulk').mockResolvedValue({
      valid: true, error: null, total: 1, accepted: 0, synonyms: 1, duplicates: 0,
      nodes: [{ name: 'Felis leo', rank: 'species', status: 'SYNONYM', extinct: false, duplicate: false, children: [], synonyms: [] }],
    });
    render(<BulkAddModal pid={1} target={target} opened fixedMode="synonyms" onClose={() => {}} onDone={() => {}} />);
    // the mode toggle is hidden -- the mode is fixed to synonyms
    expect(screen.queryByText('As accepted children')).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/names/i), 'Felis leo');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));
    await waitFor(() =>
      expect(preview).toHaveBeenCalledWith(1, expect.objectContaining({ mode: 'synonyms', targetId: 7 })),
    );
  });

  it('shows a parse error and disables insert', async () => {
    vi.spyOn(bulkApi, 'previewBulk').mockResolvedValue({
      valid: false, error: 'not properly indented on line 2', total: 0, accepted: 0, synonyms: 0, duplicates: 0, nodes: [],
    });
    render(<BulkAddModal pid={1} target={target} opened onClose={() => {}} onDone={() => {}} />);
    await userEvent.type(screen.getByLabelText(/names/i), 'Aaa\n    Bbb');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));
    expect(await screen.findByText(/not properly indented/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /insert/i })).not.toBeInTheDocument();
  });
});
