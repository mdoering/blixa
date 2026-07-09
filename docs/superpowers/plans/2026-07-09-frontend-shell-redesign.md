# Frontend Shell Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the minimal top-header + per-project tab shell with a proper app shell — collapsible icon-rail sidebar, slate theme, light/dark toggle, and a footer.

**Architecture:** `AppLayout` becomes the single owner of all chrome via one Mantine `AppShell` (header · navbar · footer · main). It derives the active project id from the route and renders `AppSidebar` (Projects + the current project's sections). `ProjectLayout` slims to a data guard. A new `theme.ts` + color-scheme wiring adds the slate palette and dark mode.

**Tech Stack:** React 18, Mantine 7.17.8 (`AppShell`, `NavLink`, `Burger`, `useMantineColorScheme`, `useComputedColorScheme`), `@mantine/hooks` (`useLocalStorage`, `useDisclosure`), react-router-dom 6, `@tabler/icons-react` 3, TanStack Query, Vitest + Testing Library + MSW.

## Global Constraints

- Mantine **7.17.8** APIs only (no v8). Icons from `@tabler/icons-react` (verified present: `IconFolders`, `IconBinaryTree2`, `IconList`, `IconSettings`, `IconUsers`, `IconSun`, `IconMoon`, `IconBook2`, `IconLogout`).
- Sidebar sections are the **built** ones only: **Tree · Names · Project · Members**. The Metadata page keeps its route `/projects/:id/metadata` and component `ProjectMetadataPage`; its nav item is labelled **Project** (metadata is one facet; more project settings will join later). Do NOT add References/Issues/Changelog/Tools.
- Theme primary color id is **`slate`** (a custom 10-shade blue-gray tuple). No CoL green / logo branding.
- Color-scheme localStorage key is Mantine's default **`mantine-color-scheme-value`**; the html attribute is **`data-mantine-color-scheme`**.
- Sidebar collapse state persists under localStorage key **`coldp-nav-collapsed`**.
- ProjectSwitcher navigates to the project's **Tree** (`/projects/:id/tree`), not metadata.
- Commit after every task. Build (`npm run build`) = tsc typecheck + vite; keep it green. Full suite `npm test` must stay green (50 tests at plan start).
- Run all commands from `frontend/`.

---

### Task 1: Theme, color-scheme wiring, and version constant

**Files:**
- Create: `frontend/src/theme.ts`
- Create: `frontend/src/theme.test.ts`
- Modify: `frontend/src/main.tsx`
- Modify: `frontend/src/test/utils.tsx`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/src/vite-env.d.ts`
- Modify: `frontend/index.html`

**Interfaces:**
- Produces: `theme` (a Mantine `MantineThemeOverride` from `createTheme`) exported from `src/theme.ts`; the global `__APP_VERSION__: string`.

- [ ] **Step 1: Write the failing test** — `frontend/src/theme.test.ts`

```ts
import { expect, test } from 'vitest';
import { theme } from './theme';

test('theme uses the custom slate primary with a full 10-shade ramp', () => {
  expect(theme.primaryColor).toBe('slate');
  expect(theme.colors?.slate).toHaveLength(10);
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/theme.test.ts`
Expected: FAIL — cannot resolve `./theme`.

- [ ] **Step 3: Create `frontend/src/theme.ts`**

```ts
import { createTheme, type MantineColorsTuple } from '@mantine/core';

// A neutral blue-gray "slate" ramp (index 0 lightest → 9 darkest); index 6 is the default filled
// shade in light mode. Deliberately not one of Mantine's default hues — the editor is brand-neutral.
const slate: MantineColorsTuple = [
  '#f8fafc',
  '#f1f5f9',
  '#e2e8f0',
  '#cbd5e1',
  '#94a3b8',
  '#64748b',
  '#475569',
  '#334155',
  '#1e293b',
  '#0f172a',
];

export const theme = createTheme({
  primaryColor: 'slate',
  primaryShade: { light: 6, dark: 8 },
  colors: { slate },
  defaultRadius: 'sm',
});
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/theme.test.ts`
Expected: PASS.

- [ ] **Step 5: Apply the theme in `frontend/src/main.tsx`**

Change the import block and the `MantineProvider` opening tag:

```tsx
import { MantineProvider } from '@mantine/core';
import { theme } from './theme';
```

```tsx
    <MantineProvider theme={theme} defaultColorScheme="light">
```

(Leave the rest of `main.tsx` unchanged.)

- [ ] **Step 6: Apply the theme in the test harness** — `frontend/src/test/utils.tsx`

Add the import and pass the theme so tests render under the real palette:

```tsx
import { MantineProvider } from '@mantine/core';
import { theme } from '../theme';
```

```tsx
    <MantineProvider theme={theme}>
```

- [ ] **Step 7: Inject the version constant in `frontend/vite.config.ts`**

Add at the top (after the existing imports) and add a `define` key to the config object:

```ts
import { createRequire } from 'node:module';

const pkg = createRequire(import.meta.url)('./package.json') as { version: string };
```

```ts
export default defineConfig({
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [react()],
```

(Keep the existing `server`, `test` keys.)

- [ ] **Step 8: Declare the global in `frontend/src/vite-env.d.ts`**

```ts
/// <reference types="vite/client" />

declare const __APP_VERSION__: string;
```

- [ ] **Step 9: Prevent the dark-mode flash in `frontend/index.html`**

Add this script as the **last** element inside `<head>` (mirrors Mantine's `ColorSchemeScript`; uses Mantine's default key/attribute):

```html
    <script>
      (function () {
        try {
          var s = localStorage.getItem('mantine-color-scheme-value');
          if (s === 'auto') {
            s = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
          }
          if (s === 'dark' || s === 'light') {
            document.documentElement.setAttribute('data-mantine-color-scheme', s);
          }
        } catch (e) {}
      })();
    </script>
```

- [ ] **Step 10: Verify the whole suite + build are green**

Run: `npx vitest run && npm run build`
Expected: all tests PASS (51 now — theme test added); build SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/theme.ts frontend/src/theme.test.ts frontend/src/main.tsx frontend/src/test/utils.tsx frontend/vite.config.ts frontend/src/vite-env.d.ts frontend/index.html
git commit -m "feat(frontend): slate theme, color-scheme wiring, __APP_VERSION__"
```

---

### Task 2: ColorSchemeToggle

**Files:**
- Create: `frontend/src/components/ColorSchemeToggle.tsx`
- Create: `frontend/src/components/ColorSchemeToggle.test.tsx`

**Interfaces:**
- Produces: `ColorSchemeToggle` (default export, no props) — a header button that flips light↔dark.

- [ ] **Step 1: Write the failing test** — `frontend/src/components/ColorSchemeToggle.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import ColorSchemeToggle from './ColorSchemeToggle';

test('toggles the color scheme and reflects the next target in its label', async () => {
  renderWithProviders(<ColorSchemeToggle />);
  // Default scheme is light → the button offers to switch to dark.
  const toggle = await screen.findByLabelText('Switch to dark mode');
  await userEvent.click(toggle);
  // After switching, it offers to go back to light.
  expect(await screen.findByLabelText('Switch to light mode')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/components/ColorSchemeToggle.test.tsx`
Expected: FAIL — cannot resolve `./ColorSchemeToggle`.

- [ ] **Step 3: Create `frontend/src/components/ColorSchemeToggle.tsx`**

```tsx
import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';

// Header light/dark toggle. useComputedColorScheme resolves 'auto' to the concrete scheme so the
// icon + label always describe the *next* state; Mantine persists the choice to localStorage.
export default function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light', { getInitialValueInEffect: true });
  const next = computed === 'dark' ? 'light' : 'dark';
  return (
    <ActionIcon
      variant="default"
      size="lg"
      aria-label={`Switch to ${next} mode`}
      onClick={() => setColorScheme(next)}
    >
      {computed === 'dark' ? <IconSun size={18} /> : <IconMoon size={18} />}
    </ActionIcon>
  );
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/components/ColorSchemeToggle.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ColorSchemeToggle.tsx frontend/src/components/ColorSchemeToggle.test.tsx
git commit -m "feat(frontend): ColorSchemeToggle (light/dark)"
```

---

### Task 3: AppFooter

**Files:**
- Create: `frontend/src/components/AppFooter.tsx`
- Create: `frontend/src/components/AppFooter.test.tsx`

**Interfaces:**
- Produces: `AppFooter` (default export, no props) — slim footer bar content.
- Consumes: global `__APP_VERSION__` (Task 1).

- [ ] **Step 1: Write the failing test** — `frontend/src/components/AppFooter.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import AppFooter from './AppFooter';

test('shows the app name, version, mode, and a repository link', () => {
  renderWithProviders(<AppFooter />);
  expect(screen.getByText(new RegExp(`ColDP Editor.*v${__APP_VERSION__}`))).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /github/i })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/components/AppFooter.test.tsx`
Expected: FAIL — cannot resolve `./AppFooter`.

- [ ] **Step 3: Create `frontend/src/components/AppFooter.tsx`**

```tsx
import { Anchor, Group, Text } from '@mantine/core';

// Assumed repository path (no git remote is configured yet — update when the remote exists).
const REPO_URL = 'https://github.com/CatalogueOfLife/coldp-editor';

// Slim, dimmed, scheme-aware footer: identity + build info on the left, repo link on the right.
export default function AppFooter() {
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap">
      <Text size="xs" c="dimmed">
        ColDP Editor · v{__APP_VERSION__} · {import.meta.env.MODE}
      </Text>
      <Anchor size="xs" c="dimmed" href={REPO_URL} target="_blank" rel="noreferrer">
        GitHub
      </Anchor>
    </Group>
  );
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/components/AppFooter.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppFooter.tsx frontend/src/components/AppFooter.test.tsx
git commit -m "feat(frontend): slim app footer (version, mode, repo link)"
```

---

### Task 4: NavItem

**Files:**
- Create: `frontend/src/components/NavItem.tsx`
- Create: `frontend/src/components/NavItem.test.tsx`

**Interfaces:**
- Produces: `NavItem` (default export) with props `{ icon: ReactNode; label: string; active?: boolean; collapsed?: boolean; onClick: () => void }`. When `collapsed`, renders icon-only wrapped in a right-side `Tooltip` (the `label` still available as the tooltip + `aria-label`); otherwise icon + label.

- [ ] **Step 1: Write the failing test** — `frontend/src/components/NavItem.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { IconList } from '@tabler/icons-react';
import { renderWithProviders } from '../test/utils';
import NavItem from './NavItem';

test('expanded item shows its label and fires onClick', async () => {
  const onClick = vi.fn();
  renderWithProviders(
    <NavItem icon={<IconList />} label="Names" onClick={onClick} />,
  );
  await userEvent.click(screen.getByText('Names'));
  expect(onClick).toHaveBeenCalled();
});

test('collapsed item hides the visible label but keeps it accessible', () => {
  renderWithProviders(
    <NavItem icon={<IconList />} label="Names" collapsed onClick={() => {}} />,
  );
  // No visible text node...
  expect(screen.queryByText('Names')).not.toBeInTheDocument();
  // ...but the control is still reachable by its accessible name.
  expect(screen.getByLabelText('Names')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/components/NavItem.test.tsx`
Expected: FAIL — cannot resolve `./NavItem`.

- [ ] **Step 3: Create `frontend/src/components/NavItem.tsx`**

```tsx
import { NavLink, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';

export interface NavItemProps {
  icon: ReactNode;
  label: string;
  active?: boolean;
  collapsed?: boolean;
  onClick: () => void;
}

// One sidebar row, built on Mantine NavLink (gets hover/active styling for free). When collapsed it
// renders icon-only inside a right-anchored Tooltip; the label stays as the NavLink's aria-label so
// the item is still findable/announced.
export default function NavItem({ icon, label, active, collapsed, onClick }: NavItemProps) {
  const link = (
    <NavLink
      leftSection={icon}
      label={collapsed ? undefined : label}
      aria-label={label}
      active={active}
      onClick={onClick}
    />
  );
  return collapsed ? (
    <Tooltip label={label} position="right" withArrow>
      {link}
    </Tooltip>
  ) : (
    link
  );
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/components/NavItem.test.tsx`
Expected: PASS. (If the collapsed NavLink still renders an empty label node that trips `queryByText('Names')`, it won't — `label={undefined}` renders no label body.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/NavItem.tsx frontend/src/components/NavItem.test.tsx
git commit -m "feat(frontend): NavItem sidebar row (collapsible + tooltip)"
```

---

### Task 5: AppSidebar

**Files:**
- Create: `frontend/src/components/AppSidebar.tsx`
- Create: `frontend/src/components/AppSidebar.test.tsx`

**Interfaces:**
- Consumes: `NavItem` (Task 4).
- Produces: `AppSidebar` (default export) with props `{ projectId: number | null; collapsed: boolean; onNavigate?: () => void }`. Renders a top **Projects** item; when `projectId != null`, a **PROJECT** group with Tree/Names/Project/Members. Uses `useNavigate` + `useLocation` for routing + active state; calls `onNavigate` after navigating (so the caller can close a mobile overlay).

- [ ] **Step 1: Write the failing test** — `frontend/src/components/AppSidebar.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { useLocation } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import AppSidebar from './AppSidebar';

function LocationEcho() {
  return <div data-testid="loc">{useLocation().pathname}</div>;
}

test('inside a project it lists the section links and navigates on click', async () => {
  renderWithProviders(
    <>
      <AppSidebar projectId={3} collapsed={false} />
      <LocationEcho />
    </>,
    { route: '/projects/3/tree' },
  );
  expect(screen.getByText('Tree')).toBeInTheDocument();
  expect(screen.getByText('Names')).toBeInTheDocument();
  expect(screen.getByText('Project')).toBeInTheDocument();
  expect(screen.getByText('Members')).toBeInTheDocument();

  await userEvent.click(screen.getByText('Names'));
  expect(screen.getByTestId('loc')).toHaveTextContent('/projects/3/names');
});

test('with no project it shows only the Projects item', () => {
  renderWithProviders(<AppSidebar projectId={null} collapsed={false} />, { route: '/' });
  expect(screen.getByText('Projects')).toBeInTheDocument();
  expect(screen.queryByText('Tree')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/components/AppSidebar.test.tsx`
Expected: FAIL — cannot resolve `./AppSidebar`.

- [ ] **Step 3: Create `frontend/src/components/AppSidebar.tsx`**

```tsx
import { Stack, Text } from '@mantine/core';
import {
  IconBinaryTree2,
  IconFolders,
  IconList,
  IconSettings,
  IconUsers,
} from '@tabler/icons-react';
import { useLocation, useNavigate } from 'react-router-dom';
import NavItem from './NavItem';

const ICON = 18;

export interface AppSidebarProps {
  projectId: number | null;
  collapsed: boolean;
  onNavigate?: () => void;
}

// Sidebar navigation. Global "Projects" always shows; when a project is active, its built sections
// appear below. Active state is derived from the path. The "Project" item points at the metadata
// page (see plan: metadata is one facet of a growing project-settings section).
export default function AppSidebar({ projectId, collapsed, onNavigate }: AppSidebarProps) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const go = (to: string) => {
    navigate(to);
    onNavigate?.();
  };

  const sections =
    projectId != null
      ? [
          { key: 'tree', label: 'Tree', icon: <IconBinaryTree2 size={ICON} />, to: `/projects/${projectId}/tree` },
          { key: 'names', label: 'Names', icon: <IconList size={ICON} />, to: `/projects/${projectId}/names` },
          { key: 'project', label: 'Project', icon: <IconSettings size={ICON} />, to: `/projects/${projectId}/metadata` },
          { key: 'members', label: 'Members', icon: <IconUsers size={ICON} />, to: `/projects/${projectId}/members` },
        ]
      : [];

  return (
    <Stack gap={4}>
      <NavItem
        icon={<IconFolders size={ICON} />}
        label="Projects"
        active={pathname === '/'}
        collapsed={collapsed}
        onClick={() => go('/')}
      />
      {projectId != null && (
        <>
          {!collapsed && (
            <Text size="xs" c="dimmed" fw={600} tt="uppercase" mt="sm" px="xs">
              Project
            </Text>
          )}
          {sections.map((s) => (
            <NavItem
              key={s.key}
              icon={s.icon}
              label={s.label}
              active={pathname.startsWith(s.to)}
              collapsed={collapsed}
              onClick={() => go(s.to)}
            />
          ))}
        </>
      )}
    </Stack>
  );
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/components/AppSidebar.test.tsx`
Expected: PASS. (Note: the uppercase "Project" group label is `tt="uppercase"` CSS only, so its text node is still `Project`; the section `NavItem` labelled `Project` is a separate node — `getByText('Project')` may match two nodes. If so, the test uses `getAllByText`. To keep the assertion unambiguous, the group label text is `PROJECT`-cased via CSS but the DOM text is "Project"; adjust the test to `getAllByText('Project')` if `getByText` throws.)

> Implementer note: if `getByText('Project')` throws "multiple elements", change that single assertion to `expect(screen.getAllByText('Project').length).toBeGreaterThan(0)`. Leave the other section assertions as-is.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppSidebar.tsx frontend/src/components/AppSidebar.test.tsx
git commit -m "feat(frontend): AppSidebar (Projects + project sections)"
```

---

### Task 6: ProjectSwitcher shows current project + lands on Tree

**Files:**
- Modify: `frontend/src/projects/ProjectSwitcher.tsx`
- Create: `frontend/src/projects/ProjectSwitcher.test.tsx`

**Interfaces:**
- Produces: unchanged export `ProjectSwitcher` (default). Now reflects the route's project as its value and navigates to `/projects/:id/tree`.

- [ ] **Step 1: Write the failing test** — `frontend/src/projects/ProjectSwitcher.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectSwitcher from './ProjectSwitcher';

test('shows the current project from the route as its value', async () => {
  server.use(
    http.get('/api/projects', () =>
      HttpResponse.json([
        { id: 3, title: 'Felidae', role: 'owner' },
        { id: 9, title: 'Birds', role: 'editor' },
      ]),
    ),
  );
  renderWithProviders(<ProjectSwitcher />, { route: '/projects/3/tree' });
  // Mantine Select renders the selected option's label in its input.
  expect(await screen.findByDisplayValue('Felidae')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/projects/ProjectSwitcher.test.tsx`
Expected: FAIL — current `ProjectSwitcher` hard-codes `value={null}`, so nothing is selected.

- [ ] **Step 3: Rewrite `frontend/src/projects/ProjectSwitcher.tsx`**

```tsx
import { Select } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMatch, useNavigate } from 'react-router-dom';
import { listProjects } from '../api/projects';

// Header project switcher: reflects the active project (read from the route) and, on change, jumps
// to that project's Tree — the primary editing view.
export default function ProjectSwitcher() {
  const navigate = useNavigate();
  const match = useMatch('/projects/:projectId/*');
  const currentId = match?.params.projectId ?? null;
  const { data } = useQuery({ queryKey: ['projects'], queryFn: listProjects });
  return (
    <Select
      placeholder="Select a project"
      searchable
      style={{ minWidth: 220 }}
      value={currentId}
      data={(data ?? []).map((p) => ({ value: String(p.id), label: p.title }))}
      onChange={(val) => val && navigate(`/projects/${val}/tree`)}
    />
  );
}
```

- [ ] **Step 4: Run it, verify it passes**

Run: `npx vitest run src/projects/ProjectSwitcher.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/projects/ProjectSwitcher.tsx frontend/src/projects/ProjectSwitcher.test.tsx
git commit -m "feat(frontend): ProjectSwitcher reflects current project, lands on Tree"
```

---

### Task 7: Shell integration — AppLayout rewrite + ProjectLayout slim-down

**Files:**
- Modify (rewrite): `frontend/src/components/AppLayout.tsx`
- Modify (slim): `frontend/src/projects/ProjectLayout.tsx`
- Create: `frontend/src/components/AppLayout.test.tsx`
- Create: `frontend/src/projects/ProjectLayout.test.tsx`

**Interfaces:**
- Consumes: `AppSidebar` (Task 5), `AppFooter` (Task 3), `ColorSchemeToggle` (Task 2), `ProjectSwitcher` (Task 6), `useMe`, `logout`.
- Produces: the composed shell. `ProjectLayout` becomes a guard that renders `<Outlet/>`.

- [ ] **Step 1: Write the failing AppLayout test** — `frontend/src/components/AppLayout.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import AppLayout from './AppLayout';

function renderShell(route: string) {
  server.use(
    http.get('/api/me', () =>
      HttpResponse.json({ id: 1, username: 'alice', orcid: '', displayName: 'Alice' }),
    ),
    http.get('/api/projects', () =>
      HttpResponse.json([{ id: 3, title: 'Felidae', role: 'owner' }]),
    ),
  );
  return renderWithProviders(
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/projects/:projectId/tree" element={<div>TREE PAGE</div>} />
      </Route>
    </Routes>,
    { route },
  );
}

test('renders brand, footer, user name, and the project section nav', async () => {
  renderShell('/projects/3/tree');
  expect(await screen.findByText('ColDP Editor')).toBeInTheDocument();
  expect(screen.getByText(/ColDP Editor · v/)).toBeInTheDocument(); // footer
  expect(screen.getByText('TREE PAGE')).toBeInTheDocument(); // Outlet
  expect(screen.getByText('Names')).toBeInTheDocument(); // sidebar section
});

test('the desktop collapse toggle hides the sidebar labels', async () => {
  renderShell('/projects/3/tree');
  expect(await screen.findByText('Names')).toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Collapse navigation'));
  expect(screen.queryByText('Names')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/components/AppLayout.test.tsx`
Expected: FAIL — current `AppLayout` has no footer / section nav / "Collapse navigation" control.

- [ ] **Step 3: Rewrite `frontend/src/components/AppLayout.tsx`**

```tsx
import { Anchor, AppShell, Burger, Group, Menu, UnstyledButton } from '@mantine/core';
import { useDisclosure, useLocalStorage } from '@mantine/hooks';
import { IconBook2, IconLogout } from '@tabler/icons-react';
import { Link, Outlet, useMatch, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useMe } from '../auth/useMe';
import { logout } from '../api/auth';
import ProjectSwitcher from '../projects/ProjectSwitcher';
import AppSidebar from './AppSidebar';
import AppFooter from './AppFooter';
import ColorSchemeToggle from './ColorSchemeToggle';

export default function AppLayout() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [mobileOpened, { toggle: toggleMobile, close: closeMobile }] = useDisclosure(false);
  const [collapsed, setCollapsed] = useLocalStorage<boolean>({
    key: 'coldp-nav-collapsed',
    defaultValue: false,
    getInitialValueInEffect: false,
  });

  const projectMatch = useMatch('/projects/:projectId/*');
  const projectId = projectMatch ? Number(projectMatch.params.projectId) : null;

  async function onLogout() {
    try {
      await logout();
    } finally {
      queryClient.clear();
      navigate('/login', { replace: true });
    }
  }

  return (
    <AppShell
      header={{ height: 56 }}
      footer={{ height: 32 }}
      navbar={{ width: collapsed ? 68 : 240, breakpoint: 'sm', collapsed: { mobile: !mobileOpened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" gap="sm" wrap="nowrap">
          <Burger
            opened={mobileOpened}
            onClick={toggleMobile}
            hiddenFrom="sm"
            size="sm"
            aria-label="Open navigation"
          />
          <Burger
            opened={!collapsed}
            onClick={() => setCollapsed((c) => !c)}
            visibleFrom="sm"
            size="sm"
            aria-label="Collapse navigation"
          />
          <Anchor component={Link} to="/" underline="never" c="inherit">
            <Group gap={6} wrap="nowrap">
              <IconBook2 size={20} />
              <span style={{ fontWeight: 700 }}>ColDP Editor</span>
            </Group>
          </Anchor>
          <ProjectSwitcher />
          <Group ml="auto" gap="sm" wrap="nowrap">
            <ColorSchemeToggle />
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton>{me?.displayName || me?.username}</UnstyledButton>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item leftSection={<IconLogout size={14} />} onClick={onLogout}>
                  Logout
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        <AppSidebar projectId={projectId} collapsed={collapsed} onNavigate={closeMobile} />
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <AppShell.Footer>
        <AppFooter />
      </AppShell.Footer>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run the AppLayout test, verify it passes**

Run: `npx vitest run src/components/AppLayout.test.tsx`
Expected: PASS. (Both burgers are in the DOM in jsdom; the test targets the desktop one by its `Collapse navigation` label.)

- [ ] **Step 5: Write the failing ProjectLayout test** — `frontend/src/projects/ProjectLayout.test.tsx`

```tsx
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import ProjectLayout from './ProjectLayout';

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId" element={<ProjectLayout />}>
        <Route index element={<div>SECTION CONTENT</div>} />
      </Route>
    </Routes>,
    { route },
  );
}

test('renders the child section once the project loads', async () => {
  server.use(
    http.get('/api/projects/3', () =>
      HttpResponse.json({ id: 3, title: 'Felidae', role: 'owner' }),
    ),
  );
  renderAt('/projects/3');
  expect(await screen.findByText('SECTION CONTENT')).toBeInTheDocument();
  // No tab strip any more — navigation lives in the sidebar.
  expect(screen.queryByRole('tab')).not.toBeInTheDocument();
});

test('shows a not-found alert when the project 404s', async () => {
  server.use(http.get('/api/projects/999', () => new HttpResponse(null, { status: 404 })));
  renderAt('/projects/999');
  expect(await screen.findByText('Project not found')).toBeInTheDocument();
});
```

- [ ] **Step 6: Run it, verify it fails**

Run: `npx vitest run src/projects/ProjectLayout.test.tsx`
Expected: FAIL — current `ProjectLayout` still renders `Tabs` (a `tab` role exists), so the `queryByRole('tab')` assertion fails.

- [ ] **Step 7: Slim `frontend/src/projects/ProjectLayout.tsx`**

```tsx
import { Alert, Center, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Outlet, useParams } from 'react-router-dom';
import { getProject } from '../api/projects';

// Thin guard for the project routes: loads the project (shared ['project', id] key so section pages
// dedupe), shows a loader / not-found, and otherwise renders the active section via <Outlet/>. The
// section navigation and project title now live in the shell (sidebar + header switcher).
export default function ProjectLayout() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['project', id],
    queryFn: () => getProject(id),
    enabled: Number.isFinite(id),
  });

  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  if (isError || !data) return <Alert color="red">Project not found</Alert>;
  return <Outlet />;
}
```

- [ ] **Step 8: Run the ProjectLayout test, verify it passes**

Run: `npx vitest run src/projects/ProjectLayout.test.tsx`
Expected: PASS.

- [ ] **Step 9: Verify AppRouting.test still passes; full suite + build**

Run: `npx vitest run && npm run build`
Expected: all PASS (existing `AppRouting.test` still finds the `Lepidoptera` project link and the `Alice` user name in the new shell). Build SUCCESS.

If `AppRouting.test` fails only because of a changed accessible structure, adjust that test minimally (do not weaken its intent). None is expected.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/AppLayout.tsx frontend/src/components/AppLayout.test.tsx frontend/src/projects/ProjectLayout.tsx frontend/src/projects/ProjectLayout.test.tsx
git commit -m "feat(frontend): new app shell (collapsible sidebar + footer); slim ProjectLayout"
```

---

### Task 8: Verification — full suite, build, and live browser check

**Files:** none (verification only).

- [ ] **Step 1: Full suite + build**

Run: `npx vitest run && npm run build`
Expected: all tests PASS; build SUCCESS.

- [ ] **Step 2: Boot backend (dev) + frontend**

```bash
cd /Users/markus/code/col/coldp-editor && docker compose up -d
cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd ../frontend && npm run dev &
```
Wait for the backend to log `Started EditorApplication` and Vite to serve on :5173.

- [ ] **Step 3: Drive the shell in a browser (chrome-devtools MCP)**

- Navigate to `http://localhost:5173`, log in `admin` / `admin`.
- Confirm: header brand + project switcher (shows current project when inside one) + color-scheme toggle + user menu; **collapsible sidebar** (expand/collapse via the burger, navigate Tree/Names/Project/Members); **footer** with version + mode.
- Toggle **light ↔ dark**; eyeball the shell and the Tree / Names / Project / Members pages in both. Fix anything that reads wrong (e.g. hard-coded colors) and re-run `npx vitest run`.

- [ ] **Step 4: Live-check the Tree move/reparent UI (still pending from the prior task)**

- In the seeded **Felidae (sample data)** project, open the Tree, select **Felis catus**, use ⋮ → **Move…**, pick **Panthera** as the new parent, confirm the tree + breadcrumb refresh. Then Move it again with **Make it a root**. Confirm the greyed-out self/subtree in the picker.

- [ ] **Step 5: Stop the dev servers**

```bash
pkill -f "spring-boot:run"; pkill -f "vite"
```

- [ ] **Step 6: Update the resume notes**

Mark the shell redesign done in `todo.md`, append a summary to `.superpowers/sdd/progress.md`, and commit `todo.md`.

---

## Self-Review

**Spec coverage:**
- Layout/structure (AppShell header+navbar+footer; AppLayout owns chrome; projectId via useMatch; ProjectLayout slimmed) → Tasks 7.
- Sidebar (Projects + Tree/Names/Project/Members; collapse rail; persistence; NavItem) → Tasks 4, 5, 7.
- Header (burger, brand, switcher, toggle, user menu) → Task 7. Switcher fix → Task 6.
- Theme + dark mode (createTheme slate; ColorSchemeScript-equivalent; toggle) → Tasks 1, 2.
- Footer (version via define; mode; repo link) → Tasks 1, 3.
- Testing + verification (unit tests per component; browser light/dark; Move UI live check) → each task + Task 8.
All spec sections map to tasks. ✓

**Placeholder scan:** No TBD/TODO; every code + test step has full content. The only conditional is the `getByText('Project')` multiplicity note in Task 5, which gives the exact fallback assertion. ✓

**Type consistency:** `NavItem` props (`icon/label/active/collapsed/onClick`) are consumed identically in `AppSidebar`. `AppSidebar` props (`projectId/collapsed/onNavigate`) match `AppLayout`'s usage. `theme` export consumed in `main.tsx` + `test/utils`. `__APP_VERSION__` defined (vite.config) + declared (vite-env.d.ts) + used (AppFooter). `useMatch('/projects/:projectId/*')` param name `projectId` consistent across ProjectSwitcher + AppLayout. ✓

**Known deviations from spec (intentional):** ColorSchemeScript is realized as the equivalent inline snippet in `index.html` (Vite SPA has no SSR head to render the React component into) — functionally identical (same key/attribute). The footer repo link uses the assumed `CatalogueOfLife/coldp-editor` URL since no git remote is configured; flagged in code.
