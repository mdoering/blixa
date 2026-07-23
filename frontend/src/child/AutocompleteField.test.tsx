import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '../test/utils';
import userEvent from '@testing-library/user-event';
import AutocompleteField from './AutocompleteField';
import { type Option } from './EntitySelect';

describe('AutocompleteField', () => {
  it('suggests the loaded options when focused', async () => {
    const load = vi.fn().mockResolvedValue([
      { value: 'bodyMass', label: 'bodyMass' },
      { value: 'karyotype', label: 'karyotype' },
    ]);
    render(
      <AutocompleteField value="" onChange={() => {}} load={load} queryKey={['pk']} label="Property" />,
    );
    const input = await screen.findByRole('textbox', { name: 'Property' });
    await userEvent.click(input);
    expect(await screen.findByText('bodyMass')).toBeInTheDocument();
    expect(screen.getByText('karyotype')).toBeInTheDocument();
  });

  it('reports the free-typed value via onChange (still accepts new keys)', async () => {
    const seen: string[] = [];
    function Wrapper() {
      const [v, setV] = useState('');
      return (
        <AutocompleteField
          value={v}
          onChange={(x) => {
            seen.push(x);
            setV(x);
          }}
          load={vi.fn<() => Promise<Option[]>>().mockResolvedValue([])}
          queryKey={['pk2']}
          label="Property"
        />
      );
    }
    render(<Wrapper />);
    const input = await screen.findByRole('textbox', { name: 'Property' });
    await userEvent.type(input, 'ploidy');
    expect(seen[seen.length - 1]).toBe('ploidy');
  });
});
