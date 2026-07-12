import { Alert, Button, Card, Center, Loader, PasswordInput, Stack, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import BlixaLogo from '../components/BlixaLogo';
import { getConfig } from '../api/config';
import { orcidLoginUrl } from '../api/auth';
import { useLocalLogin } from './useLocalLogin';

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
          <Button fullWidth type="submit" loading={submitting}>
            Sign in
          </Button>
        </Stack>
      </fieldset>
    </form>
  );
}

export default function LoginPage() {
  const { data: config, isLoading } = useQuery({
    queryKey: ['config'],
    queryFn: getConfig,
    staleTime: Infinity,
  });

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card withBorder style={{ width: 380 }}>
        <BlixaLogo variant="header" height={40} style={{ display: 'block', margin: '4px auto 20px' }} />
        {isLoading ? (
          <Center py="md">
            <Loader />
          </Center>
        ) : config?.orcidEnabled ? (
          <Button fullWidth component="a" href={orcidLoginUrl()}>
            Sign in with ORCID
          </Button>
        ) : (
          <LocalLoginForm />
        )}
      </Card>
    </div>
  );
}
