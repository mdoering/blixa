import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { useCountryName, useLanguageName, useLanguageOptions } from './useVocab';

function Probe() {
  const language = useLanguageName();
  const country = useCountryName();
  const options = useLanguageOptions();
  return (
    <div>
      <span data-testid="lang">{language('nld')}</span>
      <span data-testid="unknown">{language('xyz')}</span>
      <span data-testid="c2">{country('de')}</span>
      <span data-testid="c3">{country('NLD')}</span>
      <span data-testid="opts">{options.map((o) => o.label).join('|')}</span>
    </div>
  );
}

test('codes render with their English names; unknown codes stay as they are', async () => {
  renderWithProviders(<Probe />);
  expect(await screen.findByText('Dutch')).toBeInTheDocument();
  expect(screen.getByTestId('unknown')).toHaveTextContent('xyz');
  expect(screen.getByTestId('c2')).toHaveTextContent('Germany');
  expect(screen.getByTestId('c3')).toHaveTextContent('Netherlands');
  expect(screen.getByTestId('opts')).toHaveTextContent('Dutch (nld)|English (eng)|German (deu)');
});
