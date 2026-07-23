import { Anchor, Badge, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getDoiCandidates } from '../api/references';

export interface DoiCandidatesModalProps {
  pid: number;
  referenceId: number;
  opened: boolean;
  onClose: () => void;
  onPick: (doi: string) => void;
}

// DOI consolidation: search Crossref for candidate DOIs matching an existing reference's structured
// fields, and let the user apply one (which fills the form's DOI field; the user still saves). The
// inverse of "Import DOI" (which resolves a known DOI into fields). Higher Crossref score = closer
// match; the user always confirms since bibliographic matches aren't guaranteed exact.
export default function DoiCandidatesModal({
  pid,
  referenceId,
  opened,
  onClose,
  onPick,
}: DoiCandidatesModalProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['doiCandidates', pid, referenceId],
    queryFn: () => getDoiCandidates(pid, referenceId),
    enabled: opened,
  });
  const candidates = data ?? [];

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Find DOI">
      <Stack gap="sm">
        {isLoading ? (
          <Text c="dimmed">Searching Crossref…</Text>
        ) : isError ? (
          <Text c="red" size="sm">
            Crossref search failed. Try again later.
          </Text>
        ) : candidates.length === 0 ? (
          <Text c="dimmed" size="sm">
            No candidate DOIs found for this reference.
          </Text>
        ) : (
          candidates.map((c) => (
            <Group key={c.doi} justify="space-between" wrap="nowrap" align="flex-start">
              <Stack gap={2} style={{ minWidth: 0 }}>
                <Group gap="xs" wrap="nowrap">
                  <Text size="sm" fw={500} truncate>
                    {c.title ?? '(untitled)'}
                  </Text>
                  {c.score != null && (
                    <Badge size="sm" variant="light" color="gray">
                      {Math.round(c.score)}
                    </Badge>
                  )}
                </Group>
                <Text size="xs" c="dimmed">
                  {[c.author, c.year, c.containerTitle].filter(Boolean).join(' · ')}
                </Text>
                <Anchor
                  href={`https://doi.org/${c.doi}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  size="xs"
                >
                  {c.doi}
                </Anchor>
              </Stack>
              <Button
                size="compact-sm"
                variant="light"
                onClick={() => {
                  onPick(c.doi);
                  onClose();
                }}
              >
                Use
              </Button>
            </Group>
          ))
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Close
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
