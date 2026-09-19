import { Navigate, Outlet } from 'react-router-dom';
import { Center, Loader } from '@mantine/core';
import { useMe } from './useMe';
import PendingApprovalPage from './PendingApprovalPage';
import SignInRedirect from './SignInRedirect';
import { readPendingInvite } from '../invite/pendingInvite';

export default function RequireAuth() {
  const { data, isLoading, isError } = useMe();
  if (isLoading)
    return (
      <Center style={{ margin: 48 }}>
        <Loader />
      </Center>
    );
  if (isError || !data) return <SignInRedirect />;
  // Back from the ORCID round-trip with an invitation in hand (InviteAcceptPage stored its token
  // before sending the visitor to sign in; a fresh login always lands on /projects): resume it
  // before any other gate, so a brand-new PENDING account can accept instead of waiting for approval.
  // InviteAcceptPage clears the token once a signed-in user sees it, so this can't loop.
  const pendingInvite = readPendingInvite();
  if (pendingInvite) return <Navigate to={`/invite/${encodeURIComponent(pendingInvite)}`} replace />;
  // Authenticated but not yet ACTIVE (a pending ORCID self-signup or a disabled account): the API
  // 403s every protected route, so show the gate instead of the app chrome.
  if (data.state && data.state !== 'ACTIVE') return <PendingApprovalPage state={data.state} />;
  return <Outlet />;
}
