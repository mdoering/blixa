import { ActionIcon, Menu } from '@mantine/core';
import { useState } from 'react';
import {
  IconArrowsMove,
  IconChevronRight,
  IconCloudDownload,
  IconDotsVertical,
  IconPlus,
  IconTrash,
} from '@tabler/icons-react';
import MoveNameModal from '../tree/MoveNameModal';
import DemoteModal from '../tree/DemoteModal';
import PromoteModal from '../tree/PromoteModal';
import LinkAcceptedModal from '../tree/LinkAcceptedModal';
import CreateNameModal from './CreateNameModal';
import { useNameActions } from './useNameActions';
import DeleteNameModal from './DeleteNameModal';
import ClbImportModal from '../clb/ClbImportModal';
import BulkAddModal from './BulkAddModal';

const STATUS_OPTIONS = [
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'SYNONYM', label: 'Synonym' },
  { value: 'MISAPPLIED', label: 'Misapplied' },
  { value: 'UNASSESSED', label: 'Unassessed' },
];

// The minimal shape needed for the actions below -- both TreeNode and NameUsage satisfy this.
export interface NameActionMenuUsage {
  id: number;
  scientificName: string | null;
  status: string | null;
}

export interface NameActionMenuProps {
  pid: number;
  usage: NameActionMenuUsage;
  canEdit: boolean;
  // Called after a child/synonym create succeeds, with the new usage's id, so the caller can
  // select it (mirrors the tree's existing onSelect(id) callback).
  onSelect: (id: number) => void;
  // Lets the row control the menu (e.g. open it on right-click) in addition to its own ⋮ target.
  opened?: boolean;
  onChange?: (opened: boolean) => void;
  // Called after a confirmed delete succeeds, with the deleted usage's id -- so the caller can
  // clear its selection if the deleted row was the one selected (otherwise the detail pane would
  // keep rendering the now-gone record).
  onAfterDelete?: (deletedId: number) => void;
}

// Shared ⋮ (+ right-click, via the caller's `opened`/`onChange`) action menu for a single name
// usage: add child, add synonym, change status, delete. Used by the Tree's rows and (later) the
// Names search table's rows. Writes are owner/editor-only, so the whole menu is hidden for
// anyone else rather than shown-but-disabled.
export default function NameActionMenu({
  pid,
  usage,
  canEdit,
  onSelect,
  opened,
  onChange,
  onAfterDelete,
}: NameActionMenuProps) {
  const actions = useNameActions(pid);
  const [deleteOpen, setDeleteOpen] = useState(false);

  if (!canEdit) return null;

  // acc -> syn/misapplied opens the guided Demote modal; syn/misapplied -> accepted opens Promote;
  // every other transition (e.g. synonym <-> misapplied, -> unassessed) is a plain status update.
  const onStatusClick = (target: string) => {
    const move = { id: usage.id, scientificName: usage.scientificName };
    if (usage.status === 'ACCEPTED' && (target === 'SYNONYM' || target === 'MISAPPLIED')) {
      actions.startDemote(move, target);
    } else if ((usage.status === 'SYNONYM' || usage.status === 'MISAPPLIED') && target === 'ACCEPTED') {
      actions.startPromote(move);
    } else {
      actions.changeStatus(usage, target);
    }
    // The status options live in a nested Menu (see the "Change status" submenu below), whose
    // click-outside handling doesn't reliably reach through to close the *outer* menu too --
    // close it explicitly so picking a status doesn't leave the outer menu stuck open.
    onChange?.(false);
  };

  // Synonyms/misapplied/unassessed usages can neither be a parent nor have synonyms of their
  // own -- the backend 400s both attempts -- so only offer these for accepted usages.
  const canHaveChildrenOrSynonyms = usage.status === 'ACCEPTED';

  const confirmDelete = () => setDeleteOpen(true);

  return (
    <>
      <Menu shadow="md" width={200} opened={opened} onChange={onChange} withinPortal>
        <Menu.Target>
          <ActionIcon
            variant="subtle"
            color="gray"
            size="sm"
            aria-label="Actions"
            onClick={(e) => e.stopPropagation()}
          >
            <IconDotsVertical size={14} />
          </ActionIcon>
        </Menu.Target>
        <Menu.Dropdown onClick={(e) => e.stopPropagation()}>
          {canHaveChildrenOrSynonyms && (
            <>
              <Menu.Item
                leftSection={<IconPlus size={14} />}
                onClick={() => actions.createChild(usage)}
              >
                Add child
              </Menu.Item>
              <Menu.Item
                leftSection={<IconPlus size={14} />}
                onClick={() => actions.createSynonymOf(usage)}
              >
                Add synonym
              </Menu.Item>
              <Menu.Item
                leftSection={<IconArrowsMove size={14} />}
                onClick={() => actions.startMove(usage)}
              >
                Move…
              </Menu.Item>
              <Menu.Item
                leftSection={<IconCloudDownload size={14} />}
                onClick={() => actions.startClbImport(usage)}
              >
                Import from ChecklistBank…
              </Menu.Item>
              <Menu.Item
                leftSection={<IconPlus size={14} />}
                onClick={() => actions.startBulk(usage)}
              >
                Bulk add…
              </Menu.Item>
            </>
          )}
          {(usage.status === 'SYNONYM' || usage.status === 'MISAPPLIED') && (
            <Menu.Item
              leftSection={<IconPlus size={14} />}
              onClick={() =>
                actions.startLinkAccepted({ id: usage.id, scientificName: usage.scientificName })
              }
            >
              Add accepted name…
            </Menu.Item>
          )}
          {/* Menu.Sub (nested submenu) isn't available in the installed @mantine/core version
              (Menu.Sub / MenuSub is unwired from the package's public exports here), so this is
              a workaround: a nested <Menu> whose target is itself a Menu.Item inside the outer
              dropdown. `trigger="click-hover"` opens it on hover *and* on click (tests drive it
              via click); `closeMenuOnClick={false}` on the target keeps the outer menu open when
              that item is clicked, so the click toggles the submenu instead of dismissing
              everything. The current status is disabled so a user can't "change" to the status
              the usage already has. */}
          <Menu trigger="click-hover" position="right-start" offset={2} withinPortal shadow="md" closeOnItemClick>
            <Menu.Target>
              <Menu.Item closeMenuOnClick={false} rightSection={<IconChevronRight size={14} />}>
                Change status
              </Menu.Item>
            </Menu.Target>
            <Menu.Dropdown>
              {STATUS_OPTIONS.map((s) => (
                <Menu.Item
                  key={s.value}
                  disabled={usage.status === s.value}
                  onClick={() => onStatusClick(s.value)}
                >
                  {s.label}
                </Menu.Item>
              ))}
            </Menu.Dropdown>
          </Menu>
          <Menu.Divider />
          <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={confirmDelete}>
            Delete
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>
      {actions.modalState && (
        <CreateNameModal
          pid={pid}
          mode={actions.modalState.mode}
          anchor={actions.modalState.anchor}
          opened
          onClose={actions.closeModal}
          onCreated={(newId) => {
            actions.closeModal();
            onSelect(newId);
          }}
        />
      )}
      <DeleteNameModal
        pid={pid}
        usage={usage}
        opened={deleteOpen}
        deleting={actions.removing}
        onClose={() => setDeleteOpen(false)}
        onConfirm={(mode, reparentTo) =>
          actions.remove(usage, {
            mode,
            reparentTo,
            onSuccess: () => {
              onAfterDelete?.(usage.id);
              setDeleteOpen(false);
            },
          })
        }
      />
      {actions.moveTarget && (
        <MoveNameModal
          pid={pid}
          usage={actions.moveTarget}
          opened
          onClose={actions.closeMove}
        />
      )}
      {actions.demoteTarget && (
        <DemoteModal
          pid={pid}
          usage={actions.demoteTarget.usage}
          initialStatus={actions.demoteTarget.status}
          opened
          onClose={actions.closeDemote}
        />
      )}
      {actions.promoteTarget && (
        <PromoteModal
          pid={pid}
          usage={actions.promoteTarget}
          opened
          onClose={actions.closePromote}
        />
      )}
      {actions.linkAcceptedTarget && (
        <LinkAcceptedModal
          pid={pid}
          usage={actions.linkAcceptedTarget}
          opened
          onClose={actions.closeLinkAccepted}
        />
      )}
      {actions.clbImportTarget && (
        <ClbImportModal
          projectId={pid}
          focalUsage={actions.clbImportTarget}
          opened
          onClose={actions.closeClbImport}
        />
      )}
      {actions.bulkTarget && (
        <BulkAddModal
          pid={pid}
          target={actions.bulkTarget}
          opened
          onClose={actions.closeBulk}
          onDone={() => onSelect(actions.bulkTarget!.id)}
        />
      )}
    </>
  );
}
