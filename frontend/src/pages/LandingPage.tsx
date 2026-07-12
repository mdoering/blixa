import {
  Alert,
  Anchor,
  Button,
  Container,
  Divider,
  Group,
  Loader,
  Paper,
  PasswordInput,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getPublicProjects } from '../api/public';
import { getConfig } from '../api/config';
import { useMe } from '../auth/useMe';
import { orcidLoginUrl } from '../api/auth';
import { useLocalLogin } from '../auth/useLocalLogin';

function LocalLoginForm() {
  const { form, error, submitting, onSubmit } = useLocalLogin();

  return (
    <form onSubmit={onSubmit}>
      <fieldset disabled={submitting} style={{ border: 'none', padding: 0, margin: 0 }}>
        <Stack gap="md">
          {error && <Alert color="red">{error}</Alert>}
          <TextInput label="Username" autoComplete="username" {...form.getInputProps('username')} />
          <PasswordInput
            label="Password"
            autoComplete="current-password"
            {...form.getInputProps('password')}
          />
          <Button type="submit" loading={submitting}>
            Sign in
          </Button>
        </Stack>
      </fieldset>
    </form>
  );
}

function LoginArea() {
  const { data: me } = useMe();
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: getConfig, staleTime: Infinity });

  if (me) {
    return (
      <Anchor component={Link} to="/projects">
        My projects
      </Anchor>
    );
  }
  if (config?.orcidEnabled) {
    return (
      <Button component="a" href={orcidLoginUrl()}>
        Sign in with ORCID
      </Button>
    );
  }
  if (config) {
    return <LocalLoginForm />;
  }
  return null;
}

export default function LandingPage() {
  const { data: projects, isLoading } = useQuery({ queryKey: ['publicProjects'], queryFn: getPublicProjects });

  return (
    <Container size="md" py="xl">
      <Stack gap="xl">
        <Stack gap="xs">
          <Title order={1}>Blixa</Title>
          <Text c="dimmed">
            Blixa is a lightweight editor for building and releasing taxonomic checklists in the
            Catalogue of Life Data Package (ColDP) format.
          </Text>
        </Stack>

        <Paper withBorder p="md">
          <Title order={4} mb="sm">
            Sign in
          </Title>
          <LoginArea />
        </Paper>

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
