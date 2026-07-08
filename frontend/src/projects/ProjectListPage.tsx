import { Anchor, Badge, Button, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listProjects } from '../api/projects';
import CreateProjectModal from './CreateProjectModal';

export default function ProjectListPage() {
  const [creating, setCreating] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: listProjects });

  return (
    <div>
      <Group justify="space-between" mb="md">
        <Title order={3} m={0}>
          My projects
        </Title>
        <Button onClick={() => setCreating(true)}>New project</Button>
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
    </div>
  );
}
