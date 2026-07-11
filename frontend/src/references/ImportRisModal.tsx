import { Button, FileInput, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { importRisReferences } from '../api/references';

export interface ImportRisModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

// Import a RIS export (Zotero/EndNote/Mendeley) → the server parses + creates every record →
// refresh the table. Mirrors ImportBibtexModal, plus a .ris FileInput (RIS is normally exported as
// a file rather than pasted) that just reads the file's text into the same Textarea/state.
export default function ImportRisModal({ pid, opened, onClose }: ImportRisModalProps) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      setFile(null);
      setText('');
      setError(null);
    }
  }, [opened]);

  const handleFile = async (f: File | null) => {
    setFile(f);
    setError(null);
    if (f) {
      setText(await f.text());
    }
  };

  const mutation = useMutation({
    mutationFn: () => importRisReferences(pid, text),
    onSuccess: async (refs) => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: `Imported ${refs.length} reference${refs.length === 1 ? '' : 's'}` });
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not import RIS')),
  });

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Import RIS">
      <Stack>
        <FileInput
          label="RIS file"
          placeholder="Select a .ris file"
          accept=".ris"
          value={file}
          onChange={handleFile}
          clearable
        />
        <Textarea
          label="RIS"
          placeholder="TY  - JOUR&#10;AU  - Doe, Jane&#10;TI  - …&#10;ER  - "
          autosize
          minRows={8}
          maxRows={16}
          value={text}
          onChange={(e) => {
            setFile(null);
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
