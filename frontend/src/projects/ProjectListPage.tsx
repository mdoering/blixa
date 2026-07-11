import { Anchor, Badge, Button, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { IconFileImport } from '@tabler/icons-react';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listProjects } from '../api/projects';
import CreateProjectModal from './CreateProjectModal';
import ImportProjectModal from './ImportProjectModal';

export default function ProjectListPage() {
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: listProjects });

  return (
    <div>
      <Group justify="space-between" mb="md">
        <Title order={3} m={0}>
          My projects
        </Title>
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconFileImport size={14} />}
            onClick={() => setImporting(true)}
          >
            Import ColDP
          </Button>
          <Button onClick={() => setCreating(true)}>New project</Button>
        </Group>
      </Group>
      {isLoading ? (
        <Loader />
      ) : (data ?? []).length === 0 ? (
        <Text c="dimmed">No projects yet</Text>
      ) : (
        <Stack gap="xs">
          {(data ?? []).map((p) => (
            <Paper key={p.id} withBorder p="sm">
              <Group justify="space-between">
                <Anchor component={Link} to={`/projects/${p.id}/metadata`}>
                  {p.title}
                </Anchor>
                <Badge>{p.role}</Badge>
              </Group>
            </Paper>
          ))}
        </Stack>
      )}
      <CreateProjectModal open={creating} onClose={() => setCreating(false)} />
      <ImportProjectModal opened={importing} onClose={() => setImporting(false)} />
    </div>
  );
}
