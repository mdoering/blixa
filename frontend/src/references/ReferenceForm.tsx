import { Button, Group, Modal, SimpleGrid, Stack, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { createReference, updateReference } from '../api/references';
import type { CreateRefPayload, Reference } from '../api/types';

export interface ReferenceFormProps {
  pid: number;
  reference: Reference | null; // edit target, or null for create
  initial?: CreateRefPayload | null; // prefill for a new reference (e.g. DOI resolution)
  opened: boolean;
  onClose: () => void;
}

const FIELDS = [
  'citation',
  'author',
  'editor',
  'title',
  'containerTitle',
  'type',
  'issued',
  'volume',
  'issue',
  'page',
  'publisher',
  'doi',
  'isbn',
  'issn',
  'link',
  'accessed',
  'remarks',
] as const;
type FormValues = Record<(typeof FIELDS)[number], string>;
const EMPTY = Object.fromEntries(FIELDS.map((f) => [f, ''])) as FormValues;

function toValues(src: Reference | CreateRefPayload): FormValues {
  const rec = src as Record<string, unknown>;
  const v = { ...EMPTY };
  for (const f of FIELDS) {
    const raw = rec[f];
    v[f] = raw == null ? '' : String(raw);
  }
  return v;
}
function toPayload(v: FormValues): CreateRefPayload {
  const out: Record<string, string> = {};
  for (const f of FIELDS) {
    const val = v[f].trim();
    if (val) out[f] = val;
  }
  return out as CreateRefPayload;
}

// Create/edit a reference. Also the review target of DOI resolution: `initial` pre-fills a new one.
export default function ReferenceForm({ pid, reference, initial, opened, onClose }: ReferenceFormProps) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ initialValues: EMPTY });

  useEffect(() => {
    if (opened) {
      form.setValues(reference ? toValues(reference) : initial ? toValues(initial) : EMPTY);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, reference?.id, initial]);

  const mutation = useMutation({
    mutationFn: (values: FormValues) => {
      const payload = toPayload(values);
      return reference
        ? updateReference(pid, reference.id, { ...payload, version: reference.version })
        : createReference(pid, payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: reference ? 'Saved' : 'Reference created' });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({ color: 'orange', message: 'Changed by someone else — reopen to retry' });
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Could not save the reference') });
    },
  });

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      size="lg"
      title={reference ? 'Edit reference' : 'New reference'}
    >
      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
        <Stack gap="sm">
          <TextInput label="Citation" {...form.getInputProps('citation')} />
          <SimpleGrid cols={2}>
            <TextInput label="Author" {...form.getInputProps('author')} />
            <TextInput label="Editor" {...form.getInputProps('editor')} />
          </SimpleGrid>
          <TextInput label="Title" {...form.getInputProps('title')} />
          <SimpleGrid cols={2}>
            <TextInput label="Container title" {...form.getInputProps('containerTitle')} />
            <TextInput label="Type" {...form.getInputProps('type')} />
          </SimpleGrid>
          <SimpleGrid cols={4}>
            <TextInput label="Year" {...form.getInputProps('issued')} />
            <TextInput label="Volume" {...form.getInputProps('volume')} />
            <TextInput label="Issue" {...form.getInputProps('issue')} />
            <TextInput label="Page" {...form.getInputProps('page')} />
          </SimpleGrid>
          <SimpleGrid cols={2}>
            <TextInput label="Publisher" {...form.getInputProps('publisher')} />
            <TextInput label="DOI" {...form.getInputProps('doi')} />
          </SimpleGrid>
          <SimpleGrid cols={3}>
            <TextInput label="ISBN" {...form.getInputProps('isbn')} />
            <TextInput label="ISSN" {...form.getInputProps('issn')} />
            <TextInput label="Link" {...form.getInputProps('link')} />
          </SimpleGrid>
          <TextInput
            label="Accessed"
            placeholder="YYYY-MM-DD"
            {...form.getInputProps('accessed')}
          />
          <Textarea label="Remarks" rows={2} {...form.getInputProps('remarks')} />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={mutation.isPending}>
              {reference ? 'Save' : 'Create'}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
