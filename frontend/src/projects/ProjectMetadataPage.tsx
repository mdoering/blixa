import { Button, SimpleGrid, Stack, Select, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
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
  const queryClient = useQueryClient();

  const form = useForm<UpdateMetadataPayload>({
    initialValues: {
      title: '',
      alias: undefined,
      description: undefined,
      nomCode: undefined,
      license: undefined,
      version: undefined,
      issued: undefined,
      geographicScope: undefined,
      taxonomicScope: undefined,
      doi: undefined,
    },
    validate: {
      title: (v) => (v ? null : 'Required'),
      issued: (v) => (!v || /^\d{4}-\d{2}-\d{2}$/.test(v) ? null : 'Use YYYY-MM-DD'),
    },
  });

  const { data } = useQuery({ queryKey: ['project', id], queryFn: () => getProject(id) });
  const canEdit = data ? ['owner', 'editor'].includes(data.role) : false;

  useEffect(() => {
    if (data) {
      const values = Object.fromEntries(
        Object.entries(data).map(([k, v]) => [k, v ?? undefined]),
      ) as UpdateMetadataPayload;
      form.setValues(values);
      form.resetDirty(values);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  const mutation = useMutation({
    mutationFn: (values: UpdateMetadataPayload) => updateMetadata(id, values),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['project', id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      notifications.show({ message: 'Saved' });
    },
    onError: (e) => notifications.show({ color: 'red', message: messageFor(e, 'Save failed') }),
  });

  return (
    <form onSubmit={form.onSubmit((v) => mutation.mutate(v))} style={{ maxWidth: 720 }}>
      <fieldset disabled={!canEdit} style={{ border: 'none', padding: 0, margin: 0 }}>
        <Stack gap="md">
          <TextInput label="Title" {...form.getInputProps('title')} />
          <SimpleGrid cols={2}>
            <TextInput label="Alias" {...form.getInputProps('alias')} />
            <Select
              label="Nomenclatural code"
              clearable
              data={NOM_CODES.map((c) => ({ value: c, label: c }))}
              {...form.getInputProps('nomCode')}
            />
          </SimpleGrid>
          <Textarea label="Description" rows={3} {...form.getInputProps('description')} />
          <SimpleGrid cols={3}>
            <TextInput label="License" {...form.getInputProps('license')} />
            <TextInput label="Version" {...form.getInputProps('version')} />
            <TextInput label="Issued" placeholder="YYYY-MM-DD" {...form.getInputProps('issued')} />
          </SimpleGrid>
          <SimpleGrid cols={2}>
            <TextInput label="Geographic scope" {...form.getInputProps('geographicScope')} />
            <TextInput label="Taxonomic scope" {...form.getInputProps('taxonomicScope')} />
          </SimpleGrid>
          <TextInput label="DOI" {...form.getInputProps('doi')} />
          <Button type="submit" loading={mutation.isPending} disabled={!canEdit}>
            Save
          </Button>
        </Stack>
      </fieldset>
    </form>
  );
}
