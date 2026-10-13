// Regenerates the user-documentation screenshots in public/help/img/.
//
//   npm run screenshots            (expects the app at http://localhost:5173, override with BLIXA_URL)
//
// Prerequisites: a local stack with the backend on the `dev` profile (admin/admin login) and the
// Vite dev server -- see README.md "Regenerating help screenshots". The script signs in, imports the
// Felidae playground (public/help/demo/) once as "Blixa playground – Felidae" (reused on later runs),
// then walks the SHOTS list. A failing shot is reported and skipped; the exit code is non-zero if any
// shot failed. Run with SHOTS=name1,name2 to regenerate only some.
import { chromium } from 'playwright';
import type { APIRequestContext, Locator, Page } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const BASE = process.env.BLIXA_URL ?? 'http://localhost:5173';
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public/help/img');
const PROJECT_TITLE = 'Blixa playground – Felidae';
const ONLY = process.env.SHOTS?.split(',').map((s) => s.trim());

interface Ctx {
  page: Page;
  pid: number;
  usage: (name: string) => Promise<number>;
}

interface Shot {
  name: string;
  /** navigate + prepare the page; return a locator to clip to, or nothing for the full viewport */
  run: (c: Ctx) => Promise<Locator | void>;
}

// --- helpers ------------------------------------------------------------------------------------

async function xsrf(page: Page): Promise<Record<string, string>> {
  const cookies = await page.context().cookies(BASE);
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

async function apiJson<T>(req: APIRequestContext, url: string): Promise<T> {
  const res = await req.get(BASE + url);
  if (!res.ok()) throw new Error(`GET ${url} -> ${res.status()}`);
  return (await res.json()) as T;
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => {});
  // let Mantine transitions (modals, menus, tabs) finish
  await page.waitForTimeout(400);
}

const modal = (page: Page) => page.locator('.mantine-Modal-content').last();

async function openTree(c: Ctx, name: string) {
  const id = await c.usage(name);
  await c.page.goto(`${BASE}/projects/${c.pid}/tree?usage=${id}`);
  await c.page.getByRole('tab', { name: /^Details/ }).waitFor();
  await settle(c.page);
}

async function rowActions(c: Ctx, name: string) {
  // right-click the tree row (the tree pane renders before the detail pane) -> its actions menu
  await c.page.getByText(name, { exact: true }).first().click({ button: 'right' });
  await c.page.getByRole('menu').waitFor();
  await settle(c.page);
}

// --- the shots ----------------------------------------------------------------------------------

const SHOTS: Shot[] = [
  {
    name: 'projects-list',
    run: async ({ page }) => {
      await page.goto(`${BASE}/projects`);
      await page.getByRole('button', { name: /try it with demo data/i }).waitFor();
      await settle(page);
    },
  },
  {
    name: 'import-modal',
    run: async ({ page }) => {
      await page.goto(`${BASE}/projects`);
      await page.getByRole('button', { name: 'Import ColDP' }).click();
      await settle(page);
      return modal(page);
    },
  },
  {
    name: 'dashboard',
    run: async ({ page }) => {
      await page.goto(`${BASE}/dashboard`);
      await settle(page);
    },
  },
  {
    name: 'tree-overview',
    run: async (c) => {
      await openTree(c, 'Panthera leo');
    },
  },
  {
    name: 'tree-row-menu',
    run: async (c) => {
      await openTree(c, 'Panthera');
      await rowActions(c, 'Panthera');
    },
  },
  {
    name: 'taxon-details',
    run: async (c) => {
      await openTree(c, 'Lynx pardinus');
    },
  },
  {
    name: 'synonyms-tab',
    run: async (c) => {
      await openTree(c, 'Panthera leo');
      await c.page.getByRole('tab', { name: /^Synonyms/ }).click();
      await settle(c.page);
    },
  },
  {
    name: 'bulk-add-modal',
    run: async (c) => {
      await openTree(c, 'Panthera');
      await rowActions(c, 'Panthera');
      await c.page.getByRole('menuitem', { name: 'Bulk add…' }).click();
      await settle(c.page);
      await modal(c.page)
        .getByRole('textbox')
        .first()
        .fill('Panthera spelaea (Goldfuss, 1810) [species]\n  = Felis spelaea Goldfuss, 1810\n');
      await settle(c.page);
      return modal(c.page);
    },
  },
  {
    name: 'demote-modal',
    run: async (c) => {
      await openTree(c, 'Panthera tigirs');
      await rowActions(c, 'Panthera tigirs');
      await c.page.getByRole('menuitem', { name: 'Change status' }).click();
      await c.page.getByRole('menuitem', { name: 'Synonym', exact: true }).click();
      await settle(c.page);
      return modal(c.page);
    },
  },
  {
    name: 'homotypic-conflicts',
    run: async (c) => {
      const root = await c.usage('Felidae');
      await c.page.goto(`${BASE}/projects/${c.pid}/homotypic-conflicts/${root}`);
      await c.page.getByText('Puma concolor').first().waitFor();
      await settle(c.page);
    },
  },
  {
    name: 'homotypic-conflict-card',
    run: async (c) => {
      const root = await c.usage('Felidae');
      await c.page.goto(`${BASE}/projects/${c.pid}/homotypic-conflicts/${root}`);
      const card = c.page
        .locator('.mantine-Card-root, .mantine-Paper-root')
        .filter({ hasText: 'Puma concolor (Linnaeus, 1771)' })
        .last();
      await card.scrollIntoViewIfNeeded();
      await settle(c.page);
      return card;
    },
  },
  {
    name: 'names-search',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/names`);
      await page.getByPlaceholder(/search/i).first().fill('Lynx');
      await settle(page);
    },
  },
  {
    name: 'references',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/references`);
      await settle(page);
    },
  },
  {
    name: 'issues',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/issues`);
      await settle(page);
    },
  },
  {
    name: 'clb-compare',
    run: async (c) => {
      await openTree(c, 'Panthera leo');
      await c.page.getByRole('button', { name: 'Compare with CLB…' }).click();
      await settle(c.page);
      // compare against the Catalogue of Life: By dataset -> search -> first COL hit -> the name
      const m = modal(c.page);
      await m.getByText('By dataset', { exact: true }).click();
      await m.getByLabel('Search datasets').fill('Catalogue of Life Checklist');
      await m.getByText(/^Catalogue of Life Checklist/).first().click();
      await m.getByText('Panthera leo', { exact: true }).first().click();
      await c.page.waitForTimeout(3000);
      await settle(c.page);
      return modal(c.page);
    },
  },
  {
    name: 'clb-import-modal',
    run: async (c) => {
      await openTree(c, 'Panthera');
      await rowActions(c, 'Panthera');
      await c.page.getByRole('menuitem', { name: 'Import from ChecklistBank…' }).click();
      await settle(c.page);
      return modal(c.page);
    },
  },
  {
    name: 'discussions',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/discussions`);
      await settle(page);
    },
  },
  {
    name: 'history',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/history`);
      await settle(page);
    },
  },
  {
    name: 'members',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/members`);
      await settle(page);
    },
  },
  {
    name: 'discussion-detail',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/discussions`);
      await page.getByText('What is the "Bornean form" placeholder?').click();
      await page.waitForURL(/discussions\/\d+/);
      await settle(page);
    },
  },
  {
    name: 'merge-records-modal',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/names`);
      await page.getByPlaceholder(/search/i).first().fill('Lynx rufus');
      await settle(page);
      const rows = page.getByRole('row').filter({ hasText: /^Lynx rufus\s*\(Schreber, 1777\)/ });
      for (const row of await rows.all()) await row.getByRole('checkbox').check();
      await page.getByRole('button', { name: /^Merge \d+ selected/ }).click();
      await settle(page);
      return modal(page);
    },
  },
  {
    name: 'unassessed-tree',
    run: async (c) => {
      await openTree(c, 'Leopardus garleppi');
      await c.page.getByLabel('Show unassessed').check();
      await settle(c.page);
    },
  },
  {
    name: 'distribution-map',
    run: async (c) => {
      await openTree(c, 'Panthera leo');
      await c.page.getByRole('tab', { name: /^Distribution/ }).click();
      await c.page.waitForTimeout(3000);
      await settle(c.page);
    },
  },
  {
    name: 'activity',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/activity`);
      await settle(page);
    },
  },
  {
    name: 'objective-selector',
    run: async ({ page, pid }) => {
      await page.goto(`${BASE}/projects/${pid}/tree`);
      await page.getByText('No objective').click();
      await settle(page);
    },
  },
  {
    name: 'account-modal',
    run: async ({ page }) => {
      await page.goto(`${BASE}/dashboard`);
      await page.getByText('Local Admin').click();
      await page.getByRole('menuitem', { name: 'Account' }).click();
      await settle(page);
      return modal(page);
    },
  },
  ...(['metadata', 'settings', 'releases', 'tools'] as const).map(
    (tab): Shot => ({
      name: `project-${tab}`,
      run: async ({ page, pid }) => {
        await page.goto(`${BASE}/projects/${pid}/metadata`);
        await page.getByRole('tab', { name: new RegExp(`^${tab}$`, 'i') }).click();
        await settle(page);
      },
    }),
  ),
];

// Some activity so History, Discussions and Activity aren't empty: two discussions (one with a
// comment and mentions) and one edit through the UI (which also takes a soft lock). Idempotent.
async function seed(page: Page, pid: number, usage: (name: string) => Promise<number>) {
  const existing = await apiJson<{ total: number }>(
    page.request,
    `/api/projects/${pid}/discussions?limit=1`,
  );
  if (existing.total > 0) return;
  console.log('seeding discussions and an edit…');
  const post = async (url: string, data: unknown) => {
    const res = await page.request.post(BASE + url, { data, headers: await xsrf(page) });
    if (!res.ok()) throw new Error(`POST ${url} -> ${res.status()}`);
    return (await res.json()) as { id: number };
  };
  const bornean = await usage('Felis sp. "Bornean form"');
  const badia = await usage('Catopuma badia');
  const d1 = await post(`/api/projects/${pid}/discussions`, {
    title: 'What is the "Bornean form" placeholder?',
    body: `#${bornean} was entered as an informal name. Is it just #${badia}, or something undescribed?`,
  });
  await post(`/api/projects/${pid}/discussions/${d1.id}/comments`, {
    body: `Most likely #${badia} — @admin, could you check the source before we merge it?`,
  });
  const garleppi = await usage('Leopardus garleppi');
  await post(`/api/projects/${pid}/discussions`, {
    title: 'Review the pampas cat split',
    body: `The three provisionally accepted *Leopardus* species (e.g. #${garleppi}) follow the 2020 split of *L. colocola*. Accept or sink them?`,
  });

  const onca = await usage('Panthera onca');
  await page.goto(`${BASE}/projects/${pid}/tree?usage=${onca}`);
  await page.getByLabel('Remarks').fill('Jaguar. Subspecies not recognised here, following Kitchener et al. 2017.');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await settle(page);
}

// --- main ---------------------------------------------------------------------------------------

async function ensureProject(page: Page): Promise<number> {
  const find = async () =>
    (await apiJson<{ id: number; title: string }[]>(page.request, '/api/projects')).find(
      (p) => p.title === PROJECT_TITLE,
    );
  const existing = await find();
  if (existing) return existing.id;
  console.log('importing the Felidae playground…');
  await page.goto(`${BASE}/projects`);
  await page.getByRole('button', { name: /try it with demo data/i }).click();
  await page.waitForURL(/\/projects\/\d+\/tree/, { timeout: 120_000 });
  const pid = Number(/\/projects\/(\d+)\//.exec(page.url())![1]);
  // make sure the Issues page has the full picture
  await page.request.post(`${BASE}/api/projects/${pid}/revalidate`, { headers: await xsrf(page) });
  return pid;
}

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'light',
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15_000);

  await page.goto(`${BASE}/signin`);
  await page.getByLabel('Username').fill(process.env.BLIXA_USER ?? 'admin');
  await page.getByLabel('Password').fill(process.env.BLIXA_PASSWORD ?? 'admin');
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/signin'));

  const pid = await ensureProject(page);
  const ids = new Map<string, number>();
  const usage = async (name: string) => {
    if (!ids.has(name)) {
      const res = await apiJson<{ items: { id: number; scientificName: string }[] }>(
        page.request,
        `/api/projects/${pid}/usages?q=${encodeURIComponent(name)}&limit=50`,
      );
      const hit = res.items.find((u) => u.scientificName === name);
      if (!hit) throw new Error(`no usage named ${name}`);
      ids.set(name, hit.id);
    }
    return ids.get(name)!;
  };

  await seed(page, pid, usage);

  const failed: string[] = [];
  for (const shot of SHOTS) {
    if (ONLY && !ONLY.includes(shot.name)) continue;
    try {
      await page.keyboard.press('Escape').catch(() => {});
      const clip = await shot.run({ page, pid, usage });
      const file = path.join(OUT, `${shot.name}.png`);
      if (clip) await clip.screenshot({ path: file, animations: 'disabled' });
      else await page.screenshot({ path: file, animations: 'disabled' });
      console.log(`✓ ${shot.name}`);
    } catch (e) {
      failed.push(shot.name);
      if (process.env.DEBUG_DIR) {
        await page.screenshot({ path: path.join(process.env.DEBUG_DIR, `${shot.name}-failed.png`) });
      }
      console.error(`✗ ${shot.name}: ${(e as Error).message.split('\n')[0]}`);
    }
  }
  await browser.close();
  if (failed.length) {
    console.error(`${failed.length} shot(s) failed: ${failed.join(', ')}`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
