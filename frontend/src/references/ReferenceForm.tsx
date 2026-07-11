import { Anchor, Button, FileInput, Group, Modal, SimpleGrid, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { attachReferencePdf, createReference, removeReferencePdf, updateReference } from '../api/references';
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

  // The PDF control's own state, separate from the form's text fields: pdfUrl mirrors the loaded
  // reference but is updated locally on attach/remove success (rather than only via a parent
  // refetch) so the View/Remove <-> FileInput swap happens immediately.
  const [pdfUrl, setPdfUrl] = useState<string | null>(reference?.pdfUrl ?? null);
  const [pdfFile, setPdfFile] = useState<File | null>(null);
  const [pdfError, setPdfError] = useState<string | null>(null);

  useEffect(() => {
    if (opened) {
      form.setValues(reference ? toValues(reference) : initial ? toValues(initial) : EMPTY);
      setPdfUrl(reference?.pdfUrl ?? null);
      setPdfFile(null);
      setPdfError(null);
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

  // A PDF only makes sense for an already-persisted reference (it attaches to a reference id) --
  // both mutations below are only reachable while `reference` is set (see the PDF section's guard).
  const attachPdf = useMutation({
    mutationFn: (file: File) => attachReferencePdf(pid, reference!.id, file),
    onSuccess: async (updated) => {
      setPdfUrl(updated.pdfUrl);
      setPdfFile(null);
      setPdfError(null);
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: 'PDF attached' });
    },
    onError: (e) => setPdfError(messageFor(e, 'Could not attach the PDF')),
  });

  const removePdf = useMutation({
    mutationFn: () => removeReferencePdf(pid, reference!.id),
    onSuccess: async (updated) => {
      setPdfUrl(updated.pdfUrl);
      setPdfError(null);
      await queryClient.invalidateQueries({ queryKey: ['references', pid] });
      notifications.show({ message: 'PDF removed' });
    },
    onError: (e) => setPdfError(messageFor(e, 'Could not remove the PDF')),
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
          {/* PDF hosting: only meaningful for an already-saved reference -- a create form (no id
              yet) hides the control entirely rather than showing a disabled one. */}
          {reference && (
            <div>
              <Text size="sm" fw={500} mb={4}>
                PDF
              </Text>
              {pdfUrl ? (
                <Group gap="xs">
                  <Anchor href={pdfUrl} target="_blank" rel="noreferrer">
                    View PDF
                  </Anchor>
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    onClick={() => removePdf.mutate()}
                    loading={removePdf.isPending}
                  >
                    Remove
                  </Button>
                </Group>
              ) : (
                <Group align="flex-end" gap="xs">
                  <FileInput
                    placeholder="Select a PDF"
                    accept="application/pdf"
                    value={pdfFile}
                    onChange={(f) => {
                      setPdfFile(f);
                      setPdfError(null);
                    }}
                    style={{ flex: 1 }}
                    clearable
                  />
                  <Button
                    size="xs"
                    onClick={() => pdfFile && attachPdf.mutate(pdfFile)}
                    disabled={!pdfFile}
                    loading={attachPdf.isPending}
                  >
                    Attach
                  </Button>
                </Group>
              )}
              {pdfError && (
                <Text c="red" size="sm">
                  {pdfError}
                </Text>
              )}
            </div>
          )}
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
