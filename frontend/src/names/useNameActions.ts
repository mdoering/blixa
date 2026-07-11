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

// The node currently targeted by the Move modal (null = closed). Structurally compatible with
// MoveNameModal's MoveTarget; kept inline so this hook doesn't depend on the tree module.
export interface MoveModalTarget {
  id: number;
  scientificName: string | null;
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
    remarks: usage.remarks ?? undefined,
    version: usage.version,
  };
}

// Shared create/change-status/delete mutations for name usages, plus the "which create modal is
// open" state -- callers (NameActionMenu, TreePage's toolbar button, later the Names search
// table) render <CreateNameModal> themselves keyed off `modalState`, so each caller controls how
// the resulting new-id gets used (e.g. select it).
// The node targeted by the Demote modal, plus which non-accepted status the user picked.
export interface DemoteModalTarget {
  usage: MoveModalTarget;
  status: string;
}

export function useNameActions(pid: number) {
  const queryClient = useQueryClient();
  const [modalState, setModalState] = useState<CreateModalState | null>(null);
  const [moveTarget, setMoveTarget] = useState<MoveModalTarget | null>(null);
  const [demoteTarget, setDemoteTarget] = useState<DemoteModalTarget | null>(null);
  const [promoteTarget, setPromoteTarget] = useState<MoveModalTarget | null>(null);
  const [linkAcceptedTarget, setLinkAcceptedTarget] = useState<MoveModalTarget | null>(null);
  const [clbImportTarget, setClbImportTarget] = useState<MoveModalTarget | null>(null);
  const [bulkTarget, setBulkTarget] = useState<{ id: number; scientificName: string | null } | null>(null);

  // `id` is the affected usage: also invalidate its own detail query and path so a currently-open
  // TaxonDetail (reads ['usage', pid, id]) and Breadcrumb (reads ['treePath', pid, id]) refresh
  // instead of continuing to show stale/deleted data -- mirrors TaxonDetail's own save.
  const invalidate = async (id: number) => {
    await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
    await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
    await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
    await queryClient.invalidateQueries({ queryKey: ['usage', pid, id] });
    await queryClient.invalidateQueries({ queryKey: ['treePath', pid] });
  };

  const changeStatusMutation = useMutation({
    mutationFn: async ({ usage, status }: { usage: ActionableUsage; status: string }) => {
      const full = await getUsage(pid, usage.id);
      return updateUsage(pid, usage.id, toUpdatePayload(full, status));
    },
    onSuccess: async (_data, { usage }) => {
      await invalidate(usage.id);
      notifications.show({ message: 'Status updated' });
    },
    onError: async (e, { usage }) => {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          color: 'orange',
          message: 'Changed by someone else — refreshing',
        });
        await invalidate(usage.id);
        return;
      }
      notifications.show({ color: 'red', message: messageFor(e, 'Could not update status') });
    },
  });

  const removeMutation = useMutation({
    mutationFn: (usage: ActionableUsage) => deleteUsage(pid, usage.id),
    onSuccess: async (_data, usage) => {
      await invalidate(usage.id);
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
    // Move modal open/close (the reparent flow itself lives in MoveNameModal, mirroring how the
    // create flow lives in CreateNameModal off `modalState`).
    moveTarget,
    startMove: (usage: MoveModalTarget) => setMoveTarget(usage),
    closeMove: () => setMoveTarget(null),
    // Demote (acc->syn) and promote (syn->acc) modals; the flows live in Demote/PromoteModal.
    demoteTarget,
    startDemote: (usage: MoveModalTarget, status: string) => setDemoteTarget({ usage, status }),
    closeDemote: () => setDemoteTarget(null),
    promoteTarget,
    startPromote: (usage: MoveModalTarget) => setPromoteTarget(usage),
    closePromote: () => setPromoteTarget(null),
    // Add-accepted (pro parte) modal for a synonym/misapplied usage.
    linkAcceptedTarget,
    startLinkAccepted: (usage: MoveModalTarget) => setLinkAcceptedTarget(usage),
    closeLinkAccepted: () => setLinkAcceptedTarget(null),
    // "Import from ChecklistBank" modal, opened on an accepted usage (the focal target the
    // picked CLB taxon is imported under/onto) -- the flow itself lives in ClbImportModal.
    clbImportTarget,
    startClbImport: (usage: MoveModalTarget) => setClbImportTarget(usage),
    closeClbImport: () => setClbImportTarget(null),
    // "Bulk add…" modal, opened on an accepted usage (the target children/synonyms are inserted
    // under/onto) -- the flow itself lives in BulkAddModal.
    bulkTarget,
    startBulk: (usage: { id: number; scientificName: string | null }) =>
      setBulkTarget({ id: usage.id, scientificName: usage.scientificName }),
    closeBulk: () => setBulkTarget(null),
    changeStatus: (usage: ActionableUsage, status: string) =>
      changeStatusMutation.mutate({ usage, status }),
    // `onSuccess` here (rather than baked into removeMutation above) lets callers react to a
    // specific delete -- e.g. NameActionMenu's onAfterDelete clearing the selection if the
    // deleted usage was the one selected -- without every caller needing its own mutation.
    remove: (usage: ActionableUsage, options?: { onSuccess?: () => void }) =>
      removeMutation.mutate(usage, options),
  };
}
