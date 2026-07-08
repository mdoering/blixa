import { Select } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listProjects } from '../api/projects';

export default function ProjectSwitcher() {
  const navigate = useNavigate();
  const { data } = useQuery({ queryKey: ['projects'], queryFn: listProjects });
  return (
    <Select
      placeholder="Switch project"
      style={{ minWidth: 200 }}
      value={null}
      data={(data ?? []).map((p) => ({ value: String(p.id), label: p.title }))}
      onChange={(val) => val && navigate(`/projects/${val}/metadata`)}
    />
  );
}
