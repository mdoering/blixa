import { Button, Checkbox, Group, Loader, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { messageFor } from '../api/client';
import {
  applyHomotypic,
  detectHomotypic,
  getUsage,
  type ApplyRelation,
  type ProposedRelation,
} from '../api/usages';

export interface HomotypicGroupModalProps {
  pid: number;
  usageId: number;
  onClose: () => void;
}

function relKey(r: ProposedRelation) {
  return `${r.usageId}:${r.relatedUsageId}:${r.type}`;
}

// Detect-and-confirm: previews proposed homotypic relations for the accepted taxon, lets the curator
// uncheck any, and applies the checked (new) ones as name_relation rows.
export default function HomotypicGroupModal({ pid, usageId, onClose }: HomotypicGroupModalProps) {
  const qc = useQueryClient();
  const { data: proposal, isLoading } = useQuery({
    queryKey: ['homotypicDetect', pid, usageId],
    queryFn: () => detectHomotypic(pid, usageId),
  });

  const relations: ProposedRelation[] = (proposal?.groups ?? []).flatMap((g) => g.relations);
  const memberIds = Array.from(
    new Set((proposal?.groups ?? []).flatMap((g) => g.memberUsageIds)),
  );
  const nameQueries = useQueries({
    queries: memberIds.map((id) => ({
      queryKey: ['usage', pid, id],
      queryFn: () => getUsage(pid, id),
    })),
  });
  const nameOf = (id: number) => {
    const q = nameQueries[memberIds.indexOf(id)];
    return q?.data?.scientificName ?? `#${id}`;
  };

  // default: every NEW relation checked; already-existing ones shown but unchecked (no-op).
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set());
  const isChecked = (r: ProposedRelation) => !r.alreadyExists && !unchecked.has(relKey(r));
  const toggle = (r: ProposedRelation) =>
    setUnchecked((prev) => {
      const next = new Set(prev);
      const k = relKey(r);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });

  const apply = useMutation({
    mutationFn: () => {
      const chosen: ApplyRelation[] = relations
        .filter(isChecked)
        .map((r) => ({ usageId: r.usageId, relatedUsageId: r.relatedUsageId, type: r.type }));
      return applyHomotypic(pid, usageId, chosen);
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['synonymy', pid, usageId] });
      notifications.show({ message: 'Homotypic relations applied' });
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not apply') }),
  });

  return (
    <Modal opened onClose={onClose} title="Group synonyms homotypically" size="lg">
      {isLoading ? (
        <Loader />
      ) : relations.length === 0 ? (
        <Text size="sm" c="dimmed">No homotypic relations detected.</Text>
      ) : (
        <Stack>
          {relations.map((r) => (
            <Checkbox
              key={relKey(r)}
              checked={isChecked(r)}
              disabled={r.alreadyExists}
              onChange={() => toggle(r)}
              label={
                <Text size="sm">
                  {nameOf(r.usageId)} <b>{r.type}</b> {nameOf(r.relatedUsageId)}
                  {r.alreadyExists ? <Text span c="dimmed" size="xs"> (already linked)</Text> : null}
                </Text>
              }
            />
          ))}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cancel</Button>
            <Button loading={apply.isPending} onClick={() => apply.mutate()}>Apply</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
