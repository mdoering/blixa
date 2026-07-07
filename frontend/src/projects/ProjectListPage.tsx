import { Button, List, Tag, Typography } from 'antd';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listProjects } from '../api/projects';
import CreateProjectModal from './CreateProjectModal';

export default function ProjectListPage() {
  const [creating, setCreating] = useState(false);
  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: listProjects });

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          My projects
        </Typography.Title>
        <Button type="primary" onClick={() => setCreating(true)}>
          New project
        </Button>
      </div>
      <List
        loading={isLoading}
        bordered
        dataSource={data ?? []}
        locale={{ emptyText: 'No projects yet' }}
        renderItem={(p) => (
          <List.Item actions={[<Tag key="role">{p.role}</Tag>]}>
            <Link to={`/projects/${p.id}/metadata`}>{p.title}</Link>
          </List.Item>
        )}
      />
      <CreateProjectModal open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}
