import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import MentionMarkdown from './MentionMarkdown';

test('renders a #nameID mention as a link to the name', () => {
  renderWithProviders(
    <MentionMarkdown
      pid={3}
      text="Check #7 please"
      mentions={{ usages: { '7': 'Panthera leo' }, orcids: {} }}
    />,
  );
  const link = screen.getByRole('link', { name: 'Panthera leo' });
  expect(link).toHaveAttribute('href', '/projects/3/names?usage=7');
});

test('renders an @orcid mention as a link to orcid.org', () => {
  renderWithProviders(
    <MentionMarkdown
      pid={3}
      text="reported by @0000-0001-2345-6789"
      mentions={{ usages: {}, orcids: { '0000-0001-2345-6789': 'Olaf Banki' } }}
    />,
  );
  const link = screen.getByRole('link', { name: '@Olaf Banki' });
  expect(link).toHaveAttribute('href', 'https://orcid.org/0000-0001-2345-6789');
});

test('leaves an unresolved mention as plain text', () => {
  renderWithProviders(
    <MentionMarkdown pid={3} text="Check #999 please" mentions={{ usages: {}, orcids: {} }} />,
  );
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.getByText(/#999/)).toBeInTheDocument();
});
