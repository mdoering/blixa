import { Button, Checkbox, Divider, Group, MultiSelect, Select, Stack, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { messageFor } from '../api/client';
import { getVocab } from '../api/coldp';
import { updateTaxonInfo } from '../api/usages';
import type { NameUsage } from '../api/types';
import { PropertyTab } from './taxonTabs';

interface BiologyTabProps {
  pid: number;
  usageId: number;
  canEdit: boolean;
  usage: NameUsage;
}

interface BiologyValues {
  extinct: boolean;
  environment: string[];
  temporalRangeStart: string;
  temporalRangeEnd: string;
}

function toValues(usage: NameUsage): BiologyValues {
  return {
    extinct: usage.extinct ?? false,
    environment: usage.environment ?? [],
    temporalRangeStart: usage.temporalRangeStart ?? '',
    temporalRangeEnd: usage.temporalRangeEnd ?? '',
  };
}

// The taxon-level "Biology" tab: the extinct/environment/temporal-range attributes (taxon_info),
// saved through the narrow PUT /usages/{id}/taxon-info so they never touch the name, stacked above
// the existing flexible taxon Properties list. Accepted-gated like the other taxon-level tabs.
export default function BiologyTab({ pid, usageId, canEdit, usage }: BiologyTabProps) {
  const queryClient = useQueryClient();
  const { data: vocab } = useQuery({ queryKey: ['vocab'], queryFn: getVocab, staleTime: Infinity });

  const form = useForm<BiologyValues>({ initialValues: toValues(usage) });

  // Re-seed when the usage changes (e.g. after a save bumps the shared version, or switching taxa).
  useEffect(() => {
    const values = toValues(usage);
    form.setValues(values);
    form.resetDirty(values);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usage]);

  const mutation = useMutation({
    mutationFn: (v: BiologyValues) =>
      updateTaxonInfo(pid, usageId, {
        extinct: v.extinct || undefined,
        environment: v.environment.length ? v.environment : undefined,
        temporalRangeStart: v.temporalRangeStart || undefined,
        temporalRangeEnd: v.temporalRangeEnd || undefined,
        version: usage.version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['usage', pid, usageId] });
      await queryClient.invalidateQueries({ queryKey: ['usageIssues', pid, usageId] });
      notifications.show({ message: 'Saved' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Save failed') }),
  });

  const envOptions = (vocab?.environment ?? []).map((v) => ({ value: v, label: titleCase(v) }));
  const geoOptions = (vocab?.geoTimes ?? []).map((v) => ({ value: v, label: v }));

  return (
    <Stack>
      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
        <Stack gap="sm">
          <Title order={5} m={0}>
            Biology
          </Title>
          <Checkbox
            label="† Extinct"
            disabled={!canEdit}
            {...form.getInputProps('extinct', { type: 'checkbox' })}
          />
          <MultiSelect
            label="Environment"
            data={envOptions}
            disabled={!canEdit}
            clearable
            {...form.getInputProps('environment')}
          />
          <Group grow align="flex-start">
            <Select
              label="Temporal range start"
              placeholder="oldest"
              data={geoOptions}
              searchable
              clearable
              disabled={!canEdit}
              {...form.getInputProps('temporalRangeStart')}
            />
            <Select
              label="Temporal range end"
              placeholder="youngest"
              data={geoOptions}
              searchable
              clearable
              disabled={!canEdit}
              {...form.getInputProps('temporalRangeEnd')}
            />
          </Group>
          {canEdit && (
            <Group justify="flex-end">
              <Button type="submit" loading={mutation.isPending} disabled={!form.isDirty()}>
                Save
              </Button>
            </Group>
          )}
        </Stack>
      </form>

      <Divider label="Properties" labelPosition="left" />
      <PropertyTab pid={pid} usageId={usageId} canEdit={canEdit} />
    </Stack>
  );
}

// Environment values arrive as enum names (MARINE, FRESHWATER, ...); show them Title-cased.
function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}
