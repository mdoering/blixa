import { Anchor, Button, Checkbox, FileInput, Group, Modal, Select, SimpleGrid, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getVocab } from '../api/coldp';
import { attachReferencePdf, createReference, removeReferencePdf, updateReference } from '../api/references';
import { clearReferenceBhlItem, getBhlConfig } from '../api/bhl';
import type { CreateRefPayload, CslName, Reference } from '../api/types';
import CslNameEditor from './CslNameEditor';
import BhlLinkModal from './BhlLinkModal';

export interface ReferenceFormProps {
  pid: number;
  reference: Reference | null; // edit target, or null for create
  initial?: CreateRefPayload | null; // prefill for a new reference (e.g. DOI resolution)
  opened: boolean;
  onClose: () => void;
}

// Scalar (string) fields only -- author/editor are CslName[] and citationManual is a boolean, both
// handled separately below (see FormValues).
const FIELDS = [
  'citation',
  'title',
  'containerTitle',
  'containerTitleShort',
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
type FormValues = Record<(typeof FIELDS)[number], string> & {
  author: CslName[];
  editor: CslName[];
  citationManual: boolean;
};
const EMPTY: FormValues = {
  ...(Object.fromEntries(FIELDS.map((f) => [f, ''])) as Record<(typeof FIELDS)[number], string>),
  author: [],
  editor: [],
  citationManual: false,
};

function toValues(src: Reference | CreateRefPayload): FormValues {
  const rec = src as Record<string, unknown>;
  const v: FormValues = { ...EMPTY };
  for (const f of FIELDS) {
    const raw = rec[f];
    v[f] = raw == null ? '' : String(raw);
  }
  v.author = (rec.author as CslName[] | null | undefined) ?? [];
  v.editor = (rec.editor as CslName[] | null | undefined) ?? [];
  v.citationManual = Boolean(rec.citationManual);
  return v;
}

// A row a user clicked "Add" on but never filled in -- dropped on submit rather than sent as a
// meaningless `{}` entry.
function isBlankName(n: CslName): boolean {
  return (
    !n.family?.trim() &&
    !n.given?.trim() &&
    !n.literal?.trim() &&
    !n['dropping-particle']?.trim() &&
    !n['non-dropping-particle']?.trim() &&
    !n.suffix?.trim()
  );
}

function toPayload(v: FormValues): CreateRefPayload {
  const out: Record<string, unknown> = {};
  for (const f of FIELDS) {
    const val = v[f].trim();
    if (val) out[f] = val;
  }
  out.author = v.author.filter((n) => !isBlankName(n));
  out.editor = v.editor.filter((n) => !isBlankName(n));
  out.citationManual = v.citationManual;
  return out as CreateRefPayload;
}

// Create/edit a reference. Also the review target of DOI resolution: `initial` pre-fills a new one.
export default function ReferenceForm({ pid, reference, initial, opened, onClose }: ReferenceFormProps) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({ initialValues: EMPTY });

  // The reference `type` dropdown's data (CSL-JSON wire ids, e.g. "article-journal") -- same vocab
  // endpoint TaxonDetail's Rank/Nomenclatural-status selects use, so it shares that query's cache.
  const { data: vocab } = useQuery({ queryKey: ['vocab'], queryFn: getVocab, staleTime: Infinity });
  const typeInputProps = form.getInputProps('type');
  // Never blank on load: a stored `type` that predates this vocab (or was written by a source that
  // used a slightly different CSLType spelling) still shows up as a selectable option rather than
  // silently vanishing from the dropdown -- mirrors TaxonDetail's rankData/nomStatusData.
  const cslTypeData = useMemo(
    () => Array.from(new Set([...(vocab?.cslTypes ?? []), form.values.type].filter(Boolean))),
    [vocab?.cslTypes, form.values.type],
  );

  // The PDF control's own state, separate from the form's text fields: pdfUrl mirrors the loaded
  // reference but is updated locally on attach/remove success (rather than only via a parent
  // refetch) so the View/Remove <-> FileInput swap happens immediately.
  const [pdfUrl, setPdfUrl] = useState<string | null>(reference?.pdfUrl ?? null);
  const [pdfFile, setPdfFile] = useState<File | null>(null);
  const [pdfError, setPdfError] = useState<string | null>(null);
  // Like pdfUrl: mirrors reference.bhlItemId but updated locally on link/unlink for an immediate swap.
  const [bhlItemId, setBhlItemId] = useState<number | null>(reference?.bhlItemId ?? null);
  const [bhlOpen, setBhlOpen] = useState(false);
  const { data: bhlConfig } = useQuery({
    queryKey: ['bhlConfig', pid],
    queryFn: () => getBhlConfig(pid),
    staleTime: 5 * 60 * 1000,
  });
  const clearBhl = useMutation({
    mutationFn: () => clearReferenceBhlItem(pid, reference!.id),
    onSuccess: () => {
      setBhlItemId(null);
      queryClient.invalidateQueries({ queryKey: ['references', pid] });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Failed to unlink') }),
  });

  useEffect(() => {
    if (opened) {
      form.setValues(reference ? toValues(reference) : initial ? toValues(initial) : EMPTY);
      setPdfUrl(reference?.pdfUrl ?? null);
      setPdfFile(null);
      setPdfError(null);
      setBhlItemId(reference?.bhlItemId ?? null);
      setBhlOpen(false);
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
      size="xl"
      title={reference ? 'Edit reference' : 'New reference'}
    >
      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
        <Stack gap="sm">
          <TextInput
            label="Citation"
            readOnly={!form.values.citationManual}
            description={
              !form.values.citationManual
                ? "Generated from the fields above in the project's citation style."
                : undefined
            }
            {...form.getInputProps('citation')}
          />
          <Checkbox
            label="Enter citation manually"
            {...form.getInputProps('citationManual', { type: 'checkbox' })}
          />
          <CslNameEditor
            label="Author"
            value={form.values.author}
            onChange={(v) => form.setFieldValue('author', v)}
          />
          <CslNameEditor
            label="Editor"
            value={form.values.editor}
            onChange={(v) => form.setFieldValue('editor', v)}
          />
          <TextInput label="Title" {...form.getInputProps('title')} />
          <SimpleGrid cols={3}>
            <TextInput label="Container title" {...form.getInputProps('containerTitle')} />
            <TextInput label="Container title (short)" {...form.getInputProps('containerTitleShort')} />
            <Select
              label="Type"
              searchable
              clearable
              data={cslTypeData}
              {...typeInputProps}
              onChange={(v) => typeInputProps.onChange(v ?? '')}
            />
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
          {/* BHL item link: only for a saved reference, and only when BHL is configured (or already
              linked). Piece 1 -- names citing this reference then find the exact page within it. */}
          {reference && (bhlItemId != null || bhlConfig?.available) && (
            <div>
              <Text size="sm" fw={500} mb={4}>
                BHL item
              </Text>
              {bhlItemId != null ? (
                <Group gap="xs">
                  <Anchor
                    href={`https://www.biodiversitylibrary.org/item/${bhlItemId}`}
                    target="_blank"
                    rel="noreferrer"
                  >
                    BHL item {bhlItemId}
                  </Anchor>
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    onClick={() => clearBhl.mutate()}
                    loading={clearBhl.isPending}
                  >
                    Unlink
                  </Button>
                </Group>
              ) : (
                <Button size="xs" variant="light" onClick={() => setBhlOpen(true)}>
                  Find on BHL…
                </Button>
              )}
            </div>
          )}
          {reference && (
            <BhlLinkModal
              pid={pid}
              refId={reference.id}
              prefill={reference.citation ?? reference.title ?? ''}
              opened={bhlOpen}
              onClose={() => setBhlOpen(false)}
              onLinked={(itemId) => setBhlItemId(itemId)}
            />
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
