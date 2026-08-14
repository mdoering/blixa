import { Outlet } from 'react-router-dom';
import { Center, Loader } from '@mantine/core';
import { useMe } from './useMe';
import PendingApprovalPage from './PendingApprovalPage';
import SignInRedirect from './SignInRedirect';

export default function RequireAuth() {
  const { data, isLoading, isError } = useMe();
  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  if (isError || !data) return <SignInRedirect />;
  // Authenticated but not yet ACTIVE (a pending ORCID self-signup or a disabled account): the API
  // 403s every protected route, so show the gate instead of the app chrome.
  if (data.state && data.state !== 'ACTIVE') return <PendingApprovalPage state={data.state} />;
  return <Outlet />;
}
