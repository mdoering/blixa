import { Stack, Text } from '@mantine/core';
import {
  IconAlertTriangle,
  IconBinaryTree2,
  IconBooks,
  IconFolders,
  IconHistory,
  IconList,
  IconLock,
  IconMessages,
  IconSettings,
  IconShieldLock,
  IconUsers,
} from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { joinRequestCount } from '../api/join';
import { getProject } from '../api/projects';
import { useMe } from '../auth/useMe';
import NavItem from './NavItem';

const ICON = 18;

interface Section {
  key: string;
  label: string;
  icon: ReactNode;
  to: string;
  badge?: number;
}

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
  const { data: me } = useMe();
  const go = (to: string) => {
    navigate(to);
    onNavigate?.();
  };

  // Pending join requests only matter -- and are only visible -- to a project owner, so the count
  // query is gated on the role lookup succeeding first. Non-owners and the no-project state issue
  // neither query.
  const { data: project } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => getProject(projectId as number),
    enabled: projectId != null,
  });
  const isOwner = project?.role === 'owner';
  const { data: joinRequestCountValue } = useQuery({
    queryKey: ['joinRequestCount', projectId],
    queryFn: () => joinRequestCount(projectId as number),
    enabled: projectId != null && isOwner,
  });

  const sections: Section[] =
    projectId != null
      ? [
          { key: 'tree', label: 'Tree', icon: <IconBinaryTree2 size={ICON} />, to: `/projects/${projectId}/tree` },
          { key: 'names', label: 'Names', icon: <IconList size={ICON} />, to: `/projects/${projectId}/names` },
          { key: 'references', label: 'References', icon: <IconBooks size={ICON} />, to: `/projects/${projectId}/references` },
          { key: 'issues', label: 'Issues', icon: <IconAlertTriangle size={ICON} />, to: `/projects/${projectId}/issues` },
          { key: 'discussions', label: 'Discussions', icon: <IconMessages size={ICON} />, to: `/projects/${projectId}/discussions` },
          { key: 'history', label: 'History', icon: <IconHistory size={ICON} />, to: `/projects/${projectId}/history` },
          { key: 'activity', label: 'Activity', icon: <IconLock size={ICON} />, to: `/projects/${projectId}/activity` },
          { key: 'project', label: 'Project', icon: <IconSettings size={ICON} />, to: `/projects/${projectId}/metadata` },
          {
            key: 'members',
            label: 'Members',
            icon: <IconUsers size={ICON} />,
            to: `/projects/${projectId}/members`,
            badge: isOwner ? joinRequestCountValue : undefined,
          },
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
      {me?.admin && (
        <NavItem
          icon={<IconShieldLock size={ICON} />}
          label="Users"
          active={pathname.startsWith('/admin/users')}
          collapsed={collapsed}
          onClick={() => go('/admin/users')}
        />
      )}
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
              badge={s.badge}
              onClick={() => go(s.to)}
            />
          ))}
        </>
      )}
    </Stack>
  );
}
