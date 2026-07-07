import { Button, Col, Form, Input, Row, Select, App as AntApp } from 'antd';
import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getProject, updateMetadata } from '../api/projects';
import { messageFor } from '../api/client';
import type { UpdateMetadataPayload } from '../api/types';
import { NOM_CODES } from './CreateProjectModal';

export default function ProjectMetadataPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  const [form] = Form.useForm<UpdateMetadataPayload>();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const { data } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const canEdit = data ? ['owner', 'editor'].includes(data.role) : false;

  useEffect(() => {
    if (data) {
      const values = Object.fromEntries(
        Object.entries(data).map(([k, v]) => [k, v ?? undefined]),
      ) as UpdateMetadataPayload;
      form.setFieldsValue(values);
    }
  }, [data, form]);

  const mutation = useMutation({
    mutationFn: (values: UpdateMetadataPayload) => updateMetadata(id, values),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['project', id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      message.success('Saved');
    },
    onError: (e) => message.error(messageFor(e, 'Save failed')),
  });

  return (
    <Form form={form} layout="vertical" onFinish={(v) => mutation.mutate(v)} style={{ maxWidth: 720 }} disabled={!canEdit}>
      <Form.Item label="Title" name="title" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item label="Alias" name="alias"><Input /></Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item label="Nomenclatural code" name="nomCode">
            <Select allowClear options={NOM_CODES.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
        </Col>
      </Row>
      <Form.Item label="Description" name="description"><Input.TextArea rows={3} /></Form.Item>
      <Row gutter={16}>
        <Col span={8}><Form.Item label="License" name="license"><Input /></Form.Item></Col>
        <Col span={8}><Form.Item label="Version" name="version"><Input /></Form.Item></Col>
        <Col span={8}>
          <Form.Item
            label="Issued"
            name="issued"
            rules={[{ pattern: /^\d{4}-\d{2}-\d{2}$/, message: 'Use YYYY-MM-DD' }]}
          >
            <Input placeholder="YYYY-MM-DD" />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={16}>
        <Col span={12}><Form.Item label="Geographic scope" name="geographicScope"><Input /></Form.Item></Col>
        <Col span={12}><Form.Item label="Taxonomic scope" name="taxonomicScope"><Input /></Form.Item></Col>
      </Row>
      <Form.Item label="DOI" name="doi"><Input /></Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending} disabled={!canEdit}>
        Save
      </Button>
    </Form>
  );
}
