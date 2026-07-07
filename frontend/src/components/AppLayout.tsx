import { Dropdown, Layout, Typography } from 'antd';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useMe } from '../auth/useMe';
import { logout } from '../api/auth';
import ProjectSwitcher from '../projects/ProjectSwitcher';

export default function AppLayout() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function onLogout() {
    try {
      await logout();
    } finally {
      queryClient.clear();
      navigate('/login', { replace: true });
    }
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
        <Link to="/">
          <Typography.Text strong style={{ color: '#fff' }}>
            ColDP Editor
          </Typography.Text>
        </Link>
        <ProjectSwitcher />
        <div style={{ marginLeft: 'auto' }}>
          <Dropdown
            menu={{ items: [{ key: 'logout', label: 'Logout', onClick: onLogout }] }}
          >
            <Typography.Text style={{ color: '#fff', cursor: 'pointer' }}>
              {me?.displayName || me?.username}
            </Typography.Text>
          </Dropdown>
        </div>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
