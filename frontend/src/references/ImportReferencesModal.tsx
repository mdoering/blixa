import { Button, FileInput, Group, Modal, Select, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { importBibtex, importCslJson, importRisReferences } from '../api/references';
import type { Reference } from '../api/types';

export interface ImportReferencesModalProps {
  pid: number;
  opened: boolean;
  onClose: () => void;
}

type FormatKey = 'bibtex' | 'ris' | 'csljson';

// One import dialog for every paste/upload reference format (BibTeX, RIS, CSL-JSON) -- the format
// Select drives the field labels, file accept, example content, and which endpoint the paste is
// posted to. DOI import stays its own separate flow (ImportDoiModal), since it resolves a single id
// rather than parsing a pasted blob.
const FORMATS: Record<FormatKey, {
  label: string;
  accept: string;
  placeholder: string;
  importFn: (pid: number, text: string) => Promise<Reference[]>;
}> = {
  bibtex: {
    label: 'BibTeX',
    accept: '.bib,.bibtex',
    placeholder: '@article{key, author = {…}, title = {…}, year = {…} }',
    importFn: importBibtex,
  },
  ris: {
    label: 'RIS',
    accept: '.ris',
    placeholder: 'TY  - JOUR\nAU  - Doe, Jane\nTI  - …\nER  - ',
    importFn: importRisReferences,
  },
  csljson: {
    label: 'CSL-JSON',
    accept: '.json,application/json',
    placeholder: '[{"type":"article-journal","title":"…","author":[{"family":"Doe","given":"J."}]}]',
    importFn: importCslJson,
  },
};

export default function ImportReferencesModal({ pid, opened, onClose }: ImportReferencesModalProps) {
  const queryClient = useQueryClient();
  const [format, setFormat] = useState<FormatKey>('bibtex');
  const [file, setFile] = useState<File | null>(null);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const fmt = FORMATS[format];

  useEffect(() => {
    if (opened) {
      setFormat('bibtex');
      setFile(null);
      setText('');
      setError(null);
    }
  }, [opened]);

  // Switching format clears the (now format-specific) pasted/uploaded content so a BibTeX blob isn't
  // left in the box when the user picks RIS.
  const changeFormat = (value: string | null) => {
    if (value) setFormat(value as FormatKey);
    setFile(null);
    setText('');
    setError(null);
  };

  const handleFile = async (f: File | null) => {
    setFile(f);
    setError(null);
    if (f) setText(await f.text());
  };

  const mutation = useMutation({
    mutationFn: () => fmt.importFn(pid, text),
    onSuccess: async (refs) => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: `Imported ${refs.length} reference${refs.length === 1 ? '' : 's'}` });
      onClose();
    },
    onError: (e) => setError(messageFor(e, `Could not import ${fmt.label}`)),
  });

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Import references">
      <Stack>
        <Select
          label="Format"
          data={Object.entries(FORMATS).map(([value, f]) => ({ value, label: f.label }))}
          value={format}
          onChange={changeFormat}
          allowDeselect={false}
        />
        <FileInput
          label={`${fmt.label} file`}
          placeholder={`Upload a ${fmt.label} file`}
          accept={fmt.accept}
          value={file}
          onChange={handleFile}
          clearable
        />
        <Textarea
          label={fmt.label}
          placeholder={fmt.placeholder}
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
