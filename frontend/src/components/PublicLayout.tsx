import { AppShell, Anchor, Group } from '@mantine/core';
import { Link, Outlet } from 'react-router-dom';
import BlixaLogo from './BlixaLogo';
import AppFooter from './AppFooter';
import { useMe } from '../auth/useMe';
import { useConfig } from '../api/config';
import { orcidLoginUrl } from '../api/auth';

export default function PublicLayout() {
  const { data: me } = useMe();
  const { data: config } = useConfig();
  return (
    <AppShell header={{ height: 56 }} footer={{ height: 32 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Anchor component={Link} to="/" underline="never" c="inherit">
            <BlixaLogo variant="text" height={22} />
          </Anchor>
          {me ? (
            <Anchor component={Link} to="/projects">
              My projects
            </Anchor>
          ) : config?.orcidEnabled ? (
            // ORCID lives at a backend route, so this is a plain full-page anchor, not a
            // react-router Link. Skips the intermediate /signin page for ORCID users.
            <Anchor href={orcidLoginUrl()}>Sign in</Anchor>
          ) : (
            <Anchor component={Link} to="/signin">
              Sign in
            </Anchor>
          )}
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
      <AppShell.Footer>
        <AppFooter />
      </AppShell.Footer>
    </AppShell>
  );
}
