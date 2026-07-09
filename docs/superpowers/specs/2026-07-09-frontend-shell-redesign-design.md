# ColDP Editor — Frontend shell (re)design

**Date:** 2026-07-09
**Scope:** The outer application chrome — header, navigation, footer, theme — of the React/Mantine
frontend. Page-level content (tree, names search, taxon detail, metadata, members forms) is **out of
scope** except where it must be verified to read correctly under the new theme / dark mode.

## Motivation

The current shell (`src/components/AppLayout.tsx` + per-project `Tabs` in
`src/projects/ProjectLayout.tsx`) is minimal and has concrete problems:

- **White-on-white header.** `MantineProvider` uses the stock theme (white header), yet the header
  brand and username render with `c="white"` — near-invisible. Latent visual bug.
- **No footer.** No version / environment / links anywhere.
- **Project switcher never shows the current project.** `ProjectSwitcher` is a bare `Select` with
  `value={null}`.
- **Navigation is split and won't scale.** Global bits (brand, switcher, user) live in the header;
  per-project navigation is a horizontal `Tabs` strip in `ProjectLayout`. The per-project section
  list is going to grow (References, Issues, Changelog, Tools, import/export), and a horizontal tab
  strip runs out of room.
- **No theming.** No `createTheme`, no dark mode.

## Decisions (agreed)

1. **Collapsible icon-rail sidebar** for navigation (Mantine `AppShell.Navbar`), with a header
   toggle. Expanded shows icon + label; collapsed shows an icon rail with tooltips.
2. **Neutral / slate theme.** No CoL green / logo branding. A restrained slate (blue-gray) primary.
3. **Light/dark mode** with a persisted toggle in the header.

These were chosen over the alternatives (top-tabs-improved; CoL-green branding; light-only) during
brainstorming.

## Design

### Layout & structure

`AppLayout` is rewritten to own **all** shell chrome via a single `AppShell` with `header`,
`navbar`, and `footer` regions; `main` renders the routed `<Outlet/>`.

- Region sizes: header **56px**, footer **32px**, navbar width **240px** expanded / **68px**
  collapsed (icon rail). `main` padding `md`.
- `AppLayout` derives the active project id from the route with
  `useMatch('/projects/:projectId/*')` — it is the parent layout for both the home (project list)
  route and the project routes, so it must detect context from the URL rather than `useParams`
  (which, in the parent, would not yet see `:projectId`).

`ProjectLayout` slims to a **pure guard**: it fetches `['project', id]`, renders a loader while
pending and a "Project not found" alert on error/404, and otherwise renders `<Outlet/>`. Its former
`Title` and `Tabs` are removed — the project title is visible via the header switcher, and the
section navigation moves into the sidebar. The shared `['project', id]` query key is preserved so
pages (TaxonDetail, TreePage, etc.) that read it still dedupe.

### Navigation

**Sidebar (`AppShell.Navbar`, owned by AppLayout):**

- A top-level **Projects** item (icon `IconFolders`) → navigates to `/`.
- When the route is inside a project, a **PROJECT** section group (section label hidden when
  collapsed) with the built sections, each an icon + label whose active state is derived from the
  path:
  - **Tree** — `IconBinaryTree` (or `IconHierarchy2`) → `/projects/:id/tree`
  - **Names** — `IconList` (or `IconSearch`) → `/projects/:id/names`
  - **Project** — `IconSettings` → `/projects/:id/metadata` — the project-level settings/metadata
    page. Labelled **Project** (not "Metadata") because metadata is one facet of the project and more
    project-level settings are expected to join this section later. Route and page component
    (`ProjectMetadataPage`) are unchanged; only the nav label + icon differ.
  - **Members** — `IconUsers` → `/projects/:id/members`
- (References / Issues / Changelog / Tools are **not** added now; they slot in when built.)
- **Collapse:** a `Burger` in the header toggles the rail. Desktop collapsed/expanded state persists
  via `@mantine/hooks` `useLocalStorage` (`coldp-nav-collapsed`). On mobile (`< sm` breakpoint) the
  navbar overlays and the Burger opens/closes it (`AppShell` `collapsed.mobile`). Collapsed rail
  items show the label as a Mantine `Tooltip`.
- A small reusable `NavItem` presentational component (icon, label, active, collapsed, onClick)
  keeps the rail rows consistent and testable in isolation.

**Header (left→right):**

- `Burger` (sidebar toggle).
- Brand: a neutral Tabler glyph mark + "ColDP Editor" wordmark, links to `/`. (Upper-left brand slot;
  the glyph is a placeholder for a future SVG logo.)
- **Current project name** — read-only (see below).
- Spacer (`ml="auto"`-style).
- **Color-scheme toggle** (sun/moon).
- **User menu**: `me.displayName || me.username` → `Logout` (existing behavior preserved).

**Current project name** (revised 2026-07-09, post-implementation, per user request — replaces the
originally-planned `ProjectSwitcher`):

- The header shows the active project's **title read-only** next to the brand, and nothing on the
  home route. Component: `CurrentProjectName` (reads `['project', id]`, shared with `ProjectLayout`).
- **Selecting** a project is done by clicking a row on the Projects list page — no header dropdown.
- The original plan's `ProjectSwitcher` `Select` (current-project value + navigate-to-`/tree`) was
  built (Task 6) and then removed; `ProjectSwitcher.tsx` + its test are deleted.

### Theme & dark mode

- A real theme via `createTheme`, passed to `MantineProvider`:
  - `primaryColor: 'slate'` where `slate` is a custom 10-shade blue-gray `colors` ramp (neutral,
    professional; not one of Mantine's default hues).
  - `defaultRadius: 'sm'`.
- **Color scheme:** `MantineProvider` gets `defaultColorScheme` (`'light'`); a header toggle uses
  `useMantineColorScheme().toggleColorScheme()`. Mantine persists the choice to `localStorage`. Add
  `<ColorSchemeScript defaultColorScheme="light" />` to `index.html` `<head>` to prevent a
  first-paint flash.
- The shell and existing pages must read correctly in both schemes — verified by eyeballing (see
  Verification). No per-page restyle is planned; anything that reads wrong in dark mode is fixed as
  found.

### Footer (`AppShell.Footer`)

Slim (32px), subtle (dimmed text, top border), scheme-aware:

- Left: `ColDP Editor · v{version} · {mode}` — `version` injected from `package.json` via a Vite
  `define` (`__APP_VERSION__`); `mode` from `import.meta.env.MODE` (`development` / `production`).
- Right: a link to the GitHub repository.

`vite.config.ts` gains `define: { __APP_VERSION__: JSON.stringify(pkg.version) }` and a matching
`declare const __APP_VERSION__: string` in a `*.d.ts` (or `vite-env.d.ts`) for the type-checker.

## Components (files)

- **Rewrite** `src/components/AppLayout.tsx` — the AppShell + header + footer; wires navbar + toggles.
- **New** `src/components/AppSidebar.tsx` — the navbar content (Projects + project sections), driven
  by the active project id + path.
- **New** `src/components/NavItem.tsx` — one rail row (icon/label/active/collapsed/tooltip).
- **New** `src/components/AppFooter.tsx` — the footer bar.
- **New** `src/components/ColorSchemeToggle.tsx` — the sun/moon button.
- **Rewrite (slim)** `src/projects/ProjectLayout.tsx` — guard + `<Outlet/>` only.
- **Edit** `src/projects/ProjectSwitcher.tsx` — current-project value + navigate to tree.
- **New** `src/theme.ts` — `createTheme` with the slate palette; imported by `main.tsx`.
- **Edit** `src/main.tsx` — apply theme + `defaultColorScheme`.
- **Edit** `index.html` — `ColorSchemeScript`.
- **Edit** `vite.config.ts` (+ env `.d.ts`) — `__APP_VERSION__` define.

## Testing

- **Update** `src/AppRouting.test.tsx` for the new shell (no `Tabs`; sidebar present).
- **New/updated** focused tests (Vitest + Testing Library + MSW, `renderWithProviders`):
  - Sidebar renders the project section links when inside a project and navigates on click; renders
    only **Projects** on home.
  - Collapse toggle switches the rail (labels hidden when collapsed) and persists.
  - `ProjectSwitcher` shows the current project as its value and navigates to `/tree` on change.
  - Color-scheme toggle flips `useMantineColorScheme` (assert via the toggle's state/`document`).
  - `AppFooter` shows the version + mode.
- All existing page tests stay green (50 currently).

## Verification

- `npm test` (all suites) + `npm run build` (tsc + vite) green.
- **Boot the app in a browser (chrome-devtools MCP)**: log in (`admin`/`admin`), confirm the shell —
  header, collapsible sidebar (expand/collapse + navigate), footer — and toggle **light ↔ dark**,
  eyeballing the shell and the tree / names / metadata / members pages in both.
- While in the browser, perform the **still-pending live check of the Tree move/reparent UI** on the
  seeded Felidae sample: move a species to a new parent and to root, confirm the tree + breadcrumb
  refresh.

## Out of scope / future

- Adding not-yet-built sections (References, Issues, Changelog, Tools, import/export) to the sidebar.
- Per-page visual redesign; CoL/ChecklistBank branding; a logo asset.
- Route-level code splitting (the bundle-size warning is a separate, previously-recorded follow-up).
- Breadcrumbs in the header (the tree already has its own breadcrumb).
