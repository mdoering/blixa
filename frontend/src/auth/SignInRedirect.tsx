import { useEffect } from 'react';
import { Center, Loader } from '@mantine/core';
import { Navigate } from 'react-router-dom';
import { useConfig } from '../api/config';
import { orcidLoginUrl } from '../api/auth';

// Where an unauthenticated visitor is sent when they need to sign in. When ORCID is enabled we jump
// straight to the backend OAuth route (a full-page navigation); otherwise we fall back to the local
// /signin form. Note: this is only used for the *explicit* sign-in path (the header link and the
// RequireAuth redirect) — /signin itself never auto-redirects, so logout does not re-login via the
// still-active ORCID SSO session.
export default function SignInRedirect() {
  const { data: config, isLoading } = useConfig();
  const orcidEnabled = config?.orcidEnabled ?? false;

  useEffect(() => {
    if (!isLoading && orcidEnabled) {
      window.location.assign(orcidLoginUrl());
    }
  }, [isLoading, orcidEnabled]);

  if (!isLoading && !orcidEnabled) {
    return <Navigate to="/signin" replace />;
  }

  // Loading config, or navigating away to ORCID.
  return (
    <Center style={{ margin: 48 }}>
      <Loader />
    </Center>
  );
}
