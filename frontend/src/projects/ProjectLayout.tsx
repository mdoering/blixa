import { Alert, Center, Loader, Tabs, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Outlet, useNavigate, useParams, useLocation } from 'react-router-dom';
import { getProject } from '../api/projects';

export default function ProjectLayout() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const navigate = useNavigate();
  const location = useLocation();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['project', id],
    queryFn: () => getProject(id),
    enabled: Number.isFinite(id),
  });

  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  if (isError || !data) return <Alert color="red">Project not found</Alert>;

  const active = location.pathname.endsWith('/members')
    ? 'members'
    : location.pathname.endsWith('/tree')
      ? 'tree'
      : 'metadata';
  return (
    <div>
      <Title order={3}>{data.title}</Title>
      <Tabs value={active} onChange={(v) => v && navigate(`/projects/${id}/${v}`)}>
        <Tabs.List>
          <Tabs.Tab value="tree">Tree</Tabs.Tab>
          <Tabs.Tab value="metadata">Metadata</Tabs.Tab>
          <Tabs.Tab value="members">Members</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      <Outlet />
    </div>
  );
}
