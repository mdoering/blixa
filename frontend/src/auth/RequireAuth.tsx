import { Navigate, Outlet } from 'react-router-dom';
import { Center, Loader } from '@mantine/core';
import { useMe } from './useMe';

export default function RequireAuth() {
  const { data, isLoading, isError } = useMe();
  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  if (isError || !data) return <Navigate to="/login" replace />;
  return <Outlet />;
}
