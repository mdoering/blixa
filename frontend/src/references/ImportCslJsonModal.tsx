import { Button, FileInput, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { importCslJson } from '../api/references';

export interface ImportCslJsonModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

// Import CSL-JSON (the citeproc format Zotero's "Export → CSL JSON" and pandoc emit) → the server
// parses + creates every item → refresh the table. Mirrors ImportRisModal (paste or pick a .json
// file). Accepts an array of items or a single item object.
export default function ImportCslJsonModal({ pid, opened, onClose }: ImportCslJsonModalProps) {
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
    mutationFn: () => importCslJson(pid, text),
    onSuccess: async (refs) => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: `Imported ${refs.length} reference${refs.length === 1 ? '' : 's'}` });
      onClose();
    },
    onError: (e) => setError(messageFor(e, 'Could not import CSL-JSON')),
  });

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Import CSL-JSON">
      <Stack>
        <FileInput
          label="CSL-JSON file"
          placeholder="Select a .json file"
          accept=".json,application/json"
          value={file}
          onChange={handleFile}
          clearable
        />
        <Textarea
          label="CSL-JSON"
          placeholder={'[{"type":"article-journal","title":"…","author":[{"family":"Doe","given":"J."}]}]'}
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
