import { Alert, Button, Card, Divider, PasswordInput, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import BlixaLogo from '../components/BlixaLogo';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { localLogin, orcidLoginUrl } from '../api/auth';
import { ApiError } from '../api/client';

export default function LoginPage() {
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
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card withBorder style={{ width: 380 }}>
        <BlixaLogo variant="header" height={40} style={{ display: 'block', margin: '4px auto 20px' }} />
        <Stack gap="md">
          <Button fullWidth component="a" href={orcidLoginUrl()}>
            Sign in with ORCID
          </Button>
          <Divider label="or" labelPosition="center" />
          {error && <Alert color="red">{error}</Alert>}
          <form onSubmit={form.onSubmit(onFinish)}>
            <fieldset disabled={submitting} style={{ border: 'none', padding: 0, margin: 0 }}>
              <Stack gap="md">
                <TextInput
                  label="Username"
                  autoComplete="username"
                  {...form.getInputProps('username')}
                />
                <PasswordInput
                  label="Password"
                  autoComplete="current-password"
                  {...form.getInputProps('password')}
                />
                <Button fullWidth type="submit" loading={submitting}>
                  Sign in
                </Button>
              </Stack>
            </fieldset>
          </form>
        </Stack>
      </Card>
    </div>
  );
}
