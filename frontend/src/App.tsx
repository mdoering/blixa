import { Layout, Typography } from 'antd';

export default function App() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header>
        <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
          ColDP Editor
        </Typography.Title>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>Loading…</Layout.Content>
    </Layout>
  );
}
