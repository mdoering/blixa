import { Button, Group, Menu, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconChevronDown, IconPlus, IconTarget } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { readActiveObjectiveId, writeActiveObjectiveId } from '../api/activeObjective';
import { createDiscussion, listDiscussions, type DiscussionPage } from '../api/discussions';

// The prominent top-right selector for the active work objective (an OPEN discussion). While an
// objective is active, this project's subsequent tracked changes + locks attach to it (the api()
// client sends X-Objective-Id). "No objective" is the default and first-class -- picking one is an
// opt-in that never gates ordinary editing. A stale stored id (its discussion closed/gone) is
// reconciled to None so it can't 400 every write.
export default function ActiveObjectiveSelector({ pid }: { pid: number }) {
  const queryClient = useQueryClient();
  const [activeId, setActiveId] = useState<number | null>(() => readActiveObjectiveId(pid));
  const [createOpen, createHandlers] = useDisclosure(false);
  const [newTitle, setNewTitle] = useState('');

  // Re-read when the project changes (the selector is shared across a session).
  useEffect(() => setActiveId(readActiveObjectiveId(pid)), [pid]);

  const { data: objectives } = useQuery({
    queryKey: ['objectives', pid],
    queryFn: () => listDiscussions(pid, { status: 'OPEN', limit: 100, offset: 0 }),
  });
  const openObjectives = objectives?.items ?? [];

  const setActive = (id: number | null) => {
    writeActiveObjectiveId(pid, id);
    setActiveId(id);
  };

  // Reconcile a stale selection: once the OPEN list has loaded, if the stored id isn't among them
  // (the discussion was resolved/deleted), drop it -- otherwise every write would 400.
  useEffect(() => {
    if (!objectives) return;
    if (activeId != null && !openObjectives.some((d) => d.id === activeId)) setActive(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [objectives, activeId]);

  const activeTitle = openObjectives.find((d) => d.id === activeId)?.title ?? null;

  const createMut = useMutation({
    mutationFn: () => createDiscussion(pid, { title: newTitle.trim(), body: null }),
    onSuccess: (created) => {
      // Seed the new objective into the OPEN list *before* activating it, so the reconciliation
      // effect below sees it as valid (otherwise it would clear the freshly-created id during the
      // window before the invalidate-driven refetch lands).
      queryClient.setQueryData<DiscussionPage>(['objectives', pid], (old) =>
        old ? { ...old, items: [created, ...old.items], total: old.total + 1 } : { items: [created], total: 1 },
      );
      setActive(created.id);
      queryClient.invalidateQueries({ queryKey: ['objectives', pid] });
      queryClient.invalidateQueries({ queryKey: ['discussions', pid] });
      setNewTitle('');
      createHandlers.close();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not create objective') }),
  });

  return (
    <>
      <Menu position="bottom-end" withinPortal shadow="md" width={280}>
        <Menu.Target>
          <Button
            variant={activeId != null ? 'light' : 'subtle'}
            color={activeId != null ? 'grape' : 'gray'}
            size="sm"
            leftSection={<IconTarget size={16} />}
            rightSection={<IconChevronDown size={14} />}
            aria-label="Active objective"
          >
            <Text size="sm" truncate maw={180}>
              {activeTitle ?? 'No objective'}
            </Text>
          </Button>
        </Menu.Target>
        <Menu.Dropdown>
          <Menu.Label>Working on</Menu.Label>
          <Menu.Item onClick={() => setActive(null)} disabled={activeId == null}>
            No objective
          </Menu.Item>
          {openObjectives.length > 0 && <Menu.Divider />}
          {openObjectives.map((d) => (
            <Menu.Item
              key={d.id}
              onClick={() => setActive(d.id)}
              fw={d.id === activeId ? 700 : undefined}
            >
              <Text size="sm" truncate maw={240}>
                {d.title}
              </Text>
            </Menu.Item>
          ))}
          <Menu.Divider />
          <Menu.Item leftSection={<IconPlus size={14} />} onClick={createHandlers.open}>
            New objective…
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>

      <Modal opened={createOpen} onClose={createHandlers.close} title="New objective" size="md">
        <Stack>
          <TextInput
            label="Title"
            placeholder="e.g. Revise genus Abies"
            data-autofocus
            value={newTitle}
            onChange={(e) => setNewTitle(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && newTitle.trim()) createMut.mutate();
            }}
          />
          <Text size="xs" c="dimmed">
            Creates an internal discussion you'll work under. Your subsequent changes attach to it.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={createHandlers.close}>
              Cancel
            </Button>
            <Button
              loading={createMut.isPending}
              disabled={!newTitle.trim()}
              onClick={() => createMut.mutate()}
            >
              Create &amp; activate
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
