import { Alert, Center, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Outlet, useParams } from 'react-router-dom';
import { getProject } from '../api/projects';

// Thin guard for the project routes: loads the project (shared ['project', id] key so section pages
// dedupe), shows a loader / not-found, and otherwise renders the active section via <Outlet/>. The
// section navigation and project title now live in the shell (sidebar + header switcher).
export default function ProjectLayout() {
  const { projectId } = useParams();
  const id = Number(projectId);
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
  return <Outlet />;
}
