import { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';

export function renderWithProviders(ui: ReactElement, opts: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <MemoryRouter initialEntries={[opts.route ?? '/']}>{children}</MemoryRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
  return render(ui, { wrapper });
}
