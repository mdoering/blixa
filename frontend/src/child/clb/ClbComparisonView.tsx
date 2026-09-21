import { ActionIcon, Anchor, Badge, Button, Group, ScrollArea, Stack, Table, Text, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import type { ClbComparison } from '../../api/clb';
import DatasetLabel from '../../clb/DatasetLabel';
import { useLanguageName } from '../../vocab/useVocab';

// The "editor" side of the comparison, shaped to line up with ClbComparison.
export interface OursSide {
  scientificName: string | null;
  authorship: string | null;
  rank: string | null;
  status: string | null;
  // "scientificName authorship" of the accepted name when the focal usage is a synonym.
  acceptedName?: string | null;
  gender?: string | null;
  etymology?: string | null;
  // citation of our published-in reference, and the page within it
  publishedIn?: string | null;
  publishedInPage?: string | null;
  classification: { rank: string | null; name: string | null }[];
  synonyms: { scientificName: string | null; authorship: string | null; status: string | null }[];
  vernacularNames?: { name: string | null; language: string | null }[];
  typeMaterial?: { status: string | null; citation: string | null; catalogNumber: string | null }[];
  nameRelations?: { type: string | null; relatedName: string | null }[];
}

// Form fields a CLB value can be copied into (TaxonDetail's edit form, unsaved until the user saves).
export type CopyField = 'scientificName' | 'authorship' | 'rank' | 'gender' | 'etymology' | 'publishedInPage';

// The "«" actions. Each is optional: a missing handler hides that row's copy buttons.
export interface CopyHandlers {
  field?: (field: CopyField, value: string) => void;
  publishedIn?: () => void;
  synonyms?: (ids: string[]) => void;
  vernaculars?: (ids: string[]) => void;
  typeMaterial?: (ids: string[]) => void;
  nameRelations?: (ids: string[]) => void;
  // opens the "wire into tree" panel for the CLB classification
  classification?: () => void;
  // a record copy is in flight -- disables the record buttons
  busy?: boolean;
}

const norm = (s: string | null | undefined) => (s ?? '').trim().toLowerCase();
const same = (a: string | null | undefined, b: string | null | undefined) => norm(a) === norm(b);
const blank = (s: string | null | undefined) => norm(s) === '';

function Cell({ value, differs }: { value: string | null | undefined; differs: boolean }) {
  const shown = value ?? '—';
  return differs ? (
    <Text component="span" fw={600} c="orange.7">
      {shown}
    </Text>
  ) : (
    <Text component="span">{shown}</Text>
  );
}

function CopyButton({ label, onClick, disabled }: { label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <Tooltip label={label} withArrow openDelay={300}>
      <ActionIcon variant="light" size="sm" aria-label={label} onClick={onClick} disabled={disabled}>
        «
      </ActionIcon>
    </Tooltip>
  );
}

function rankMap(items: { rank: string | null; name: string | null }[]): Map<string, string> {
  const m = new Map<string, string>();
  for (const it of items) if (it.rank) m.set(norm(it.rank), it.name ?? '');
  return m;
}

const typeLabel = (t: { status: string | null; citation: string | null; catalogNumber: string | null }) =>
  [t.status, t.citation ?? t.catalogNumber].filter(Boolean).join(': ');
const relationLabel = (r: { type: string | null; relatedName: string | null }) =>
  `${r.type ?? ''}: ${r.relatedName ?? ''}`;
// Relations compare on type + related scientific name: our side's label carries no authorship.
const relKey = (type: string | null, scientificName: string | null) => `${norm(type)}|${norm(scientificName)}`;

interface ListItem {
  key: string; // normalized identity, compared across sides
  label: ReactNode;
  copyId?: string | null; // CLB id to copy (CLB side only)
}

// Side-by-side comparison of the focal taxon (ours) vs a CLB taxon, with differing values
// highlighted and "«" buttons that pull a CLB value or record over to our side. Rows empty on both
// sides are hidden.
export default function ClbComparisonView({
  ours,
  clb,
  copy = {},
}: {
  ours: OursSide;
  clb: ClbComparison;
  copy?: CopyHandlers;
}) {
  const languageName = useLanguageName();
  const vernacularLabel = (v: { name: string | null; language: string | null }) =>
    v.language ? `${v.name ?? ''} (${languageName(v.language)})` : v.name ?? '';
  const clbByRank = rankMap(clb.classification);
  const oursByRank = rankMap(ours.classification);

  // A scalar row. `onCopy` (when given) offers "«" if the CLB value is set and differs from ours.
  const scalar = (label: string, a: string | null | undefined, b: string | null | undefined, onCopy?: () => void) => {
    if (blank(a) && blank(b)) return null;
    const differs = !same(a, b);
    return (
      <Table.Tr key={label}>
        <Table.Th w={130}>{label}</Table.Th>
        <Table.Td>
          <Cell value={a} differs={differs} />
        </Table.Td>
        <Table.Td w={40}>
          {onCopy && differs && !blank(b) && <CopyButton label={`Copy ${label.toLowerCase()} from CLB`} onClick={onCopy} />}
        </Table.Td>
        <Table.Td>
          <Cell value={b} differs={differs} />
        </Table.Td>
      </Table.Tr>
    );
  };

  const fieldCopy = (field: CopyField, value: string | null | undefined) =>
    copy.field && value ? () => copy.field!(field, value) : undefined;

  const classificationCell = (items: { rank: string | null; name: string | null }[], other: Map<string, string>) => (
    <Stack gap={2}>
      {items.length === 0 && <Text c="dimmed" size="sm">—</Text>}
      {items.map((it, i) => {
        const differs = it.rank ? !same(other.get(norm(it.rank)) ?? null, it.name) : false;
        return (
          <Text key={`${it.rank}-${i}`} size="sm" c={differs ? 'orange.7' : undefined} fw={differs ? 600 : undefined}>
            {it.rank ? `${it.rank}: ` : ''}
            {it.name ?? '—'}
          </Text>
        );
      })}
    </Stack>
  );

  // A record-list row: items only on one side get an "only here" badge; CLB-only items with a copy
  // id get a "«" (and the middle column a "copy all missing").
  const listRow = (label: string, oursItems: ListItem[], clbItems: ListItem[], onCopy?: (ids: string[]) => void) => {
    if (oursItems.length === 0 && clbItems.length === 0) return null;
    const oursKeys = new Set(oursItems.map((i) => i.key));
    const clbKeys = new Set(clbItems.map((i) => i.key));
    const missing = clbItems.filter((i) => !oursKeys.has(i.key) && i.copyId).map((i) => i.copyId as string);
    const cell = (items: ListItem[], otherKeys: Set<string>, copyable: boolean) => (
      <ScrollArea.Autosize mah={240} type="auto">
        <Stack gap={2}>
          {items.length === 0 && <Text c="dimmed" size="sm">—</Text>}
          {items.map((it, i) => {
            const onlyHere = !otherKeys.has(it.key);
            return (
              <Group key={`${it.key}-${i}`} gap={6} wrap="nowrap">
                {copyable && onCopy && onlyHere && it.copyId && (
                  <CopyButton
                    label={`Copy to ours`}
                    disabled={copy.busy}
                    onClick={() => onCopy([it.copyId as string])}
                  />
                )}
                <Text size="sm">{it.label}</Text>
                {onlyHere && (
                  <Badge size="xs" color="orange" variant="light">
                    only here
                  </Badge>
                )}
              </Group>
            );
          })}
        </Stack>
      </ScrollArea.Autosize>
    );
    return (
      <Table.Tr key={label}>
        <Table.Th>{label}</Table.Th>
        <Table.Td>{cell(oursItems, clbKeys, false)}</Table.Td>
        <Table.Td>
          {onCopy && missing.length > 1 && (
            <Tooltip label={`Copy all ${missing.length} missing ${label.toLowerCase()}`} withArrow>
              <Button size="compact-xs" variant="light" disabled={copy.busy} onClick={() => onCopy(missing)}>
                « all
              </Button>
            </Tooltip>
          )}
        </Table.Td>
        <Table.Td>{cell(clbItems, oursKeys, true)}</Table.Td>
      </Table.Tr>
    );
  };

  const synKey = (s: { scientificName: string | null }) => norm(s.scientificName);
  const vnKey = (v: { name: string | null; language: string | null }) => `${norm(v.name)}|${norm(v.language)}`;
  const tmKey = (t: { citation: string | null; catalogNumber: string | null }) => norm(t.citation ?? t.catalogNumber);

  return (
    <Table withRowBorders verticalSpacing="xs">
      <Table.Thead>
        <Table.Tr>
          <Table.Th />
          <Table.Th>Ours</Table.Th>
          <Table.Th />
          <Table.Th>
            CLB{' '}
            <Anchor href={clb.link} target="_blank" rel="noopener noreferrer" size="sm">
              <DatasetLabel datasetKey={clb.datasetKey} /> ↗
            </Anchor>
          </Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {scalar('Name', ours.scientificName, clb.scientificName, fieldCopy('scientificName', clb.scientificName))}
        {scalar('Authorship', ours.authorship, clb.authorship, fieldCopy('authorship', clb.authorship))}
        {scalar('Rank', ours.rank, clb.rank, fieldCopy('rank', clb.rank))}
        {scalar('Status', ours.status, clb.status)}
        {scalar('Accepted name', ours.acceptedName, clb.acceptedName)}
        {scalar('Gender', ours.gender, clb.gender, fieldCopy('gender', clb.gender))}
        {scalar('Etymology', ours.etymology, clb.etymology, fieldCopy('etymology', clb.etymology))}
        {scalar('Published in', ours.publishedIn, clb.publishedIn, copy.publishedIn)}
        {scalar('Page', ours.publishedInPage, clb.publishedInPage, fieldCopy('publishedInPage', clb.publishedInPage))}
        {(ours.classification.length > 0 || clb.classification.length > 0) && (
          <Table.Tr>
            <Table.Th>Classification</Table.Th>
            <Table.Td>{classificationCell(ours.classification, clbByRank)}</Table.Td>
            <Table.Td>
              {copy.classification && clb.classification.length > 0 && (
                <Tooltip label="Wire into our tree along this classification…" withArrow>
                  <ActionIcon variant="light" size="sm" aria-label="Wire into tree" onClick={copy.classification}>
                    «
                  </ActionIcon>
                </Tooltip>
              )}
            </Table.Td>
            <Table.Td>{classificationCell(clb.classification, oursByRank)}</Table.Td>
          </Table.Tr>
        )}
        {listRow(
          'Synonyms',
          ours.synonyms.map((s) => ({ key: synKey(s), label: `${s.scientificName ?? ''} ${s.authorship ?? ''}` })),
          clb.synonyms.map((s) => ({ key: synKey(s), label: `${s.scientificName ?? ''} ${s.authorship ?? ''}`, copyId: s.id })),
          copy.synonyms,
        )}
        {listRow(
          'Name relations',
          (ours.nameRelations ?? []).map((r) => ({ key: relKey(r.type, r.relatedName), label: relationLabel(r) })),
          (clb.nameRelations ?? []).map((r) => ({
            key: relKey(r.type, r.relatedScientificName ?? r.relatedName),
            label: relationLabel(r),
            copyId: r.id,
          })),
          copy.nameRelations,
        )}
        {listRow(
          'Vernacular names',
          (ours.vernacularNames ?? []).map((v) => ({ key: vnKey(v), label: vernacularLabel(v) })),
          (clb.vernacularNames ?? []).map((v) => ({ key: vnKey(v), label: vernacularLabel(v), copyId: v.id })),
          copy.vernaculars,
        )}
        {listRow(
          'Type material',
          (ours.typeMaterial ?? []).map((t) => ({ key: tmKey(t), label: typeLabel(t) })),
          (clb.typeMaterial ?? []).map((t) => ({ key: tmKey(t), label: typeLabel(t), copyId: t.id })),
          copy.typeMaterial,
        )}
      </Table.Tbody>
    </Table>
  );
}
