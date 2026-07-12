import { Alert, Button, Modal, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { requestJoin } from '../api/join';
import { messageFor } from '../api/client';

export interface JoinRequestModalProps {
  idOrAlias: string;
  opened: boolean;
  onClose: () => void;
}

const ORCID_PATTERN = /^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$/;

interface FormValues {
  orcid: string;
  name: string;
  message: string;
}

// A visitor on a public project page requests to join by submitting their ORCID (unauthenticated
// POST /api/public/projects/{idOrAlias}/join). No account is created and nobody is added as a
// member here -- the project owner reviews pending requests on the Members page and adds them
// manually once they've signed in with that ORCID.
export default function JoinRequestModal({ idOrAlias, opened, onClose }: JoinRequestModalProps) {
  const [sent, setSent] = useState(false);

  const form = useForm<FormValues>({
    initialValues: { orcid: '', name: '', message: '' },
    validate: {
      orcid: (v) => (ORCID_PATTERN.test(v.trim()) ? null : 'Enter a valid ORCID, e.g. 0000-0002-1825-0097'),
    },
  });

  useEffect(() => {
    if (opened) {
      setSent(false);
      form.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      requestJoin(idOrAlias, {
        orcid: values.orcid.trim(),
        name: values.name.trim() || undefined,
        message: values.message.trim() || undefined,
      }),
    onSuccess: () => setSent(true),
    onError: (e) => form.setErrors({ orcid: messageFor(e, 'Could not send the request') }),
  });

  return (
    <Modal opened={opened} onClose={onClose} title="Request to join">
      {sent ? (
        <Stack gap="md">
          <Alert color="green">Request sent — a project owner will review it.</Alert>
          <Button onClick={onClose}>Close</Button>
        </Stack>
      ) : (
        <form onSubmit={form.onSubmit((v) => mutation.mutate(v))}>
          <Stack gap="md">
            <Text size="sm" c="dimmed">
              Enter your ORCID so a project owner can identify and add you.
            </Text>
            <TextInput
              label="ORCID"
              placeholder="0000-0002-1825-0097"
              {...form.getInputProps('orcid')}
            />
            <TextInput label="Name" placeholder="Optional" {...form.getInputProps('name')} />
            <Textarea label="Message" placeholder="Optional" {...form.getInputProps('message')} />
            <Button type="submit" loading={mutation.isPending}>
              Send request
            </Button>
          </Stack>
        </form>
      )}
    </Modal>
  );
}
