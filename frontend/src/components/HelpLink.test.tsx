import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import HelpLink from './HelpLink';

describe('HelpLink', () => {
  it('links to the help index by default, in a new tab', () => {
    render(
      <MantineProvider>
        <HelpLink />
      </MantineProvider>,
    );
    const link = screen.getByRole('link', { name: 'Help' });
    expect(link).toHaveAttribute('href', '/help');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('deep-links to a page section', () => {
    render(
      <MantineProvider>
        <HelpLink page="synonyms" section="demote" label="Help: synonyms" />
      </MantineProvider>,
    );
    expect(screen.getByRole('link', { name: 'Help: synonyms' })).toHaveAttribute(
      'href',
      '/help/synonyms#demote',
    );
  });
});
