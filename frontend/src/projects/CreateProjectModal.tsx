import { Form, Input, Modal, Select } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createProject } from '../api/projects';
import type { CreateProjectPayload } from '../api/types';

export const NOM_CODES = ['zoological', 'botanical', 'virus', 'bacterial', 'cultivars', 'phytosociological'];

export default function CreateProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form] = Form.useForm<CreateProjectPayload>();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: (values: CreateProjectPayload) => createProject(values),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      form.resetFields();
      onClose();
      navigate(`/projects/${project.id}/metadata`);
    },
  });

  return (
    <Modal
      open={open}
      title="New project"
      okText="Create"
      confirmLoading={mutation.isPending}
      onOk={() => form.submit()}
      onCancel={onClose}
    >
      <Form form={form} layout="vertical" onFinish={(v) => mutation.mutate(v)}>
        <Form.Item label="Slug" name="slug" rules={[{ required: true }]}>
          <Input placeholder="lepidoptera" />
        </Form.Item>
        <Form.Item label="Title" name="title" rules={[{ required: true }]}>
          <Input placeholder="Lepidoptera of the World" />
        </Form.Item>
        <Form.Item label="Nomenclatural code" name="nomCode">
          <Select allowClear options={NOM_CODES.map((c) => ({ value: c, label: c }))} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
