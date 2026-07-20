import { Anchor, AppShell, Burger, Group, Menu, UnstyledButton } from '@mantine/core';
import { useDisclosure, useLocalStorage } from '@mantine/hooks';
import { IconLogout, IconUser } from '@tabler/icons-react';
import { Link, Outlet, useMatch, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useMe } from '../auth/useMe';
import { logout } from '../api/auth';
import CurrentProjectName from './CurrentProjectName';
import AppSidebar from './AppSidebar';
import AppFooter from './AppFooter';
import AccountModal from './AccountModal';
import BlixaLogo from './BlixaLogo';
import ColorSchemeToggle from './ColorSchemeToggle';
import UserAvatar from './UserAvatar';

export default function AppLayout() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [mobileOpened, { toggle: toggleMobile, close: closeMobile }] = useDisclosure(false);
  const [accountOpened, { open: openAccount, close: closeAccount }] = useDisclosure(false);
  const [collapsed, setCollapsed] = useLocalStorage<boolean>({
    key: 'coldp-nav-collapsed',
    defaultValue: false,
    getInitialValueInEffect: false,
  });

  const projectMatch = useMatch('/projects/:projectId/*');
  const projectId = projectMatch ? Number(projectMatch.params.projectId) : null;

  async function onLogout() {
    try {
      await logout();
    } finally {
      queryClient.clear();
      navigate('/signin', { replace: true });
    }
  }

  return (
    <AppShell
      header={{ height: 56 }}
      footer={{ height: 32 }}
      navbar={{ width: collapsed ? 68 : 240, breakpoint: 'sm', collapsed: { mobile: !mobileOpened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" gap="sm" wrap="nowrap">
          <Burger
            opened={mobileOpened}
            onClick={toggleMobile}
            hiddenFrom="sm"
            size="sm"
            aria-label="Open navigation"
          />
          <Burger
            opened={!collapsed}
            onClick={() => setCollapsed((c) => !c)}
            visibleFrom="sm"
            size="sm"
            aria-label="Collapse navigation"
          />
          {/* Brand slot (upper-left) — the full Blixa lockup, masked to inherit the header colour. */}
          <Anchor component={Link} to="/projects" underline="never" c="inherit">
            <BlixaLogo variant="header" height={28} />
          </Anchor>
          {/* Read-only current-project context; picking a project happens on the Projects list. */}
          <CurrentProjectName projectId={projectId} />
          <Group ml="auto" gap="sm" wrap="nowrap">
            <ColorSchemeToggle />
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton>
                  <Group gap="xs" wrap="nowrap">
                    <UserAvatar name={me?.displayName || me?.username} size="sm" />
                    <span>{me?.displayName || me?.username}</span>
                  </Group>
                </UnstyledButton>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item leftSection={<IconUser size={14} />} onClick={openAccount}>
                  Account
                </Menu.Item>
                <Menu.Item leftSection={<IconLogout size={14} />} onClick={onLogout}>
                  Logout
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
            <AccountModal opened={accountOpened} onClose={closeAccount} />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        <AppSidebar projectId={projectId} collapsed={collapsed} onNavigate={closeMobile} />
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <AppShell.Footer>
        <AppFooter />
      </AppShell.Footer>
    </AppShell>
  );
}
