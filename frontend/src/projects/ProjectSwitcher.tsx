import { Select } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMatch, useNavigate } from 'react-router-dom';
import { listProjects } from '../api/projects';

// Header project switcher: reflects the active project (read from the route) and, on change, jumps
// to that project's Tree — the primary editing view.
export default function ProjectSwitcher() {
  const navigate = useNavigate();
  const match = useMatch('/projects/:projectId/*');
  const currentId = match?.params.projectId ?? null;
  const { data } = useQuery({ queryKey: ['projects'], queryFn: listProjects });
  return (
    <Select
      placeholder="Select a project"
      searchable
      style={{ minWidth: 220 }}
      value={currentId}
      data={(data ?? []).map((p) => ({ value: String(p.id), label: p.title }))}
      onChange={(val) => val && navigate(`/projects/${val}/tree`)}
    />
  );
}
