import { Alert, Spin, Tabs, Typography } from 'antd';
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

  if (isLoading) return <Spin style={{ margin: 48 }} />;
  if (isError || !data) return <Alert type="error" showIcon message="Project not found" />;

  const active = location.pathname.endsWith('/members') ? 'members' : 'metadata';
  return (
    <div>
      <Typography.Title level={3}>{data.title}</Typography.Title>
      <Tabs
        activeKey={active}
        onChange={(k) => navigate(`/projects/${id}/${k}`)}
        items={[
          { key: 'metadata', label: 'Metadata' },
          { key: 'members', label: 'Members' },
        ]}
      />
      <Outlet />
    </div>
  );
}
