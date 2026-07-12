import {
  Alert,
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
import { IconLock } from '@tabler/icons-react';
import { useEffect, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { getVocab } from '../api/coldp';
import { listLocks } from '../api/locks';
import { getUsage, updateUsage } from '../api/usages';
import type { NameUsage, UpdateUsagePayload } from '../api/types';
import EntitySelect from '../child/EntitySelect';
import NameRelationsTab, { referenceOptions } from '../child/NameRelationsTab';
import { colIdFrom, scopedId, withScopedId } from '../child/map/mapUrls';
import ReferencesTab from '../child/ReferencesTab';
import TypeMaterialTab from '../child/TypeMaterialTab';
import {
  DistributionTab,
  EstimateTab,
  MediaTab,
  PropertyTab,
  VernacularTab,
} from '../child/taxonTabs';
import { useUsageLock } from '../lock/useUsageLock';
import IssueList from './IssueList';
import SynonymList from './SynonymList';

const STATUS_OPTIONS = [
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'SYNONYM', label: 'Synonym' },
  { value: 'MISAPPLIED', label: 'Misapplied' },
  { value: 'UNASSESSED', label: 'Unassessed' },
];

// enum name -> human label for a dropdown option, e.g. REPLACEMENT_NAME -> "replacement name".
function prettyEnum(v: string): string {
  return v.toLowerCase().replace(/_/g, ' ');
}

interface EditableFields {
  scientificName: string;
  authorship: string;
  rank: string;
  status: string;
  publishedInReferenceId: number | '';
  publishedInYear: number | '';
  publishedInPage: string;
  publishedInPageLink: string;
  nomStatus: string;
  etymology: string;
  remarks: string;
  // One entry per project.identifierScopes scope (e.g. "ipni"), keyed by the bare scope -- seeded
  // from usage.alternativeId's matching `<scope>:<id>` entry (see the identifiers-seeding effect
  // below) and folded back into alternativeId on save (see the mutation's alternativeId build).
  // Dynamic per-project, so plain string keys rather than a fixed field per scope.
  identifiers: Record<string, string>;
}

function toFormValues(u: NameUsage): EditableFields {
  return {
    scientificName: u.scientificName ?? '',
    authorship: u.authorship ?? '',
    rank: u.rank ?? '',
    status: u.status ?? 'ACCEPTED',
    publishedInReferenceId: u.publishedInReferenceId ?? '',
    publishedInYear: u.publishedInYear ?? '',
    publishedInPage: u.publishedInPage ?? '',
    publishedInPageLink: u.publishedInPageLink ?? '',
    nomStatus: u.nomStatus ?? '',
    etymology: u.etymology ?? '',
    remarks: u.remarks ?? '',
    // Seeded separately by the identifiers-seeding effect below, once the project's
    // identifierScopes are known -- empty here so a usage-only reseed (e.g. after save, or a 409
    // conflict refetch) doesn't wipe out already-seeded identifier fields.
    identifiers: {},
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
      publishedInReferenceId: '',
      publishedInYear: '',
      publishedInPage: '',
      publishedInPageLink: '',
      nomStatus: '',
      etymology: '',
      remarks: '',
      identifiers: {},
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

  // Shared locks list for this project, polled so a foreign lock's "locked by X" banner (below)
  // shows immediately on open -- before the current user has made any edit of their own (and thus
  // before useUsageLock's own `claim()`-driven `holder` would ever be populated).
  const { data: locks } = useQuery({
    queryKey: ['locks', pid],
    queryFn: () => listLocks(pid),
    refetchInterval: 20_000,
  });
  const foreignLock = locks?.find((l) => l.entityType === 'name_usage' && l.entityId === usageId && !l.heldByMe);
  const { claim } = useUsageLock(pid, usageId, canEdit);
  // Which alternative_id CURIE scopes this project renders a real identifier field for (Project
  // settings page). project.identifierScopes is now a list of {scope, datasetKey} objects (the
  // datasetKey drives CLB matching, unused by this per-scope-field logic) -- reduce to the bare
  // scope strings this form has always worked with. scopesKey is a primitive (not the array
  // itself) so the identifiers-seeding effect below only reruns when the actual scope list
  // changes, not on every render's fresh `?? []`/`.map(...)` array literal.
  const scopes = (project?.identifierScopes ?? []).map((s) => s.scope);
  const scopesKey = scopes.join(' ');

  // Enum vocabularies backing the constrained, searchable Rank / Nomenclatural-status dropdowns.
  // Static for the app's lifetime, so cache indefinitely. Each dropdown always includes the
  // currently-saved value (even before the vocab resolves, or for a legacy value not in the current
  // enum), so a loaded usage never shows a blank rank/nomStatus.
  const { data: vocab } = useQuery({ queryKey: ['vocab'], queryFn: getVocab, staleTime: Infinity });
  const rankData = useMemo(
    () => Array.from(new Set([...(vocab?.ranks ?? []), form.values.rank].filter(Boolean))),
    [vocab?.ranks, form.values.rank],
  );
  // Nomenclatural-status labels are code-specific: the zoological label for a zoological project,
  // the botanical label otherwise (botanical, bacterial -> botany, and as a sensible default when a
  // project has no code set yet).
  const nomStatusData = useMemo(() => {
    const zoo = project?.nomCode === 'zoological';
    const opts = (vocab?.nomStatus ?? []).map((o) => ({
      value: o.value,
      label: zoo ? o.zoological : o.botanical,
    }));
    const cur = form.values.nomStatus;
    if (cur && !opts.some((o) => o.value === cur)) opts.push({ value: cur, label: prettyEnum(cur) });
    return opts;
  }, [vocab?.nomStatus, form.values.nomStatus, project?.nomCode]);

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

  // Seeds the per-scope identifier fields from usage.alternativeId once the project's
  // identifierScopes are known. Kept as its own effect (rather than folded into toFormValues
  // above) so a project whose scopes resolve a tick after the usage doesn't reset the rest of the
  // form -- and it's declared after the effect above so, when both fire together (e.g. switching
  // to a different taxon), this one's seeding wins over that effect's `identifiers: {}` default.
  useEffect(() => {
    if (usage && scopes.length > 0) {
      const identifiers: Record<string, string> = {};
      for (const scope of scopes) {
        identifiers[scope] = scopedId(usage.alternativeId, scope) ?? '';
      }
      form.setFieldValue('identifiers', identifiers);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usage, scopesKey]);

  const mutation = useMutation({
    mutationFn: (values: EditableFields) => {
      if (!usage) throw new Error('usage not loaded yet');
      // Full replace: carry over the loaded usage's values for fields this form doesn't expose,
      // so saving doesn't null them out (see UpdateUsagePayload).
      // alternativeId: fold each configured scope's field value into the loaded usage's
      // alternativeId (withScopedId drops any existing `<scope>:` entry and appends the new one,
      // or drops it entirely when the field is empty) -- preserves col: and any scope this
      // project doesn't have a field for.
      let alternativeId = usage.alternativeId ?? [];
      for (const scope of scopes) {
        alternativeId = withScopedId(alternativeId, scope, values.identifiers[scope] ?? '');
      }
      const payload: UpdateUsagePayload = {
        scientificName: values.scientificName,
        authorship: values.authorship || undefined,
        rank: values.rank,
        status: values.status,
        parentId: usage.parentId ?? undefined,
        namePhrase: usage.namePhrase ?? undefined,
        nomStatus: values.nomStatus || undefined,
        publishedInReferenceId:
          values.publishedInReferenceId === '' ? undefined : values.publishedInReferenceId,
        publishedInYear: values.publishedInYear === '' ? undefined : values.publishedInYear,
        publishedInPage: values.publishedInPage || undefined,
        publishedInPageLink: values.publishedInPageLink || undefined,
        gender: usage.gender ?? undefined,
        extinct: usage.extinct ?? undefined,
        environment: usage.environment ?? undefined,
        temporalRangeStart: usage.temporalRangeStart ?? undefined,
        temporalRangeEnd: usage.temporalRangeEnd ?? undefined,
        etymology: values.etymology || undefined,
        remarks: values.remarks || undefined,
        alternativeId,
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

  // Captured once per render (rather than calling form.getInputProps(...) again inside each
  // Select's onChange below) so the claim-wrapped onChange still delegates to the exact same
  // value/onChange pair the spread below wires up.
  const rankInputProps = form.getInputProps('rank');
  const statusInputProps = form.getInputProps('status');
  const nomStatusInputProps = form.getInputProps('nomStatus');

  return (
    <Box>
      {foreignLock && (
        <Alert color="orange" variant="light" mb="sm" icon={<IconLock size={16} />}>
          {foreignLock.username} is editing this name — your changes may conflict.
        </Alert>
      )}
      <Tabs defaultValue="details" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="details">Details</Tabs.Tab>
          <Tabs.Tab value="synonyms">Synonyms</Tabs.Tab>
          <Tabs.Tab value="names">Relations</Tabs.Tab>
          <Tabs.Tab value="types">Types</Tabs.Tab>
          {isAccepted && <Tabs.Tab value="vernaculars">Vernaculars</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="distribution">Distribution</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="media">Media</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="estimates">Estimates</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="properties">Properties</Tabs.Tab>}
          <Tabs.Tab value="issues">Issues</Tabs.Tab>
          <Tabs.Tab value="references">References</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="details" pt="md">
          <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
            {/* Native DOM onInput, not the mantine form's onValuesChange: real user typing/selection
                bubbles a native input event up to the fieldset, but the form-seeding effects above
                (form.setValues/setFieldValue) are programmatic and never dispatch one -- so this
                claims the lock only on genuine edit intent, not on load/reseed. claim() is
                idempotent, so firing on every keystroke is fine. */}
            <fieldset
              disabled={!canEdit}
              onInput={() => claim()}
              style={{ border: 'none', padding: 0, margin: 0 }}
            >
              <Stack gap="md">
                <SimpleGrid cols={2}>
                  <TextInput label="Scientific name" {...form.getInputProps('scientificName')} />
                  <TextInput label="Authorship" {...form.getInputProps('authorship')} />
                </SimpleGrid>
                <SimpleGrid cols={2}>
                  <Select
                    label="Rank"
                    searchable
                    data={rankData}
                    autoComplete="off"
                    {...rankInputProps}
                    onChange={(v) => {
                      // Mantine's non-searchable/click-only Select selection is a controlled-state
                      // change with no native DOM input event, so it never bubbles to the
                      // fieldset's onInput below -- claim() here catches that click-without-typing
                      // edit. Safe against seeding: onChange only fires on genuine user selection,
                      // never from the programmatic form.setValues/setFieldValue seeding effects
                      // above (a controlled component's onChange doesn't fire when its `value` prop
                      // changes externally).
                      claim();
                      rankInputProps.onChange(v);
                    }}
                  />
                  <Select
                    label="Status"
                    data={STATUS_OPTIONS}
                    {...statusInputProps}
                    onChange={(v) => {
                      claim();
                      statusInputProps.onChange(v);
                    }}
                  />
                </SimpleGrid>
                <EntitySelect
                  label="Published in reference"
                  value={
                    form.values.publishedInReferenceId === ''
                      ? null
                      : String(form.values.publishedInReferenceId)
                  }
                  onChange={(v) => {
                    claim();
                    form.setFieldValue('publishedInReferenceId', v ? Number(v) : '');
                  }}
                  load={referenceOptions(pid)}
                  queryKey={['refOptions', pid]}
                  current={
                    usage.publishedInReferenceId
                      ? {
                          value: String(usage.publishedInReferenceId),
                          label: `#${usage.publishedInReferenceId}`,
                        }
                      : null
                  }
                />
                <SimpleGrid cols={3}>
                  <NumberInput label="Published in year" {...form.getInputProps('publishedInYear')} />
                  <TextInput label="Published in page" {...form.getInputProps('publishedInPage')} />
                  <TextInput
                    label="Published in page link"
                    {...form.getInputProps('publishedInPageLink')}
                  />
                </SimpleGrid>
                <Select
                  label="Nomenclatural status"
                  searchable
                  clearable
                  data={nomStatusData}
                  autoComplete="off"
                  data-1p-ignore
                  data-lpignore="true"
                  {...nomStatusInputProps}
                  onChange={(v) => {
                    claim();
                    nomStatusInputProps.onChange(v);
                  }}
                />
                {scopes.length > 0 && (
                  <SimpleGrid cols={Math.min(scopes.length, 3)}>
                    {scopes.map((scope) => (
                      <TextInput
                        key={scope}
                        label={scope.toUpperCase()}
                        {...form.getInputProps(`identifiers.${scope}`)}
                      />
                    ))}
                  </SimpleGrid>
                )}
                <Textarea label="Etymology" rows={2} {...form.getInputProps('etymology')} />
                <Textarea label="Remarks" rows={2} {...form.getInputProps('remarks')} />
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
            <Text size="sm" c="dimmed">COL id: {colIdFrom(usage.alternativeId) ?? '—'}</Text>
          </SimpleGrid>
        </Tabs.Panel>

        <Tabs.Panel value="names" pt="md">
          <NameRelationsTab pid={pid} usageId={usageId} canEdit={canEdit} />
        </Tabs.Panel>

        <Tabs.Panel value="types" pt="md">
          <TypeMaterialTab pid={pid} usageId={usageId} canEdit={canEdit} />
        </Tabs.Panel>

        <Tabs.Panel value="references" pt="md">
          <ReferencesTab
            pid={pid}
            usageId={usageId}
            referenceIds={usage.referenceId ?? []}
            version={usage.version}
            canEdit={canEdit}
          />
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
