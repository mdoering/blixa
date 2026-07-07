import React from 'react';
import ReactDOM from 'react-dom/client';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import App from './App';
import { ApiError } from './api/client';

function markUnauthenticatedOn401(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    queryClient.setQueryData(['me'], null);
  }
}

const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      // Don't act on the ['me'] query's own 401 (RequireAuth handles that) — avoids a refetch loop.
      if (query.queryKey[0] !== 'me') markUnauthenticatedOn401(error);
    },
  }),
  mutationCache: new MutationCache({ onError: markUnauthenticatedOn401 }),
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
