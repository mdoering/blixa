import {
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Divider,
  Group,
  Loader,
  Modal,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconSparkles } from '@tabler/icons-react';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { requestSuggestions, type AiReferenceCard, type AiSynonymCard } from '../api/ai';
import { createUsage, linkSynonym } from '../api/usages';

interface AiSuggestModalProps {
  pid: number;
  usageId: number;
  usageRank?: string | null;
  usageName?: string | null;
  opened: boolean;
  onClose: () => void;
}

const synKey = (s: AiSynonymCard) => `${s.scientificName}|${s.authorship ?? ''}`;

function VerifiedRef({ reference }: { reference: AiReferenceCard | null }) {
  if (!reference) return null;
  return (
    <Badge color="green" variant="light" size="sm" leftSection={<IconCheck size={11} />}>
      {reference.citation ? reference.citation : reference.doi}
    </Badge>
  );
}

// Amber "verify me" marker for AI-suggested facts we can't auto-verify.
function Unverified() {
  return (
    <Badge color="yellow" variant="light" size="sm">
      AI — verify
    </Badge>
  );
}

// The AI-curation surface for a focal taxon: fetches suggestions on open and lets the curator accept
// synonyms straight into the tree (create SYNONYM usage + link). References are verified server-side;
// the remaining categories are shown for manual review. The AI never writes -- every accept is an
// explicit curator action through the existing create endpoints.
export default function AiSuggestModal({
  pid,
  usageId,
  usageRank,
  usageName,
  opened,
  onClose,
}: AiSuggestModalProps) {
  const queryClient = useQueryClient();
  const [done, setDone] = useState<Set<string>>(new Set());
  const suggest = useMutation({ mutationFn: () => requestSuggestions(pid, usageId) });

  // Fire once each time the modal opens (per taxon); reset when it closes.
  const firedFor = useRef<number | null>(null);
  useEffect(() => {
    if (opened && firedFor.current !== usageId) {
      firedFor.current = usageId;
      setDone(new Set());
      suggest.mutate();
    }
    if (!opened) firedFor.current = null;
    // suggest.mutate is stable enough; intentionally keyed on open+taxon only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, usageId]);

  const acceptSynonym = useMutation({
    mutationFn: async (s: AiSynonymCard) => {
      const created = await createUsage(pid, {
        scientificName: s.scientificName,
        authorship: s.authorship ?? undefined,
        rank: usageRank ?? undefined,
        status: 'SYNONYM',
      });
      await linkSynonym(pid, created.id, usageId);
    },
    onSuccess: (_r, s) => {
      notifications.show({ message: `Added synonym ${s.scientificName}` });
      setDone((prev) => new Set(prev).add(synKey(s)));
      queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Failed to add synonym') }),
  });

  const data = suggest.data;
  const synonyms = (data?.synonyms ?? []).filter((s) => !done.has(synKey(s)));

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      size="lg"
      title={
        <Group gap="xs">
          <IconSparkles size={18} />
          <Text fw={600}>AI suggestions{usageName ? ` — ${usageName}` : ''}</Text>
        </Group>
      }
    >
      {suggest.isPending && (
        <Group gap="sm" py="md">
          <Loader size="sm" />
          <Text c="dimmed">Gathering suggestions… this can take a few seconds.</Text>
        </Group>
      )}

      {suggest.isError && (
        <Alert color="red" variant="light">
          {messageFor(suggest.error, 'Could not gather suggestions')}
        </Alert>
      )}

      {data && (
        <Stack gap="md">
          <Text size="xs" c="dimmed">
            {data.provider} · {data.model}. Suggestions are AI-generated — review before accepting.
          </Text>

          <Box>
            <Title order={6} mb="xs">
              Synonyms
            </Title>
            {synonyms.length === 0 ? (
              <Text size="sm" c="dimmed">
                No (further) synonym suggestions.
              </Text>
            ) : (
              <Stack gap="xs">
                {synonyms.map((s) => (
                  <Group key={synKey(s)} justify="space-between" wrap="nowrap">
                    <Group gap="xs" wrap="wrap">
                      <Text size="sm" fs="italic">
                        {s.scientificName}
                      </Text>
                      {s.authorship && (
                        <Text size="xs" c="dimmed">
                          {s.authorship}
                        </Text>
                      )}
                      {s.nomStatus && (
                        <Badge size="sm" variant="outline" color="gray">
                          {s.nomStatus}
                        </Badge>
                      )}
                      {s.reference ? <VerifiedRef reference={s.reference} /> : <Unverified />}
                    </Group>
                    <Button
                      size="xs"
                      variant="light"
                      loading={acceptSynonym.isPending && acceptSynonym.variables === s}
                      onClick={() => acceptSynonym.mutate(s)}
                    >
                      Add synonym
                    </Button>
                  </Group>
                ))}
              </Stack>
            )}
          </Box>

          {data.references.length > 0 && (
            <Box>
              <Divider mb="xs" />
              <Title order={6} mb="xs">
                Key references <Text span c="dimmed" size="xs">(verified)</Text>
              </Title>
              <Stack gap={4}>
                {data.references.map((r) => (
                  <Group key={r.doi} gap="xs">
                    <Badge color="green" variant="light" size="sm" leftSection={<IconCheck size={11} />}>
                      verified
                    </Badge>
                    <Anchor href={`https://doi.org/${r.doi}`} target="_blank" size="sm">
                      {r.citation ?? r.doi}
                    </Anchor>
                  </Group>
                ))}
              </Stack>
            </Box>
          )}

          <AiFactList title="Vernacular names" items={data.vernacularNames.map((v) => v.language ? `${v.name} (${v.language})` : v.name)} />
          <AiFactList title="Distribution" items={data.distributions.map((d) => d.area)} />
          <AiFactList title="Description" items={data.descriptions} />
          <AiFactList title="Etymology" items={data.etymology ? [data.etymology] : []} />
        </Stack>
      )}
    </Modal>
  );
}

// Read-only list of AI-suggested facts (one-click accept for these categories is a follow-up); each
// is clearly marked as needing review.
function AiFactList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <Box>
      <Divider mb="xs" />
      <Group gap="xs" mb="xs">
        <Title order={6}>{title}</Title>
        <Unverified />
      </Group>
      <Stack gap={2}>
        {items.map((it, i) => (
          <Text key={i} size="sm">
            {it}
          </Text>
        ))}
      </Stack>
    </Box>
  );
}
