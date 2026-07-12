import { Button, FileInput, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
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

// Paste a .bib blob → the server parses + creates every entry → refresh the table. Mirrors
// ImportRisModal, plus a .bib/.bibtex FileInput that just reads the file's text into the same
// Textarea/state.
export default function ImportBibtexModal({ pid, opened, onClose }: ImportBibtexModalProps) {
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
        <FileInput
          label="Or upload a BibTeX file"
          placeholder="Upload a .bib file"
          accept=".bib,.bibtex"
          value={file}
          onChange={handleFile}
          clearable
        />
        <Textarea
          label="BibTeX"
          placeholder="@article{key, author = {…}, title = {…}, year = {…} }"
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
