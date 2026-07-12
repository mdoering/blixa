import { Alert, Button, Card, Divider, PasswordInput, Stack, TextInput } from '@mantine/core';
import BlixaLogo from '../components/BlixaLogo';
import { orcidLoginUrl } from '../api/auth';
import { useLocalLogin } from './useLocalLogin';

export default function LoginPage() {
  const { form, error, submitting, onSubmit } = useLocalLogin();

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
          <form onSubmit={onSubmit}>
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
