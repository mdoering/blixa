import { ActionIcon, Menu } from '@mantine/core';
import { modals } from '@mantine/modals';
import { IconDots, IconPlus, IconTrash } from '@tabler/icons-react';
import CreateNameModal from './CreateNameModal';
import { useNameActions } from './useNameActions';

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
}: NameActionMenuProps) {
  const actions = useNameActions(pid);

  if (!canEdit) return null;

  const confirmDelete = () => {
    modals.openConfirmModal({
      title: 'Delete this name?',
      children: `This permanently deletes "${usage.scientificName ?? 'this name'}".`,
      labels: { confirm: 'Delete', cancel: 'Cancel' },
      confirmProps: { color: 'red' },
      onConfirm: () => actions.remove(usage),
    });
  };

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
            <IconDots size={14} />
          </ActionIcon>
        </Menu.Target>
        <Menu.Dropdown onClick={(e) => e.stopPropagation()}>
          <Menu.Item leftSection={<IconPlus size={14} />} onClick={() => actions.createChild(usage)}>
            Add child
          </Menu.Item>
          <Menu.Item
            leftSection={<IconPlus size={14} />}
            onClick={() => actions.createSynonymOf(usage)}
          >
            Add synonym
          </Menu.Item>
          <Menu.Label>Change status</Menu.Label>
          {STATUS_OPTIONS.map((s) => (
            <Menu.Item key={s.value} onClick={() => actions.changeStatus(usage, s.value)}>
              {s.label}
            </Menu.Item>
          ))}
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
    </>
  );
}
