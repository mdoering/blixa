import { AppShell, Anchor, Group, Menu, UnstyledButton } from '@mantine/core';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useMe } from '../auth/useMe';
import { logout } from '../api/auth';
import ProjectSwitcher from '../projects/ProjectSwitcher';

export default function AppLayout() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function onLogout() {
    try {
      await logout();
    } finally {
      queryClient.clear();
      navigate('/login', { replace: true });
    }
  }

  return (
    <AppShell header={{ height: 60 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" gap="lg">
          <Anchor component={Link} to="/" fw={700} c="white" underline="never">
            ColDP Editor
          </Anchor>
          <ProjectSwitcher />
          <Menu position="bottom-end" withinPortal>
            <Menu.Target>
              <UnstyledButton style={{ marginLeft: 'auto', color: 'white' }}>
                {me?.displayName || me?.username}
              </UnstyledButton>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item onClick={onLogout}>Logout</Menu.Item>
            </Menu.Dropdown>
          </Menu>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
