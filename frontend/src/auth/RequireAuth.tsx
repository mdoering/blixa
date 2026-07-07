import { Navigate, Outlet } from 'react-router-dom';
import { Spin } from 'antd';
import { useMe } from './useMe';

export default function RequireAuth() {
  const { data, isLoading, isError } = useMe();
  if (isLoading) return <Spin style={{ margin: 48 }} />;
  if (isError || !data) return <Navigate to="/login" replace />;
  return <Outlet />;
}
