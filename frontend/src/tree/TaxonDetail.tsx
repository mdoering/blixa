import {
  Box,
  Button,
  Divider,
  Group,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { getUsage, updateUsage } from '../api/usages';
import type { NameUsage, UpdateUsagePayload } from '../api/types';
import NameRelationsTab from '../child/NameRelationsTab';
import TypeMaterialTab from '../child/TypeMaterialTab';
import {
  DistributionTab,
  EstimateTab,
  MediaTab,
  PropertyTab,
  VernacularTab,
} from '../child/taxonTabs';
import IssueList from './IssueList';
import SynonymList from './SynonymList';

const STATUS_OPTIONS = [
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'SYNONYM', label: 'Synonym' },
  { value: 'MISAPPLIED', label: 'Misapplied' },
  { value: 'UNASSESSED', label: 'Unassessed' },
];

interface EditableFields {
  scientificName: string;
  authorship: string;
  rank: string;
  status: string;
  publishedInYear: number | '';
  publishedInPage: string;
  publishedInPageLink: string;
  nomStatus: string;
  etymology: string;
  link: string;
}

function toFormValues(u: NameUsage): EditableFields {
  return {
    scientificName: u.scientificName ?? '',
    authorship: u.authorship ?? '',
    rank: u.rank ?? '',
    status: u.status ?? 'ACCEPTED',
    publishedInYear: u.publishedInYear ?? '',
    publishedInPage: u.publishedInPage ?? '',
    publishedInPageLink: u.publishedInPageLink ?? '',
    nomStatus: u.nomStatus ?? '',
    etymology: u.etymology ?? '',
    link: u.link ?? '',
  };
}

export interface TaxonDetailProps {
  pid: number;
  usageId: number;
}

// Views + edits one name usage's fields, plus its synonyms/accepted targets and validation
// issues. Save is optimistic-locked on the loaded `version`: a 409 (someone else saved first)
// reloads the usage and reseeds the form instead of clobbering their change.
export default function TaxonDetail({ pid, usageId }: TaxonDetailProps) {
  const queryClient = useQueryClient();

  const form = useForm<EditableFields>({
    initialValues: {
      scientificName: '',
      authorship: '',
      rank: '',
      status: 'ACCEPTED',
      publishedInYear: '',
      publishedInPage: '',
      publishedInPageLink: '',
      nomStatus: '',
      etymology: '',
      link: '',
    },
    validate: {
      scientificName: (v) => (v ? null : 'Required'),
      rank: (v) => (v ? null : 'Required'),
      status: (v) => (v ? null : 'Required'),
    },
  });

  // Same source as ProjectMetadataPage's canEdit: the project's role for the current user.
  const { data: project } = useQuery({
    queryKey: ['project', pid],
    queryFn: () => getProject(pid),
  });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  const usageQuery = useQuery({
    queryKey: ['usage', pid, usageId],
    queryFn: () => getUsage(pid, usageId),
  });
  const usage = usageQuery.data;

  useEffect(() => {
    if (usage) {
      const values = toFormValues(usage);
      form.setValues(values);
      form.resetDirty(values);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usage]);

  const mutation = useMutation({
    mutationFn: (values: EditableFields) => {
      if (!usage) throw new Error('usage not loaded yet');
      // Full replace: carry over the loaded usage's values for fields this form doesn't expose,
      // so saving doesn't null them out (see UpdateUsagePayload).
      const payload: UpdateUsagePayload = {
        scientificName: values.scientificName,
        authorship: values.authorship || undefined,
        rank: values.rank,
        status: values.status,
        parentId: usage.parentId ?? undefined,
        namePhrase: usage.namePhrase ?? undefined,
        nomStatus: values.nomStatus || undefined,
        publishedInReferenceId: usage.publishedInReferenceId ?? undefined,
        publishedInYear: values.publishedInYear === '' ? undefined : values.publishedInYear,
        publishedInPage: values.publishedInPage || undefined,
        publishedInPageLink: values.publishedInPageLink || undefined,
        gender: usage.gender ?? undefined,
        extinct: usage.extinct ?? undefined,
        environment: usage.environment ?? undefined,
        temporalRangeStart: usage.temporalRangeStart ?? undefined,
        temporalRangeEnd: usage.temporalRangeEnd ?? undefined,
        etymology: values.etymology || undefined,
        link: values.link || undefined,
        remarks: usage.remarks ?? undefined,
        version: usage.version,
      };
      return updateUsage(pid, usageId, payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      // The edited scientificName/authorship/rank/status can all show up in the tree rows.
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treePath', pid] });
      notifications.show({ message: 'Saved' });
    },
    onError: async (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          color: 'orange',
          message: 'Changed by someone else — reloading',
        });
        // Reseed directly from the refetch result rather than relying on the `usage`-keyed
        // effect below: TanStack Query's structural sharing can return the SAME `data` reference
        // when the refetched usage is unchanged from what's already cached, which would mean the
        // effect never re-fires and the user's stale edit stays showing in the form.
        const result = await usageQuery.refetch();
        if (result.data) {
          const values = toFormValues(result.data);
          form.setValues(values);
          form.resetDirty(values);
        }
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Save failed') });
    },
  });

  if (usageQuery.isLoading) return <Text c="dimmed">Loading…</Text>;
  if (usageQuery.isError || !usage) return <Text c="red">Could not load this taxon</Text>;

  // The taxon-level entities only apply to accepted taxa (the backend guards create + drops them on
  // demote), so their tabs only show when accepted.
  const isAccepted = usage.status === 'ACCEPTED';

  return (
    <Box>
      <Tabs defaultValue="details" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="details">Details</Tabs.Tab>
          <Tabs.Tab value="names">Names</Tabs.Tab>
          <Tabs.Tab value="types">Types</Tabs.Tab>
          {isAccepted && <Tabs.Tab value="vernaculars">Vernaculars</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="distribution">Distribution</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="media">Media</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="estimates">Estimates</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="properties">Properties</Tabs.Tab>}
          <Tabs.Tab value="synonyms">Synonyms</Tabs.Tab>
          <Tabs.Tab value="issues">Issues</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="details" pt="md">
          <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
            <fieldset disabled={!canEdit} style={{ border: 'none', padding: 0, margin: 0 }}>
              <Stack gap="md">
                <SimpleGrid cols={2}>
                  <TextInput label="Scientific name" {...form.getInputProps('scientificName')} />
                  <TextInput label="Authorship" {...form.getInputProps('authorship')} />
                </SimpleGrid>
                <SimpleGrid cols={2}>
                  <TextInput label="Rank" {...form.getInputProps('rank')} />
                  <Select label="Status" data={STATUS_OPTIONS} {...form.getInputProps('status')} />
                </SimpleGrid>
                <SimpleGrid cols={3}>
                  <NumberInput label="Published in year" {...form.getInputProps('publishedInYear')} />
                  <TextInput label="Published in page" {...form.getInputProps('publishedInPage')} />
                  <TextInput
                    label="Published in page link"
                    {...form.getInputProps('publishedInPageLink')}
                  />
                </SimpleGrid>
                <SimpleGrid cols={2}>
                  <TextInput label="Nomenclatural status" {...form.getInputProps('nomStatus')} />
                  <TextInput label="Link" {...form.getInputProps('link')} />
                </SimpleGrid>
                <Textarea label="Etymology" rows={2} {...form.getInputProps('etymology')} />
                <Group>
                  <Button type="submit" loading={mutation.isPending} disabled={!canEdit}>
                    Save
                  </Button>
                </Group>
              </Stack>
            </fieldset>
          </form>

          <Divider my="md" label="Parsed name" labelPosition="left" />
          <SimpleGrid cols={2} spacing="xs">
            <Text size="sm" c="dimmed">Name type: {usage.nameType ?? '—'}</Text>
            <Text size="sm" c="dimmed">Parse state: {usage.parseState ?? '—'}</Text>
            <Text size="sm" c="dimmed">Uninomial: {usage.uninomial ?? '—'}</Text>
            <Text size="sm" c="dimmed">Genus: {usage.genus ?? '—'}</Text>
            <Text size="sm" c="dimmed">Specific epithet: {usage.specificEpithet ?? '—'}</Text>
            <Text size="sm" c="dimmed">Infraspecific epithet: {usage.infraspecificEpithet ?? '—'}</Text>
          </SimpleGrid>
        </Tabs.Panel>

        <Tabs.Panel value="names" pt="md">
          <NameRelationsTab pid={pid} usageId={usageId} canEdit={canEdit} />
        </Tabs.Panel>

        <Tabs.Panel value="types" pt="md">
          <TypeMaterialTab pid={pid} usageId={usageId} canEdit={canEdit} />
        </Tabs.Panel>

        {isAccepted && (
          <Tabs.Panel value="vernaculars" pt="md">
            <VernacularTab pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}
        {isAccepted && (
          <Tabs.Panel value="distribution" pt="md">
            <DistributionTab pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}
        {isAccepted && (
          <Tabs.Panel value="media" pt="md">
            <MediaTab pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}
        {isAccepted && (
          <Tabs.Panel value="estimates" pt="md">
            <EstimateTab pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}
        {isAccepted && (
          <Tabs.Panel value="properties" pt="md">
            <PropertyTab pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}

        <Tabs.Panel value="synonyms" pt="md">
          <SynonymList pid={pid} usageId={usageId} status={usage.status} canEdit={canEdit} />
        </Tabs.Panel>

        <Tabs.Panel value="issues" pt="md">
          <IssueList pid={pid} entityId={usageId} />
        </Tabs.Panel>
      </Tabs>
    </Box>
  );
}
