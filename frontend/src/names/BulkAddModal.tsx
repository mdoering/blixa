import { Alert, Badge, Button, FileButton, Group, Modal, SegmentedControl, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../api/client';
import { insertBulk, previewBulk, type BulkPreview, type BulkPreviewNode } from '../api/bulk';

export interface BulkAddTarget {
  id: number;
  scientificName: string | null;
}

export interface BulkAddModalProps {
  pid: number;
  target: BulkAddTarget;
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}

function NodeView({ node, depth }: { node: BulkPreviewNode; depth: number }) {
  return (
    <div style={{ paddingLeft: depth * 16 }}>
      <Group gap={6}>
        <Text size="sm" fs="italic">{node.name}</Text>
        <Badge size="xs" variant="light" color={node.status === 'SYNONYM' ? 'gray' : 'blue'}>
          {node.rank}
        </Badge>
        {node.extinct && <Badge size="xs" variant="light" color="dark">†</Badge>}
        {node.duplicate && <Badge size="xs" variant="light" color="yellow">exists</Badge>}
      </Group>
      {node.synonyms.map((s, i) => (
        <div key={`s${i}`} style={{ paddingLeft: (depth + 1) * 16 }}>
          <Text size="sm" c="dimmed">= {s.name}</Text>
        </div>
      ))}
      {node.children.map((c, i) => <NodeView key={`c${i}`} node={c} depth={depth + 1} />)}
    </div>
  );
}

// Bulk-add a text-tree (or plain list) under `target`. Preview -> confirm: previewBulk parses and
// validates server-side; the parsed tree renders here; insertBulk commits everything in one
// transaction. Duplicates are shown but inserted anyway.
export default function BulkAddModal({ pid, target, opened, onClose, onDone }: BulkAddModalProps) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<'children' | 'synonyms'>('children');
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<BulkPreview | null>(null);

  useEffect(() => {
    if (opened) {
      setMode('children');
      setText('');
      setPreview(null);
    }
  }, [opened]);

  const previewMut = useMutation({
    mutationFn: () => previewBulk(pid, { targetId: target.id, mode, text }),
    onSuccess: (p) => setPreview(p),
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Preview failed') }),
  });

  const insertMut = useMutation({
    mutationFn: () => insertBulk(pid, { targetId: target.id, mode, text }),
    onSuccess: async (res) => {
      await queryClient.invalidateQueries({ queryKey: ['treeRoots', pid] });
      await queryClient.invalidateQueries({ queryKey: ['treeChildren', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSearch', pid] });
      await queryClient.invalidateQueries({ queryKey: ['usageSynonyms', pid] });
      notifications.show({ message: `Inserted ${res.created} names` });
      onDone();
      onClose();
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Insert failed') }),
  });

  const readFile = (file: File | null) => {
    if (!file) return;
    file.text().then((t) => { setText(t); setPreview(null); });
  };

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={
      <Text fw={600}>Bulk add under <Text span fs="italic" inherit>{target.scientificName ?? '—'}</Text></Text>
    }>
      <Stack gap="md">
        <SegmentedControl
          value={mode}
          onChange={(v) => { setMode(v as 'children' | 'synonyms'); setPreview(null); }}
          data={[{ label: 'As accepted children', value: 'children' },
                 { label: 'As synonyms of target', value: 'synonyms' }]}
        />
        <Textarea
          label="Names"
          description="One name per line. [rank] sets rank; 2-space indent nests children; = marks a synonym; † marks extinct."
          autosize minRows={6} maxRows={16}
          value={text}
          onChange={(e) => { setText(e.currentTarget.value); setPreview(null); }}
        />
        <Group>
          <FileButton onChange={readFile} accept=".txt,.tsv,.txtree,.tree">
            {(props) => <Button variant="default" size="xs" {...props}>Upload file…</Button>}
          </FileButton>
          <Button variant="light" size="xs" loading={previewMut.isPending}
            disabled={!text.trim()} onClick={() => previewMut.mutate()}>
            Preview
          </Button>
        </Group>

        {preview && !preview.valid && (
          <Alert color="red" title="Cannot parse">{preview.error}</Alert>
        )}
        {preview && preview.valid && (
          <Stack gap="xs">
            <Group gap="xs">
              <Badge variant="light" color="blue">accepted {preview.accepted}</Badge>
              <Badge variant="light" color="gray">synonyms {preview.synonyms}</Badge>
              {preview.duplicates > 0 && (
                <Badge variant="light" color="yellow">already exist {preview.duplicates}</Badge>
              )}
            </Group>
            <div style={{ maxHeight: 300, overflowY: 'auto' }}>
              {preview.nodes.map((n, i) => <NodeView key={i} node={n} depth={0} />)}
            </div>
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>Cancel</Button>
              <Button loading={insertMut.isPending} onClick={() => insertMut.mutate()}>
                Insert {preview.total} names
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}
