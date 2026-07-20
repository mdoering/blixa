import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import MentionMarkdown from './MentionMarkdown';

test('renders a #nameID mention as a link to the name', () => {
  renderWithProviders(
    <MentionMarkdown
      pid={3}
      text="Check #7 please"
      mentions={{ usages: { '7': 'Panthera leo' }, users: {} }}
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
      mentions={{
        usages: {},
        users: { '0000-0001-2345-6789': { label: 'Olaf Banki', orcid: '0000-0001-2345-6789' } },
      }}
    />,
  );
  const link = screen.getByRole('link', { name: '@Olaf Banki' });
  expect(link).toHaveAttribute('href', 'https://orcid.org/0000-0001-2345-6789');
});

test('renders an @username mention (no ORCID) as a non-link label', () => {
  renderWithProviders(
    <MentionMarkdown
      pid={3}
      text="cc @olaf"
      mentions={{ usages: {}, users: { olaf: { label: 'Olaf Banki', orcid: null } } }}
    />,
  );
  expect(screen.getByText('@Olaf Banki')).toBeInTheDocument();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
});

test('leaves an unresolved mention as plain text', () => {
  renderWithProviders(
    <MentionMarkdown pid={3} text="Check #999 please" mentions={{ usages: {}, users: {} }} />,
  );
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.getByText(/#999/)).toBeInTheDocument();
});
