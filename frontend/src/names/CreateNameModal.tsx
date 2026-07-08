import { Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { createUsage, linkSynonym } from '../api/usages';
import type { CreateUsagePayload } from '../api/types';

export type CreateNameMode = 'root' | 'child' | 'synonym';

// The minimal shape needed to display context ("Child of X") and to link a synonym -- both the
// tree's TreeNode and the full NameUsage satisfy this structurally.
export interface CreateNameAnchor {
  id: number;
  scientificName: string | null;
}

export interface CreateNameModalProps {
  pid: number;
  mode: CreateNameMode;
  anchor?: CreateNameAnchor | null;
  opened: boolean;
  onClose: () => void;
  // Called with the id of the newly-created usage, after the create (and, for synonyms, the
  // synonym-of link) has succeeded -- callers use this to select the new node.
  onCreated: (newId: number) => void;
}

// A reasonable common subset of ranks (lower-case, per how rank is actually stored -- see
// NameUsageService.normalizeRankFilter / ParsedNameMapping.applyTo, which re-renders whatever
// rank the client sends into its lower-case parsed form).
const RANK_OPTIONS = [
  'kingdom',
  'phylum',
  'class',
  'order',
  'family',
  'genus',
  'species',
  'subspecies',
  'variety',
  'form',
].map((r) => ({ value: r, label: r }));

interface FormValues {
  scientificName: string;
  authorship: string;
  rank: string;
}

const EMPTY_VALUES: FormValues = { scientificName: '', authorship: '', rank: '' };

function modalTitle(mode: CreateNameMode, anchor?: CreateNameAnchor | null) {
  if (mode === 'child') {
    return (
      <Text fw={600}>
        Child of <Text span fs="italic" inherit>{anchor?.scientificName ?? '—'}</Text>
      </Text>
    );
  }
  if (mode === 'synonym') {
    return (
      <Text fw={600}>
        Synonym of <Text span fs="italic" inherit>{anchor?.scientificName ?? '—'}</Text>
      </Text>
    );
  }
  return <Text fw={600}>New root name</Text>;
}

// Shared create-name flow for root/child/synonym usages, used from both the Tree's per-row
// action menu / toolbar button and (later) the Names search table. Child usages are created
// ACCEPTED with parentId=anchor; synonyms are created SYNONYM with no parent and then linked to
// the anchor via a separate synonym-of PUT (the backend has no create-and-link-in-one-call
// endpoint); roots are created ACCEPTED with no parent.
export default function CreateNameModal({
  pid,
  mode,
  anchor,
  opened,
  onClose,
  onCreated,
}: CreateNameModalProps) {
  const queryClient = useQueryClient();

  const form = useForm<FormValues>({
    initialValues: EMPTY_VALUES,
    validate: {
      scientificName: (v) => (v ? null : 'Required'),
      rank: (v) => (v ? null : 'Required'),
    },
  });

  // Reset the form fields each time the modal is (re-)opened, e.g. opening it again for a
  // different anchor/mode shouldn't carry over the previous attempt's input.
  useEffect(() => {
    if (opened) {
      form.setValues(EMPTY_VALUES);
      form.resetDirty(EMPTY_VALUES);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, mode, anchor?.id]);

  const mutation = useMutation({
    mutationFn: async (values: FormValues) => {
      const payload: CreateUsagePayload = {
        scientificName: values.scientificName,
        authorship: values.authorship || undefined,
        rank: values.rank,
        status: mode === 'synonym' ? 'SYNONYM' : 'ACCEPTED',
        parentId: mode === 'child' && anchor ? anchor.id : undefined,
      };
      const created = await createUsage(pid, payload);
      if (mode === 'synonym' && anchor) {
        await linkSynonym(pid, created.id, anchor.id);
      }
      return created;
    },
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
      notifications.show({ message: 'Name created' });
      onCreated(created.id);
      onClose();
    },
    onError: (e) => {
      notifications.show({ color: 'red', message: messageFor(e, 'Could not create the name') });
    },
  });

  return (
    <Modal opened={opened} onClose={onClose} title={modalTitle(mode, anchor)}>
      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
        <Stack gap="md">
          <TextInput
            label="Scientific name"
            data-autofocus
            {...form.getInputProps('scientificName')}
          />
          <TextInput label="Authorship" {...form.getInputProps('authorship')} />
          <Select label="Rank" data={RANK_OPTIONS} {...form.getInputProps('rank')} />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={mutation.isPending}>
              Create
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
