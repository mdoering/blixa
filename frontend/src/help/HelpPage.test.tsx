import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import HelpPage, { helpPageFiles } from './HelpPage';
import { HELP_PAGES, helpPath } from './toc';
import { extractHeadings, slugify } from './markdown';

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/help" element={<HelpPage />} />
            <Route path="/help/:page" element={<HelpPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('help table of contents', () => {
  it('has a markdown file for every toc entry and a toc entry for every file', () => {
    expect(helpPageFiles().sort()).toEqual(HELP_PAGES.map((p) => p.slug).sort());
  });

  it('maps the index slug to /help', () => {
    expect(helpPath('index')).toBe('/help');
    expect(helpPath('tutorial', 'export')).toBe('/help/tutorial#export');
  });
});

describe('markdown helpers', () => {
  it('slugifies like GitHub', () => {
    expect(slugify('Demote & promote: synonyms')).toBe('demote--promote-synonyms');
  });

  it('extracts ## and ### headings outside code fences', () => {
    const md = '# Title\n## First `step`\n```\n## not a heading\n```\n### Sub [link](/x)\n';
    expect(extractHeadings(md)).toEqual([
      { slug: 'first-step', level: 2, label: 'First step' },
      { slug: 'sub-link', level: 3, label: 'Sub link' },
    ]);
  });
});

describe('HelpPage', () => {
  it('renders the index page with the sidebar', async () => {
    renderAt('/help');
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Glossary' })).toHaveAttribute('href', '/help/glossary');
  });

  it('gives section headings anchor ids', async () => {
    renderAt('/help/concepts');
    const h2s = await screen.findAllByRole('heading', { level: 2 });
    expect(h2s.length).toBeGreaterThan(0);
    for (const h of h2s) expect(h.id).not.toBe('');
  });

  it('redirects an unknown page to the index', async () => {
    renderAt('/help/no-such-page');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Welcome to Blixa' }),
    ).toBeInTheDocument();
  });
});
