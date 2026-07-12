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
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getPublicProjects } from '../api/public';
import { getConfig } from '../api/config';
import { useMe } from '../auth/useMe';
import { localLogin, orcidLoginUrl } from '../api/auth';
import { ApiError } from '../api/client';

function LocalLoginForm() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v ? null : 'Required'),
      password: (v) => (v ? null : 'Required'),
    },
  });

  async function onFinish(values: { username: string; password: string }) {
    setSubmitting(true);
    setError(null);
    try {
      await localLogin(values.username, values.password);
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      navigate('/projects', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'Invalid username or password' : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={form.onSubmit(onFinish)}>
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
