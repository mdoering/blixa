import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { HELP_PAGES } from './toc';
import { extractHeadings } from './markdown';

// Static checks over the help pages: every internal /help link points at an existing page (and
// heading, when it has an #anchor), and every referenced image / demo file exists under public/.
const PAGES_DIR = path.resolve(__dirname, 'pages');
const PUBLIC_DIR = path.resolve(__dirname, '../../public');

const pages = new Map(
  readdirSync(PAGES_DIR)
    .filter((f) => f.endsWith('.md'))
    .map((f) => [f.replace(/\.md$/, ''), readFileSync(path.join(PAGES_DIR, f), 'utf8')]),
);
const anchors = new Map(
  [...pages].map(([slug, md]) => [slug, new Set(extractHeadings(md).map((h) => h.slug))]),
);

// [text](target) and ![alt](target), ignoring fenced code
function targets(md: string): string[] {
  const body = md.replace(/```[\s\S]*?```/g, '');
  return [...body.matchAll(/\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)].map((m) => m[1]);
}

describe.each([...pages.keys()])('help page %s', (slug) => {
  const md = pages.get(slug)!;

  it('starts with an H1 matching its toc title', () => {
    const title = HELP_PAGES.find((p) => p.slug === slug)?.title;
    expect(md.split('\n')[0]).toBe(`# ${title}`);
  });

  it('only links to existing help pages and headings', () => {
    for (const t of targets(md)) {
      const local = t.startsWith('#') ? `/help/${slug}${t}` : t;
      if (
        !local.startsWith('/help') ||
        local.startsWith('/help/img/') ||
        local.startsWith('/help/demo/')
      )
        continue;
      const [p, anchor] = local.replace(/^\/help\/?/, '').split('#');
      const target = p || 'index';
      expect(pages.has(target), `${t}: no page ${target}`).toBe(true);
      if (anchor)
        expect(anchors.get(target)?.has(anchor), `${t}: no heading #${anchor}`).toBe(true);
    }
  });

  it('only references existing images and downloads', () => {
    for (const t of targets(md)) {
      if (t.startsWith('/help/img/') || t.startsWith('/help/demo/')) {
        expect(existsSync(path.join(PUBLIC_DIR, t)), `${t} missing`).toBe(true);
      }
    }
  });
});

// Every in-app (?) link -- <HelpLink page=".." section=".."> / <WithHelp ...> in src/ -- must point at
// an existing help page and heading.
describe('in-app help links', () => {
  const SRC = path.resolve(__dirname, '..');
  const files: string[] = [];
  const walk = (dir: string) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx$/.test(e.name) && !/\.test\.tsx$/.test(e.name)) files.push(p);
    }
  };
  walk(SRC);
  const uses = files.flatMap((f) =>
    [...readFileSync(f, 'utf8').matchAll(/<(?:HelpLink|WithHelp)\b([^>]*)>/g)].map((m) => ({
      file: path.relative(SRC, f),
      page: /page="([^"]+)"/.exec(m[1])?.[1] ?? 'index',
      section: /section="([^"]+)"/.exec(m[1])?.[1],
    })),
  );

  it('finds the help links', () => {
    expect(uses.length).toBeGreaterThan(10);
  });

  it.each(uses.map((u) => [`${u.file} -> ${u.page}#${u.section ?? ''}`, u] as const))(
    '%s resolves',
    (_, u) => {
      expect(pages.has(u.page)).toBe(true);
      if (u.section) expect(anchors.get(u.page)?.has(u.section)).toBe(true);
    },
  );
});
