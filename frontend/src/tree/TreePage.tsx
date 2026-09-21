import { Box, Button, Group, Switch, Text } from '@mantine/core';
import { IconDownload, IconPlus } from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getProject } from '../api/projects';
import { getUsage } from '../api/usages';
import { getPath, subtreeTxtreeUrl } from '../api/tree';
import CollapsibleSplit from '../components/CollapsibleSplit';
import CreateNameModal from '../names/CreateNameModal';
import { useNameActions } from '../names/useNameActions';
import ClassificationTree from './ClassificationTree';
import TaxonDetail from './TaxonDetail';
import { useSelectedUsage } from './useSelectedUsage';

export default function TreePage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  // In the URL (?usage=<id>): shareable, and kept when switching to/from the Names search.
  const [selectedId, setSelectedId] = useSelectedUsage();
  // The tree defaults to the accepted backbone only; this toggles the provisional (UNASSESSED)
  // layer on so a curator can browse and review it.
  const [showUnassessed, setShowUnassessed] = useState(false);

  // Same source as TaxonDetail's canEdit: the project's role for the current user. Shared
  // queryKey (['project', pid]) means this dedups with TaxonDetail's own fetch below.
  const { data: project } = useQuery({ queryKey: ['project', pid], queryFn: () => getProject(pid) });
  const canEdit = project ? ['owner', 'editor'].includes(project.role) : false;

  // Only needed to show "Child of <name>" in the create modal when a node is selected; shares
  // its queryKey with TaxonDetail's usage fetch, so this doesn't add an extra request.
  const { data: selectedUsage } = useQuery({
    queryKey: ['usage', pid, selectedId],
    queryFn: () => getUsage(pid, selectedId as number),
    enabled: selectedId != null,
  });

  // Reveal the selection in the lazy tree: expand every ancestor on its path. A synonym/misapplied
  // name isn't a tree node, so its (primary) accepted name is revealed instead. Same ['treePath']
  // key as ClassificationBar, so it usually comes from cache.
  const isSynonym = selectedUsage?.status === 'SYNONYM' || selectedUsage?.status === 'MISAPPLIED';
  const anchorId = selectedUsage
    ? isSynonym
      ? selectedUsage.acceptedParentIds?.[0] ?? null
      : selectedUsage.id
    : null;
  const { data: anchorPath } = useQuery({
    queryKey: ['treePath', pid, anchorId],
    queryFn: () => getPath(pid, anchorId as number),
    enabled: anchorId != null,
  });
  // The path runs root..anchor; the anchor itself needn't be opened.
  const revealIds = useMemo(
    () => new Set((anchorPath ?? []).map((n) => n.id).filter((id) => id !== anchorId)),
    [anchorPath, anchorId],
  );
  // A provisional (UNASSESSED) selection is hidden in the default backbone-only tree -- show that layer.
  useEffect(() => {
    if (selectedUsage?.status === 'UNASSESSED') setShowUnassessed(true);
  }, [selectedUsage?.status]);

  const actions = useNameActions(pid);

  const handleNewName = () => {
    if (selectedUsage) {
      actions.createChild({ id: selectedUsage.id, scientificName: selectedUsage.scientificName });
    } else {
      actions.createRoot();
    }
  };

  return (
    <Box>
      <Group justify="space-between" mb="md">
        <Text fw={600}>Classification tree</Text>
        <Group gap="md">
          <Switch
            size="sm"
            label="Show unassessed"
            checked={showUnassessed}
            onChange={(e) => setShowUnassessed(e.currentTarget.checked)}
          />
          {canEdit && (
            <Button leftSection={<IconPlus size={14} />} size="xs" onClick={handleNewName}>
              New name
            </Button>
          )}
        </Group>
      </Group>
      <CollapsibleSplit
        storageKey="coldp:split:tree:collapsed"
        leftPercent={42}
        leftStyle={{ maxHeight: '75vh', overflowY: 'auto' }}
        left={
          <ClassificationTree
            pid={pid}
            selectedId={selectedId}
            onSelect={setSelectedId}
            canEdit={canEdit}
            includeUnassessed={showUnassessed}
            revealIds={revealIds}
            onAfterDelete={(id) => {
              if (id === selectedId) setSelectedId(null);
            }}
          />
        }
        right={
          selectedId == null ? (
            <Text c="dimmed">Select a taxon in the tree to see its details.</Text>
          ) : (
            <Box>
              <Group justify="flex-end" wrap="nowrap" align="flex-start" gap="sm">
                <Button
                  component="a"
                  href={subtreeTxtreeUrl(pid, selectedId)}
                  download
                  variant="light"
                  size="xs"
                  leftSection={<IconDownload size={14} />}
                  style={{ flexShrink: 0 }}
                >
                  Download subtree
                </Button>
              </Group>
              <Box mt="md">
                <TaxonDetail pid={pid} usageId={selectedId} onNavigate={setSelectedId} />
              </Box>
            </Box>
          )
        }
      />
      {actions.modalState && (
        <CreateNameModal
          pid={pid}
          mode={actions.modalState.mode}
          anchor={actions.modalState.anchor}
          opened
          onClose={actions.closeModal}
          onCreated={(newId) => {
            actions.closeModal();
            setSelectedId(newId);
          }}
        />
      )}
    </Box>
  );
}
