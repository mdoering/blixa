import { Alert, Button, Card, Divider, Form, Input, Space } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { localLogin, orcidLoginUrl } from '../api/auth';
import { ApiError } from '../api/client';

export default function LoginPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onFinish(values: { username: string; password: string }) {
    setSubmitting(true);
    setError(null);
    try {
      await localLogin(values.username, values.password);
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      navigate('/', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'Invalid username or password' : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
      <Card title="Sign in to ColDP Editor" style={{ width: 380 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Button block type="primary" href={orcidLoginUrl()}>
            Sign in with ORCID
          </Button>
          <Divider plain>or</Divider>
          {error && <Alert type="error" message={error} showIcon />}
          <Form layout="vertical" onFinish={onFinish} disabled={submitting}>
            <Form.Item label="Username" name="username" rules={[{ required: true }]}>
              <Input autoComplete="username" />
            </Form.Item>
            <Form.Item label="Password" name="password" rules={[{ required: true }]}>
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Button block htmlType="submit" loading={submitting}>
              Sign in
            </Button>
          </Form>
        </Space>
      </Card>
    </div>
  );
}
