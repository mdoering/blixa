import { AppShell, Anchor, Group } from '@mantine/core';
import { Link, Outlet } from 'react-router-dom';
import BlixaLogo from './BlixaLogo';
import { useMe } from '../auth/useMe';

export default function PublicLayout() {
  const { data: me } = useMe();
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Anchor component={Link} to="/" underline="never" c="inherit">
            <BlixaLogo variant="header" height={28} />
          </Anchor>
          {me ? (
            <Anchor component={Link} to="/projects">
              My projects
            </Anchor>
          ) : (
            <Anchor component={Link} to="/login">
              Sign in
            </Anchor>
          )}
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
