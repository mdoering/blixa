import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useForm } from '@mantine/form';
import { localLogin } from '../api/auth';
import { ApiError } from '../api/client';

export function useLocalLogin() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v ? null : 'Required'),
      password: (v) => (v ? null : 'Required'),
    },
  });

  async function onFinish(values: { username: string; password: string }) {
    setSubmitting(true);
    setError(null);
    try {
      await localLogin(values.username, values.password);
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      navigate('/projects', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'Invalid username or password' : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  const onSubmit = form.onSubmit(onFinish);

  return { form, error, submitting, onSubmit };
}
