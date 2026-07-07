import { Button, Form, Input, Popconfirm, Select, Table, App as AntApp } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, listMembers, removeMember, setMember } from '../api/projects';
import type { Member, Role } from '../api/types';

const ROLES: Role[] = ['owner', 'editor', 'reviewer', 'viewer'];

export default function MembersPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<{ username: string; role: Role }>();

  const { data: project } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const { data: members, isLoading } = useQuery({ queryKey: ['members', id], queryFn: () => listMembers(id) });
  const canManage = project?.role === 'owner';

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['members', id] });

  const setMut = useMutation({
    mutationFn: ({ username, role }: { username: string; role: Role }) => setMember(id, username, role),
    onSuccess: async () => { await invalidate(); form.resetFields(); },
    onError: () => message.error('Could not update member'),
  });
  const removeMut = useMutation({
    mutationFn: (userId: number) => removeMember(id, userId),
    onSuccess: invalidate,
    onError: () => message.error('Could not remove member'),
  });

  return (
    <div>
      {canManage && (
        <Form form={form} layout="inline" style={{ marginBottom: 16 }} onFinish={(v) => setMut.mutate(v)}>
          <Form.Item name="username" rules={[{ required: true }]}>
            <Input placeholder="username" />
          </Form.Item>
          <Form.Item name="role" initialValue="editor" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={ROLES.map((r) => ({ value: r, label: r }))} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={setMut.isPending}>
            Add / update
          </Button>
        </Form>
      )}
      <Table<Member>
        rowKey="userId"
        loading={isLoading}
        dataSource={members ?? []}
        pagination={false}
        columns={[
          { title: 'Username', dataIndex: 'username' },
          {
            title: 'Role',
            dataIndex: 'role',
            render: (role: Role, m) =>
              canManage ? (
                <Select
                  value={role}
                  style={{ width: 140 }}
                  options={ROLES.map((r) => ({ value: r, label: r }))}
                  onChange={(r) => setMut.mutate({ username: m.username, role: r })}
                />
              ) : (
                role
              ),
          },
          {
            title: '',
            key: 'actions',
            render: (_, m) =>
              canManage ? (
                <Popconfirm title="Remove member?" onConfirm={() => removeMut.mutate(m.userId)}>
                  <Button danger size="small">Remove</Button>
                </Popconfirm>
              ) : null,
          },
        ]}
      />
    </div>
  );
}
