import { Button, Modal, Select, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createProject } from '../api/projects';
import { messageFor } from '../api/client';
import type { CreateProjectPayload } from '../api/types';

export const NOM_CODES = ['zoological', 'botanical', 'virus', 'bacterial', 'cultivars', 'phytosociological'];

export default function CreateProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const form = useForm<CreateProjectPayload>({
    initialValues: { title: '', nomCode: undefined },
    validate: {
      title: (v) => (v ? null : 'Required'),
    },
  });
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: (values: CreateProjectPayload) => createProject(values),
    onSuccess: async (project) => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      form.reset();
      onClose();
      navigate(`/projects/${project.id}/metadata`);
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Could not create project') }),
  });

  return (
    <Modal opened={open} onClose={onClose} title="New project">
      <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
        <Stack gap="md">
          <TextInput
            label="Title"
            placeholder="Lepidoptera of the World"
            {...form.getInputProps('title')}
          />
          <Select
            label="Nomenclatural code"
            clearable
            data={NOM_CODES.map((c) => ({ value: c, label: c }))}
            {...form.getInputProps('nomCode')}
          />
          <Button type="submit" loading={mutation.isPending}>
            Create
          </Button>
        </Stack>
      </form>
    </Modal>
  );
}
