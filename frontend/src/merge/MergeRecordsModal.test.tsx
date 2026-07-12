import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '../test/utils';
import userEvent from '@testing-library/user-event';
import MergeRecordsModal from './MergeRecordsModal';
import * as mergeApi from '../api/merge';

// id 1 is the more-connected candidate (2 + 3 = 5 total counts) vs id 2's (0 + 1 = 1) -- the
// default survivor should land on id 1 without the user touching the Radio.Group.
const candidateHigh = {
  id: 1,
  alternativeId: ['gbif:123'],
  scientificName: 'Abies alba',
  authorship: 'Mill.',
  rank: 'species',
  status: 'ACCEPTED',
  counts: { synonyms: 2, children: 3 },
};
const candidateLow = {
  id: 2,
  alternativeId: null,
  scientificName: 'Abies nigra',
  authorship: null,
  rank: 'species',
  status: 'SYNONYM',
  counts: { synonyms: 0, children: 1 },
};

describe('MergeRecordsModal', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('previews candidates, defaults the survivor to the most-connected record, and merges', async () => {
    vi.spyOn(mergeApi, 'previewUsageMerge').mockResolvedValue([candidateHigh, candidateLow]);
    const merge = vi
      .spyOn(mergeApi, 'mergeUsages')
      .mockResolvedValue({ survivorId: 1, mergedCount: 1 });
    const onDone = vi.fn();

    render(
      <MergeRecordsModal
        entity="usage"
        pid={1}
        ids={[1, 2]}
        opened
        onClose={() => {}}
        onDone={onDone}
      />,
    );

    expect(await screen.findByText('Abies alba')).toBeInTheDocument();
    expect(screen.getByText('Abies nigra')).toBeInTheDocument();

    // Survivor defaults to id 1 (higher total counts) without any interaction.
    const survivorRadio = screen.getByRole('radio', { name: /Abies alba/ });
    expect(survivorRadio).toBeChecked();
    expect(screen.getByRole('radio', { name: /Abies nigra/ })).not.toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: /merge 2 records into/i }));

    await waitFor(() => expect(merge).toHaveBeenCalledWith(1, 1, [1, 2]));
    expect(onDone).toHaveBeenCalled();
  });
});
