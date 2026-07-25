import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Checkbox,
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
import { IconBook, IconBrain, IconLock, IconPencil, IconRefresh, IconWorld } from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, messageFor } from '../api/client';
import { getProject } from '../api/projects';
import { getVocab } from '../api/coldp';
import { listLocks } from '../api/locks';
import { getUsage, searchUsages, updateGenusId, updateUsage } from '../api/usages';
import { getAiConfig } from '../api/ai';
import { revalidateSubtree } from '../api/issues';
import { getReference } from '../api/references';
import BhlPageModal from './BhlPageModal';
import type { NameUsage, UpdateUsagePayload } from '../api/types';
import CurieId from '../components/CurieId';
import InfoLabel from '../components/InfoLabel';
import ClassificationBar from './ClassificationBar';
import EntitySelect from '../child/EntitySelect';
import NameRelationsTab, { referenceOptions } from '../child/NameRelationsTab';
import { colIdFrom, scopedId, withScopedId } from '../child/map/mapUrls';
import ReferencesTab from '../child/ReferencesTab';
import TypeMaterialTab from '../child/TypeMaterialTab';
import UsageDiscussionsTab from '../discussions/UsageDiscussionsTab';
import CompareClbModal from '../child/clb/CompareClbModal';
import BiologyTab from '../child/BiologyTab';
import {
  DistributionTab,
  EstimateTab,
  MediaTab,
  VernacularTab,
} from '../child/taxonTabs';
import { useUsageLock } from '../lock/useUsageLock';
import IssueList from './IssueList';
import Synonymy from './Synonymy';
import AiSuggestModal from './AiSuggestModal';

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

// Splits an alternativeId CURIE entry ("scope:id") on the FIRST colon, so a scope-less or
// malformed entry (no colon) is skipped rather than rendered as a bare/garbled chip.
function parseCurie(entry: string): { scope: string; id: string } | null {
  const i = entry.indexOf(':');
  if (i < 0) return null;
  return { scope: entry.slice(0, i), id: entry.slice(i + 1) };
}

// The parser classifies a name it can't treat as a normal scientific name (nameType != SCIENTIFIC):
// these won't atomise into name parts, so the form flags them prominently. (See the name-vs-parts
// design discussion 2026-07-24.)
const NAME_TYPE_LABEL: Record<string, string> = {
  FORMULA: 'Hybrid formula',
  INFORMAL: 'Informal name',
  PLACEHOLDER: 'Placeholder',
  IDENTIFIER: 'Identifier',
  OTHER: 'Other / unparsable',
};

// The name-quality warning for a usage: a hard flag when it isn't a scientific name at all, else a
// softer one when a scientific name only partially parsed. Null when the name atomised cleanly.
function nameWarningFor(
  nameType: string | null,
  parseState: string | null,
): { color: string; label: string; hint: string } | null {
  if (nameType && nameType !== 'SCIENTIFIC') {
    return {
      color: 'red',
      label: NAME_TYPE_LABEL[nameType] ?? nameType,
      hint: 'Not a standard scientific name — the parser won’t atomise it into name parts.',
    };
  }
  if (parseState && parseState !== 'COMPLETE') {
    return {
      color: 'orange',
      label: parseState === 'NONE' ? 'Unparsed' : 'Partially parsed',
      hint: 'The parser could not fully atomise this scientific name.',
    };
  }
  return null;
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
  // Grammatical gender: editable on a genus (its own gender); on a bi/trinomial the parent genus
  // defines it and only genderAgreement is editable.
  gender: string;
  genderAgreement: boolean;
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
    gender: u.gender ?? '',
    genderAgreement: u.genderAgreement ?? false,
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
  // Navigate the form to another usage (its owner holds the selected id) -- used by the
  // classification bar's ancestor links. Without it the ancestors render as plain text.
  onNavigate?: (id: number) => void;
}

// Views + edits one name usage's fields, plus its synonyms/accepted targets and validation
// issues. Save is optimistic-locked on the loaded `version`: a 409 (someone else saved first)
// reloads the usage and reseeds the form instead of clobbering their change.
export default function TaxonDetail({ pid, usageId, onNavigate }: TaxonDetailProps) {
  const queryClient = useQueryClient();

  // Identifiers section view/edit toggle: view mode (default) shows usage.alternativeId as
  // read-only linked CurieId chips; edit mode shows the per-scope TextInput form below. Collapses
  // back to view mode on a successful save (see the mutation's onSuccess) so the freshly-saved
  // identifiers immediately show as resolved chips again.
  const [editingIds, setEditingIds] = useState(false);
  const [compareOpen, setCompareOpen] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);

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
      gender: '',
      genderAgreement: false,
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

  // Whether the AI-curation affordance should appear at all: a provider configured WITH a backend
  // key (keys are backend-only, so the frontend can only ask). Cached briefly across taxa.
  const { data: aiConfig } = useQuery({
    queryKey: ['aiConfig', pid],
    queryFn: () => getAiConfig(pid),
    staleTime: 5 * 60 * 1000,
  });

  // BHL page finder: the focal name's nomenclatural reference must already have a linked BHL item.
  const nomRefId =
    typeof form.values.publishedInReferenceId === 'number'
      ? form.values.publishedInReferenceId
      : null;
  const { data: nomRef } = useQuery({
    queryKey: ['reference', pid, nomRefId],
    queryFn: () => getReference(pid, nomRefId as number),
    enabled: nomRefId != null && canEdit,
  });
  const [bhlPageOpen, setBhlPageOpen] = useState(false);

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
        // Gender belongs to the genus; a bi/trinomial only carries genderAgreement (its own gender
        // stays null and is derived from the parent). Suprageneric: neither.
        gender: values.rank === 'genus' ? values.gender || undefined : undefined,
        genderAgreement: usage.specificEpithet ? values.genderAgreement : undefined,
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
      // Collapse the identifiers section back to its read-only chip view, now showing whatever
      // was just saved.
      setEditingIds(false);
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

  // "Revalidate this group": recompute the focal taxon's whole subtree, so relational rules settle
  // across it (see the subtree-revalidation design). The returned summary is scoped to the subtree.
  const revalidateMutation = useMutation({
    mutationFn: () => revalidateSubtree(pid, usageId),
    onSuccess: async (summary) => {
      await queryClient.invalidateQueries({ queryKey: ['usageIssues', pid, usageId] });
      await queryClient.invalidateQueries({ queryKey: ['issueSummary', pid] });
      const errors = summary.bySeverity.error ?? 0;
      const warnings = summary.bySeverity.warning ?? 0;
      notifications.show({
        message: `Revalidated this group: ${summary.total} issue${summary.total === 1 ? '' : 's'}`
          + ` (${errors} error${errors === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'})`,
      });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Revalidate failed') }),
  });

  // Controlled tabs so switching usage (e.g. clicking a synonym) never leaves a now-hidden tab
  // active with a blank panel: fall the selection back to Details whenever the current tab isn't
  // available for the loaded usage. The taxon-level tabs (synonyms/vernaculars/distribution/media/
  // estimates/biology) exist only for accepted taxa -- a synonym has none of them.
  const [activeTab, setActiveTab] = useState<string | null>('details');
  useEffect(() => {
    if (!usage) return;
    const taxonTabs =
      usage.status === 'ACCEPTED'
        ? ['synonyms', 'vernaculars', 'distribution', 'media', 'estimates', 'properties']
        : [];
    const available = ['details', 'names', 'types', 'issues', 'references', 'discussions', ...taxonTabs];
    if (activeTab && !available.includes(activeTab)) setActiveTab('details');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usage, activeTab]);

  // Genus options for a binomial's nomenclatural-genus picker: the project's genus usages. Loaded
  // only when the usage is a bi/trinomial (has a specific epithet).
  const { data: generaPage } = useQuery({
    queryKey: ['genera', pid],
    queryFn: () => searchUsages(pid, { rank: 'genus', limit: 500, offset: 0 }),
    enabled: !!usage?.specificEpithet,
    staleTime: 60_000,
  });

  // Pin/clear the nomenclatural genus link (a direct narrow write, not part of the Details Save).
  const genusMutation = useMutation({
    mutationFn: (genusId: number | null) => updateGenusId(pid, usageId, { genusId, version: usage!.version }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      await queryClient.invalidateQueries({ queryKey: ['usageIssues', pid, usageId] });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Linking the genus failed') }),
  });

  if (usageQuery.isLoading) return <Text c="dimmed">Loading…</Text>;
  if (usageQuery.isError || !usage) return <Text c="red">Could not load this taxon</Text>;

  // The taxon-level entities only apply to accepted taxa (the backend guards create + drops them on
  // demote), so their tabs only show when accepted.
  const isAccepted = usage.status === 'ACCEPTED';

  // A plain status edit may only move WITHIN a group (accepted<->unassessed, synonym<->misapplied):
  // crossing the taxon<->synonym boundary must reassign children + set/clear the accepted link, which
  // only the guided Demote/Promote actions do (the backend rejects a cross-group plain update). So the
  // Select offers just the loaded usage's group.
  const inTaxonGroup = ['ACCEPTED', 'UNASSESSED'].includes((usage.status ?? 'ACCEPTED').toUpperCase());
  const statusGroup = inTaxonGroup ? ['ACCEPTED', 'UNASSESSED'] : ['SYNONYM', 'MISAPPLIED'];
  const statusOptions = STATUS_OPTIONS.filter((o) => statusGroup.includes(o.value));

  // Captured once per render (rather than calling form.getInputProps(...) again inside each
  // Select's onChange below) so the claim-wrapped onChange still delegates to the exact same
  // value/onChange pair the spread below wires up.
  const rankInputProps = form.getInputProps('rank');
  const statusInputProps = form.getInputProps('status');
  const nomStatusInputProps = form.getInputProps('nomStatus');
  const genderInputProps = form.getInputProps('gender');
  // Gender is editable only on a genus; a bi/trinomial (has a specific epithet) derives it from the
  // parent genus and only toggles agreement; suprageneric names show neither.
  const isGenus = form.values.rank === 'genus';
  const isBinomialOrBelow = !!usage.specificEpithet;

  // Options for the nomenclatural-genus picker; ensure the currently-linked genus is present even if
  // it falls outside the loaded page, so the Select can show its label.
  const generaOptions = (generaPage?.items ?? []).map((g) => ({
    value: String(g.id),
    label: g.scientificName ?? String(g.id),
  }));
  if (usage.genusId != null && !generaOptions.some((o) => o.value === String(usage.genusId))) {
    generaOptions.unshift({ value: String(usage.genusId), label: usage.genusName ?? String(usage.genusId) });
  }

  return (
    <Box>
      <Group justify="flex-end" mb="xs">
        {canEdit && (
          <ActionIcon
            variant="light"
            size="lg"
            color="gray"
            aria-label="Revalidate this group"
            title="Revalidate this group (recompute the subtree's issues)"
            loading={revalidateMutation.isPending}
            onClick={() => revalidateMutation.mutate()}
          >
            <IconRefresh size={18} />
          </ActionIcon>
        )}
        {canEdit && aiConfig?.available && (
          <ActionIcon
            variant="light"
            size="lg"
            aria-label="AI suggestions"
            title="AI suggestions"
            onClick={() => setAiOpen(true)}
          >
            <IconBrain size={18} />
          </ActionIcon>
        )}
        <Button
          variant="default"
          size="xs"
          leftSection={<IconWorld size={14} />}
          onClick={() => setCompareOpen(true)}
        >
          Compare with CLB…
        </Button>
      </Group>
      <CompareClbModal
        pid={pid}
        usageId={usageId}
        opened={compareOpen}
        onClose={() => setCompareOpen(false)}
      />
      <AiSuggestModal
        pid={pid}
        usageId={usageId}
        usageRank={usage.rank}
        usageName={usage.scientificName}
        opened={aiOpen}
        onClose={() => setAiOpen(false)}
      />
      {foreignLock && (
        <Alert color="orange" variant="light" mb="sm" icon={<IconLock size={16} />}>
          {foreignLock.username} is editing this name — your changes may conflict.
        </Alert>
      )}
      <ClassificationBar pid={pid} usage={usage} canEdit={canEdit} onNavigate={onNavigate} />
      <Tabs value={activeTab} onChange={setActiveTab} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="details">Details</Tabs.Tab>
          {isAccepted && <Tabs.Tab value="synonyms">Synonyms</Tabs.Tab>}
          <Tabs.Tab value="names">Relations</Tabs.Tab>
          <Tabs.Tab value="types">Types</Tabs.Tab>
          {isAccepted && <Tabs.Tab value="vernaculars">Vernaculars</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="distribution">Distribution</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="media">Media</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="estimates">Estimates</Tabs.Tab>}
          {isAccepted && <Tabs.Tab value="properties">Biology</Tabs.Tab>}
          <Tabs.Tab value="issues">Issues</Tabs.Tab>
          <Tabs.Tab value="references">References</Tabs.Tab>
          <Tabs.Tab value="discussions">Discussions</Tabs.Tab>
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
                {(() => {
                  const w = nameWarningFor(usage.nameType, usage.parseState);
                  return w ? (
                    <Group gap="xs" wrap="nowrap" mt={-8}>
                      <Badge color={w.color} variant="filled" size="sm" style={{ flexShrink: 0 }}>
                        {w.label}
                      </Badge>
                      <Text size="xs" c="dimmed">
                        {w.hint}
                      </Text>
                    </Group>
                  ) : null;
                })()}
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
                    label={<InfoLabel label="Status" info="Accepted ↔ synonym uses Demote/Promote" />}
                    aria-label="Status"
                    data={statusOptions}
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
                {canEdit && nomRef?.bhlItemId != null && (
                  <Group>
                    <Button
                      size="xs"
                      variant="light"
                      leftSection={<IconBook size={14} />}
                      onClick={() => setBhlPageOpen(true)}
                    >
                      Find page on BHL…
                    </Button>
                  </Group>
                )}
                {nomRef?.bhlItemId != null && (
                  <BhlPageModal
                    pid={pid}
                    itemId={nomRef.bhlItemId}
                    name={usage.scientificName ?? ''}
                    opened={bhlPageOpen}
                    onClose={() => setBhlPageOpen(false)}
                    onPick={(p) => {
                      form.setFieldValue('publishedInPageLink', p.url);
                      form.setFieldValue('publishedInPage', p.pageNumber ?? '');
                      claim();
                    }}
                  />
                )}
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
                {isGenus ? (
                  <Select
                    label="Gender"
                    placeholder="—"
                    clearable
                    data={vocab?.gender ?? []}
                    disabled={!canEdit}
                    {...genderInputProps}
                    onChange={(v) => {
                      claim();
                      genderInputProps.onChange(v);
                    }}
                  />
                ) : isBinomialOrBelow ? (
                  <Stack gap="sm">
                    <Group grow align="flex-start" gap="md">
                      <Select
                        label={
                          <InfoLabel
                            label="Nomenclatural genus"
                            info="The genus this name's epithet agrees with"
                          />
                        }
                        aria-label="Nomenclatural genus"
                        placeholder={usage.genus ?? 'genus'}
                        searchable
                        clearable
                        disabled={!canEdit || genusMutation.isPending}
                        data={generaOptions}
                        value={usage.genusId != null ? String(usage.genusId) : null}
                        onChange={(v) => genusMutation.mutate(v ? Number(v) : null)}
                      />
                      <TextInput
                        label={usage.genusId != null ? 'Gender' : 'Gender (unconfirmed)'}
                        readOnly
                        value={usage.genusGender ?? '—'}
                      />
                    </Group>
                    <Checkbox
                      label={
                        <InfoLabel
                          label="Gender agreement"
                          info="Epithets follow the genus gender (e.g. alba / albus)"
                        />
                      }
                      aria-label="Gender agreement"
                      disabled={!canEdit}
                      checked={form.values.genderAgreement}
                      onChange={(e) => {
                        claim();
                        form.setFieldValue('genderAgreement', e.currentTarget.checked);
                      }}
                    />
                  </Stack>
                ) : null}
                {((usage.alternativeId?.length ?? 0) > 0 || (canEdit && scopes.length > 0)) &&
                  (editingIds ? (
                    <SimpleGrid cols={Math.min(scopes.length, 3)}>
                      {scopes.map((scope) => (
                        <TextInput
                          key={scope}
                          label={scope.toUpperCase()}
                          {...form.getInputProps(`identifiers.${scope}`)}
                        />
                      ))}
                    </SimpleGrid>
                  ) : (
                    <Group gap="xs" wrap="wrap">
                      {(usage.alternativeId ?? []).map((entry) => {
                        const parsed = parseCurie(entry);
                        return parsed ? (
                          <CurieId key={entry} scope={parsed.scope} id={parsed.id} />
                        ) : null;
                      })}
                      {canEdit && scopes.length > 0 && (
                        <ActionIcon
                          type="button"
                          variant="subtle"
                          size="xs"
                          aria-label="Edit identifiers"
                          onClick={() => setEditingIds(true)}
                        >
                          <IconPencil size={12} />
                        </ActionIcon>
                      )}
                    </Group>
                  ))}
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

        <Tabs.Panel value="discussions" pt="md">
          <UsageDiscussionsTab pid={pid} usageId={usageId} />
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
            <BiologyTab pid={pid} usageId={usageId} canEdit={canEdit} usage={usage} />
          </Tabs.Panel>
        )}

        {isAccepted && (
          <Tabs.Panel value="synonyms" pt="md">
            <Synonymy pid={pid} usageId={usageId} canEdit={canEdit} />
          </Tabs.Panel>
        )}

        <Tabs.Panel value="issues" pt="md">
          <IssueList pid={pid} entityId={usageId} />
        </Tabs.Panel>
      </Tabs>
    </Box>
  );
}
