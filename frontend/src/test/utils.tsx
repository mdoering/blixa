import { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MantineProvider } from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { Notifications } from '@mantine/notifications';
import { theme } from '../theme';

export function renderWithProviders(
  ui: ReactElement,
  opts: { route?: string; queryClient?: QueryClient } = {},
) {
  const queryClient =
    opts.queryClient ??
    new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MantineProvider theme={theme}>
      <ModalsProvider>
        <Notifications />
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[opts.route ?? '/']}>{children}</MemoryRouter>
        </QueryClientProvider>
      </ModalsProvider>
    </MantineProvider>
  );
  return render(ui, { wrapper });
}
