import { Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { resolveDoi } from '../api/references';
import type { CreateRefPayload } from '../api/types';

export interface ImportDoiModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
  // The resolved preview is handed back so the caller can open ReferenceForm pre-filled for review.
  onResolved: (payload: CreateRefPayload) => void;
}

// Enter a DOI → server resolves it via Crossref (falling back to DataCite) → hand the preview to
// the caller (which opens the reference form pre-filled). Nothing is saved here.
export default function ImportDoiModal({ pid, opened, onClose, onResolved }: ImportDoiModalProps) {
  const [doi, setDoi] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setDoi('');
      setError(null);
    }
  }, [opened]);

  const mutation = useMutation({
    mutationFn: () => resolveDoi(pid, doi.trim()),
    onSuccess: (payload) => {
      onResolved(payload);
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not resolve that DOI')),
  });

  return (
    <Modal opened={opened} onClose={onClose} title="Import from DOI">
      <Stack>
        <TextInput
          label="DOI"
          placeholder="10.xxxx/xxxx, doi:10.xxxx/xxxx, or https://doi.org/…"
          data-autofocus
          value={doi}
          onChange={(e) => {
            setDoi(e.currentTarget.value);
            setError(null);
          }}
        />
        <Text size="xs" c="dimmed">
          Crossref, with DataCite fallback.
        </Text>
        {error && (
          <Text c="red" size="sm">
            {error}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={!doi.trim()}>
            Fetch
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
