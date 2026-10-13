// Ordered table of contents for the in-app help (/help). Drives the sidebar and the prev/next
// links; every slug must have a matching src/help/pages/<slug>.md (checked by HelpPage.test.tsx).

export interface HelpPageEntry {
  slug: string;
  title: string;
}

export interface HelpSection {
  title: string;
  pages: HelpPageEntry[];
}

export const HELP_TOC: HelpSection[] = [
  {
    title: 'Getting started',
    pages: [
      { slug: 'index', title: 'Welcome to Blixa' },
      { slug: 'access', title: 'Signing in & access' },
      { slug: 'concepts', title: 'Key concepts' },
      { slug: 'tour', title: 'A tour of the screens' },
      { slug: 'tutorial', title: 'Tutorial: the Felidae playground' },
    ],
  },
  {
    title: 'How-to guides',
    pages: [
      { slug: 'start-a-checklist', title: 'Start a checklist' },
      { slug: 'build-the-tree', title: 'Build the classification' },
      { slug: 'edit-names', title: 'Edit names & taxon data' },
      { slug: 'synonyms', title: 'Synonyms & homotypy' },
      { slug: 'references', title: 'References' },
      { slug: 'checklistbank', title: 'Use ChecklistBank & COL' },
      { slug: 'validation', title: 'Validation issues' },
      { slug: 'collaborate', title: 'Work together' },
      { slug: 'merge', title: 'Merge duplicates & projects' },
      { slug: 'publish', title: 'Publish, release & export' },
    ],
  },
  {
    title: 'Reference',
    pages: [
      { slug: 'texttree', title: 'TextTree syntax' },
      { slug: 'import-formats', title: 'Import formats' },
      { slug: 'validation-rules', title: 'Validation rules' },
      { slug: 'glossary', title: 'Glossary' },
      { slug: 'faq', title: 'FAQ & troubleshooting' },
    ],
  },
];

export const HELP_PAGES: HelpPageEntry[] = HELP_TOC.flatMap((s) => s.pages);

export function helpPath(slug: string, section?: string): string {
  const base = slug === 'index' ? '/help' : `/help/${slug}`;
  return section ? `${base}#${section}` : base;
}
