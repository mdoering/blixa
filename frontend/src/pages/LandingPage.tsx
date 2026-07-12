import { Anchor, Button, Container, Divider, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getPublicProjects } from '../api/public';
import { useMe } from '../auth/useMe';
import BlixaLogo from '../components/BlixaLogo';

export default function LandingPage() {
  const { data: me } = useMe();
  const { data: projects, isLoading } = useQuery({ queryKey: ['publicProjects'], queryFn: getPublicProjects });

  return (
    <Container size="md" py="xl">
      <Stack gap="xl">
        <Stack gap="xs" align="flex-start">
          {/* Brand lockup: the square logo mark on the left edge + a large wordmark. */}
          <Group gap="xl" align="center" wrap="nowrap">
            <BlixaLogo variant="logo" height={104} />
            <BlixaLogo variant="text" height={128} />
          </Group>
          <Text c="dimmed">
            Blixa is a lightweight editor for building and releasing taxonomic checklists in the
            Catalogue of Life Data Package (ColDP) format.
          </Text>
          {me ? (
            <Button component={Link} to="/projects">
              My projects
            </Button>
          ) : (
            <Button component={Link} to="/login">
              Log in
            </Button>
          )}
        </Stack>

        <Stack gap="sm">
          <Title order={3}>Public projects</Title>
          {isLoading ? (
            <Loader />
          ) : (projects ?? []).length === 0 ? (
            <Text c="dimmed">No public projects yet.</Text>
          ) : (
            <Stack gap="xs">
              {(projects ?? []).map((p) => (
                <Paper key={p.id} withBorder p="sm">
                  <Group justify="space-between" align="flex-start">
                    <div>
                      <Anchor component={Link} to={`/p/${p.id}`} fw={500}>
                        {p.title}
                      </Anchor>
                      {p.description && (
                        <Text size="sm" c="dimmed">
                          {p.description}
                        </Text>
                      )}
                    </div>
                    <Stack gap={0} align="flex-end">
                      {p.latestVersion && <Text size="sm">v{p.latestVersion}</Text>}
                      {p.nameUsageCount != null && (
                        <Text size="xs" c="dimmed">
                          {p.nameUsageCount.toLocaleString()} names
                        </Text>
                      )}
                    </Stack>
                  </Group>
                </Paper>
              ))}
            </Stack>
          )}
        </Stack>
      </Stack>
      <Divider mt="xl" />
    </Container>
  );
}
