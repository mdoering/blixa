import { ActionIcon, Anchor, Badge, Group, Stack, Text, Title, Tooltip } from '@mantine/core';
import { IconArrowsExchange } from '@tabler/icons-react';
import { useQueries } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { getUsage } from '../api/usages';
import type { NameUsage } from '../api/types';
import { statusMeta } from '../names/statusMeta';
import ChangeAcceptedModal from './ChangeAcceptedModal';

export interface NameHeaderProps {
  pid: number;
  usage: NameUsage;
  canEdit: boolean;
  /** navigate the form to another usage; the accepted names are plain text without it. */
  onNavigate?: (id: number) => void;
  /** extra controls placed at the right end of the title line (TaxonDetail's action icons). */
  actions?: ReactNode;
}

function withAuthorship(name: string | null, authorship: string | null): string {
  return [name, authorship].filter(Boolean).join(' ');
}

// The focal name as a heading above the edit form -- full name with authorship, rank and status --
// so the curator always sees which (saved) name the tabs below belong to. A synonym/misapplied name
// adds a line naming the accepted name(s) it hangs under (several for pro parte), each a link, with
// a warning badge when a linked name is not actually accepted (the synonym_of_non_accepted issue),
// and the change icon that re-points it (ChangeAcceptedModal).
export default function NameHeader({ pid, usage, canEdit, onNavigate, actions }: NameHeaderProps) {
  const isSynonym = usage.status === 'SYNONYM' || usage.status === 'MISAPPLIED';
  const acceptedIds = isSynonym ? usage.acceptedParentIds ?? [] : [];
  const [changeOpen, setChangeOpen] = useState(false);

  // Same ['usage', pid, id] key as TaxonDetail's own fetch, so navigating to an accepted name is
  // served from cache (and ClassificationBar reuses the primary accepted name's row).
  const accepted = useQueries({
    queries: acceptedIds.map((id) => ({
      queryKey: ['usage', pid, id],
      queryFn: () => getUsage(pid, id),
    })),
  });

  // Italic from the genus group down: the parser sets `genus` on every bi/trinomial and infrageneric
  // name, and a genus itself is a uninomial of rank genus.
  const italic = usage.genus != null || usage.rank === 'genus';
  const status = usage.status ? statusMeta(usage.status) : null;

  return (
    <Stack gap={4}>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Group gap="xs" align="baseline" wrap="wrap" style={{ flex: 1, minWidth: 0 }}>
          <Title order={4} fw={600}>
            <Text span inherit fs={italic ? 'italic' : undefined}>
              {usage.scientificName}
            </Text>
            {usage.authorship && (
              <Text span inherit fw={400} c="dimmed">
                {' '}
                {usage.authorship}
              </Text>
            )}
          </Title>
          {usage.rank && (
            <Badge variant="outline" color="gray" size="sm" radius="sm" tt="none" style={{ flexShrink: 0 }}>
              {usage.rank}
            </Badge>
          )}
          {status && (
            <Badge variant="light" color={status.color} size="sm" radius="sm" style={{ flexShrink: 0 }}>
              {status.label}
            </Badge>
          )}
        </Group>
        {actions}
      </Group>
      {isSynonym && (
        <Group gap={6} wrap="wrap" align="center">
          <Text size="sm" c="dimmed">
            {usage.status === 'MISAPPLIED' ? 'Misapplied for' : 'Synonym of'}
          </Text>
          {acceptedIds.length === 0 && (
            <Text size="sm" c="red">
              not linked to an accepted name
            </Text>
          )}
          {acceptedIds.map((id, i) => {
            const a = accepted[i]?.data;
            const label = a ? withAuthorship(a.scientificName, a.authorship) : `#${id}`;
            const notAccepted = a && a.status !== 'ACCEPTED' && a.status ? statusMeta(a.status) : null;
            return (
              <Group key={id} gap={4} wrap="nowrap">
                {onNavigate ? (
                  <Anchor size="sm" fw={600} style={{ cursor: 'pointer' }} onClick={() => onNavigate(id)}>
                    {label}
                  </Anchor>
                ) : (
                  <Text size="sm" fw={600}>
                    {label}
                  </Text>
                )}
                {notAccepted && (
                  <Tooltip label="Not an accepted name — a synonym must point to an accepted taxon" withArrow>
                    <Badge variant="filled" color="red" size="sm" radius="sm" style={{ flexShrink: 0 }}>
                      {notAccepted.label}
                    </Badge>
                  </Tooltip>
                )}
              </Group>
            );
          })}
          {canEdit && acceptedIds.length > 0 && (
            <Tooltip label="Change accepted name" withArrow>
              <ActionIcon
                variant="subtle"
                color="gray"
                size="sm"
                aria-label="Change accepted name"
                onClick={() => setChangeOpen(true)}
              >
                <IconArrowsExchange size={15} />
              </ActionIcon>
            </Tooltip>
          )}
        </Group>
      )}
      {isSynonym && acceptedIds.length > 0 && (
        <ChangeAcceptedModal
          pid={pid}
          usage={{ id: usage.id, scientificName: usage.scientificName }}
          currentAcceptedId={acceptedIds[0]}
          opened={changeOpen}
          onClose={() => setChangeOpen(false)}
        />
      )}
    </Stack>
  );
}
