import { Select } from 'antd';
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
      options={(data ?? []).map((p) => ({ value: p.id, label: p.title }))}
      onChange={(id) => navigate(`/projects/${id}/metadata`)}
    />
  );
}
