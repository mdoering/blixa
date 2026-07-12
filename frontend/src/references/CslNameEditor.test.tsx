import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { renderWithProviders } from '../test/utils';
import CslNameEditor from './CslNameEditor';
import type { CslName } from '../api/types';

test('renders a family/given row per name', () => {
  const value: CslName[] = [
    { family: 'Linnaeus', given: 'C.' },
    { family: 'Darwin', given: 'C.' },
  ];
  renderWithProviders(<CslNameEditor label="Author" value={value} onChange={() => {}} />);

  expect(screen.getByRole('textbox', { name: 'Author family 1' })).toHaveValue('Linnaeus');
  expect(screen.getByRole('textbox', { name: 'Author given 1' })).toHaveValue('C.');
  expect(screen.getByRole('textbox', { name: 'Author family 2' })).toHaveValue('Darwin');
  expect(screen.getByRole('textbox', { name: 'Author given 2' })).toHaveValue('C.');
});

test('"Add author" appends a blank row', async () => {
  const onChange = vi.fn();
  renderWithProviders(
    <CslNameEditor label="Author" value={[{ family: 'Linnaeus' }]} onChange={onChange} />,
  );

  await userEvent.click(screen.getByRole('button', { name: 'Add author' }));

  expect(onChange).toHaveBeenCalledWith([{ family: 'Linnaeus' }, {}]);
});

test('remove drops one row', async () => {
  const onChange = vi.fn();
  const value: CslName[] = [{ family: 'Linnaeus' }, { family: 'Darwin' }];
  renderWithProviders(<CslNameEditor label="Author" value={value} onChange={onChange} />);

  await userEvent.click(screen.getByRole('button', { name: 'Remove author 1' }));

  expect(onChange).toHaveBeenCalledWith([{ family: 'Darwin' }]);
});

test('the institution toggle switches a row to a single literal input', async () => {
  const onChange = vi.fn();
  const { rerender } = renderWithProviders(
    <CslNameEditor label="Author" value={[{ family: 'Linnaeus' }]} onChange={onChange} />,
  );

  expect(screen.getByRole('textbox', { name: 'Author family 1' })).toBeInTheDocument();
  expect(screen.queryByRole('textbox', { name: 'Author name 1' })).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('switch', { name: 'Author institution 1' }));
  expect(onChange).toHaveBeenCalledWith([{ family: 'Linnaeus', isInstitution: true }]);

  // The component is fully controlled -- simulate the parent applying the emitted value.
  rerender(
    <CslNameEditor
      label="Author"
      value={[{ family: 'Linnaeus', isInstitution: true }]}
      onChange={onChange}
    />,
  );
  expect(screen.queryByRole('textbox', { name: 'Author family 1' })).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Author name 1' })).toBeInTheDocument();
});

test('editing a field emits the updated CslName[] via onChange', async () => {
  const onChange = vi.fn();
  renderWithProviders(<CslNameEditor label="Author" value={[{}]} onChange={onChange} />);

  await userEvent.type(screen.getByRole('textbox', { name: 'Author family 1' }), 'X');

  expect(onChange).toHaveBeenLastCalledWith([{ family: 'X' }]);
});

test('two independent editors (author/editor) keep their rows disambiguated', () => {
  renderWithProviders(
    <>
      <CslNameEditor label="Author" value={[{ family: 'Linnaeus' }]} onChange={() => {}} />
      <CslNameEditor label="Editor" value={[{ family: 'Darwin' }]} onChange={() => {}} />
    </>,
  );

  expect(screen.getByRole('textbox', { name: 'Author family 1' })).toHaveValue('Linnaeus');
  expect(screen.getByRole('textbox', { name: 'Editor family 1' })).toHaveValue('Darwin');
});
