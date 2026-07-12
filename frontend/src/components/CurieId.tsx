import { ActionIcon, Anchor, Group, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { IconPencil } from '@tabler/icons-react';
import { getIdScopes } from '../api/coldp';
import type { IdScope } from '../api/types';

// A `{id}`/`{ID}`/`{value}` placeholder inside an IdScope's `link` marks where the (encoded) id
// substitutes into the resolver URL -- e.g. a scope whose link is
// "https://example.org/name/{id}/details". Non-global, so repeated `.test()` calls across
// invocations don't carry stateful `lastIndex` between them.
const PLACEHOLDER = /\{(?:id|ID|value)\}/;

// Resolves a CURIE's `scope:id` to a clickable URL using the CLB identifier-scope vocab (see
// backend IdScopeService/IdScopeController): finds the scope (case-insensitively) among `scopes`
// and, if it has a resolver `link`, either substitutes the encoded id into a placeholder or
// appends it to the link's path. Returns null when the scope is unknown or has no link -- callers
// then render the CURIE as plain text instead of a link.
export function resolveIdentifierUrl(scope: string, id: string, scopes: IdScope[]): string | null {
  const match = scopes.find((s) => s.scope.toLowerCase() === scope.toLowerCase());
  const link = match?.link;
  if (!link) return null;
  const encoded = encodeURIComponent(id);
  if (PLACEHOLDER.test(link)) {
    // Fresh `g`-flagged literal per call, so it replaces every occurrence without leaking state.
    return link.replace(/\{(?:id|ID|value)\}/g, encoded);
  }
  return `${link.replace(/\/+$/, '')}/${encoded}`;
}

export interface CurieIdProps {
  scope: string;
  id: string;
  // Shows a small pencil icon right after the CURIE, wired to `onEdit`, when both are set --
  // omit either (or leave `editable` false, the default) for a read-only chip.
  editable?: boolean;
  onEdit?: () => void;
}

// Renders a `scope:id` CURIE, linked to its resolver URL (see resolveIdentifierUrl) when the CLB
// vocab has one for this scope, plain text otherwise. The scopes vocab is fetched via the same
// ['idScopes'] query key used elsewhere (ProjectMetadataPage, ImportProjectModal), so rendering
// several CurieId chips on one page only issues a single request.
export default function CurieId({ scope, id, editable = false, onEdit }: CurieIdProps) {
  const { data: scopes } = useQuery({ queryKey: ['idScopes'], queryFn: getIdScopes });
  const url = scopes ? resolveIdentifierUrl(scope, id, scopes) : null;
  const curie = `${scope}:${id}`;

  return (
    <Group gap={4} wrap="nowrap" component="span">
      {url ? (
        <Anchor href={url} target="_blank" rel="noreferrer" size="sm">
          {curie}
        </Anchor>
      ) : (
        <Text span size="sm">
          {curie}
        </Text>
      )}
      {editable && onEdit && (
        <ActionIcon variant="subtle" size="xs" onClick={onEdit} aria-label="Edit identifier">
          <IconPencil size={12} />
        </ActionIcon>
      )}
    </Group>
  );
}
