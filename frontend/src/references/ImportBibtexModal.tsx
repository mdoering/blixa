import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { importBibtex } from '../api/references';

export interface ImportBibtexModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

// Paste a .bib blob → the server parses + creates every entry → refresh the table.
export default function ImportBibtexModal({ pid, opened, onClose }: ImportBibtexModalProps) {
  const queryClient = useQueryClient();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setText('');
      setError(null);
    }
  }, [opened]);

  const mutation = useMutation({
    mutationFn: () => importBibtex(pid, text),
    onSuccess: async (refs) => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: `Imported ${refs.length} reference${refs.length === 1 ? '' : 's'}` });
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not import BibTeX')),
  });

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Import BibTeX">
      <Stack>
        <Textarea
          label="BibTeX"
          placeholder="@article{key, author = {…}, title = {…}, year = {…} }"
          autosize
          minRows={8}
          maxRows={16}
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
            setError(null);
          }}
        />
        {error && (
          <Text c="red" size="sm">
            {error}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => mutation.mutate()} loading={mutation.isPending} disabled={!text.trim()}>
            Import
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
