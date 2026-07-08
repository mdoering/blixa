import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';
import { ApiError, messageFor } from '../api/client';
import { deleteUsage, getUsage, updateUsage } from '../api/usages';
import type { NameUsage, UpdateUsagePayload } from '../api/types';
import type { CreateNameAnchor, CreateNameMode } from './CreateNameModal';

// The minimal shape the mutations below need -- both TreeNode and NameUsage satisfy this.
export interface ActionableUsage {
  id: number;
  scientificName?: string | null;
}

export interface CreateModalState {
  mode: CreateNameMode;
  anchor: CreateNameAnchor | null;
}

// Update (there's no status-only endpoint) is a full replace, so change-status must carry over
// every other field of the freshly-loaded usage -- mirrors TaxonDetail's save payload.
function toUpdatePayload(usage: NameUsage, status: string): UpdateUsagePayload {
  return {
    scientificName: usage.scientificName ?? '',
    authorship: usage.authorship ?? undefined,
    rank: usage.rank ?? '',
    status,
    parentId: usage.parentId ?? undefined,
    namePhrase: usage.namePhrase ?? undefined,
    nomStatus: usage.nomStatus ?? undefined,
    publishedInReferenceId: usage.publishedInReferenceId ?? undefined,
    publishedInYear: usage.publishedInYear ?? undefined,
    publishedInPage: usage.publishedInPage ?? undefined,
    publishedInPageLink: usage.publishedInPageLink ?? undefined,
    gender: usage.gender ?? undefined,
    extinct: usage.extinct ?? undefined,
    environment: usage.environment ?? undefined,
    temporalRangeStart: usage.temporalRangeStart ?? undefined,
    temporalRangeEnd: usage.temporalRangeEnd ?? undefined,
    etymology: usage.etymology ?? undefined,
    link: usage.link ?? undefined,
    remarks: usage.remarks ?? undefined,
    version: usage.version,
  };
}

// Shared create/change-status/delete mutations for name usages, plus the "which create modal is
// open" state -- callers (NameActionMenu, TreePage's toolbar button, later the Names search
// table) render <CreateNameModal> themselves keyed off `modalState`, so each caller controls how
// the resulting new-id gets used (e.g. select it).
export function useNameActions(pid: number) {
  const queryClient = useQueryClient();
  const [modalState, setModalState] = useState<CreateModalState | null>(null);

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
    await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
    await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
  };

  const changeStatusMutation = useMutation({
    mutationFn: async ({ usage, status }: { usage: ActionableUsage; status: string }) => {
      const full = await getUsage(pid, usage.id);
      return updateUsage(pid, usage.id, toUpdatePayload(full, status));
    },
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Status updated' });
    },
    onError: async (e) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          color: 'orange',
          message: 'Changed by someone else — refreshing',
        });
        await invalidate();
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Could not update status') });
    },
  });

  const removeMutation = useMutation({
    mutationFn: (usage: ActionableUsage) => deleteUsage(pid, usage.id),
    onSuccess: async () => {
      await invalidate();
      notifications.show({ message: 'Deleted' });
    },
    onError: (e) => {
      notifications.show({ color: 'red', message: messageFor(e, 'Could not delete') });
    },
  });

  return {
    modalState,
    closeModal: () => setModalState(null),
    createChild: (parent: CreateNameAnchor) => setModalState({ mode: 'child', anchor: parent }),
    createSynonymOf: (accepted: CreateNameAnchor) =>
      setModalState({ mode: 'synonym', anchor: accepted }),
    createRoot: () => setModalState({ mode: 'root', anchor: null }),
    changeStatus: (usage: ActionableUsage, status: string) =>
      changeStatusMutation.mutate({ usage, status }),
    remove: (usage: ActionableUsage) => removeMutation.mutate(usage),
  };
}
