import { Stack, Text } from '@mantine/core';
import {
  IconAlertTriangle,
  IconBinaryTree2,
  IconFolders,
  IconHistory,
  IconList,
  IconSettings,
  IconUsers,
} from '@tabler/icons-react';
import { useLocation, useNavigate } from 'react-router-dom';
import NavItem from './NavItem';

const ICON = 18;

export interface AppSidebarProps {
  projectId: number | null;
  collapsed: boolean;
  onNavigate?: () => void;
}

// Sidebar navigation. Global "Projects" always shows; when a project is active, its built sections
// appear below. Active state is derived from the path. The "Project" item points at the metadata
// page (see plan: metadata is one facet of a growing project-settings section).
export default function AppSidebar({ projectId, collapsed, onNavigate }: AppSidebarProps) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const go = (to: string) => {
    navigate(to);
    onNavigate?.();
  };

  const sections =
    projectId != null
      ? [
          { key: 'tree', label: 'Tree', icon: <IconBinaryTree2 size={ICON} />, to: `/projects/${projectId}/tree` },
          { key: 'names', label: 'Names', icon: <IconList size={ICON} />, to: `/projects/${projectId}/names` },
          { key: 'issues', label: 'Issues', icon: <IconAlertTriangle size={ICON} />, to: `/projects/${projectId}/issues` },
          { key: 'history', label: 'History', icon: <IconHistory size={ICON} />, to: `/projects/${projectId}/history` },
          { key: 'project', label: 'Project', icon: <IconSettings size={ICON} />, to: `/projects/${projectId}/metadata` },
          { key: 'members', label: 'Members', icon: <IconUsers size={ICON} />, to: `/projects/${projectId}/members` },
        ]
      : [];

  return (
    <Stack gap={4}>
      <NavItem
        icon={<IconFolders size={ICON} />}
        label="Projects"
        active={pathname === '/'}
        collapsed={collapsed}
        onClick={() => go('/')}
      />
      {projectId != null && (
        <>
          {!collapsed && (
            <Text size="xs" c="dimmed" fw={600} tt="uppercase" mt="sm" px="xs">
              Project
            </Text>
          )}
          {sections.map((s) => (
            <NavItem
              key={s.key}
              icon={s.icon}
              label={s.label}
              active={pathname.startsWith(s.to)}
              collapsed={collapsed}
              onClick={() => go(s.to)}
            />
          ))}
        </>
      )}
    </Stack>
  );
}
