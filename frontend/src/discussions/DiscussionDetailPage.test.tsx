import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import DiscussionDetailPage from './DiscussionDetailPage';

const project = { id: 3, title: 'P', role: 'owner' };

const discussion = {
  id: 1,
  projectId: 3,
  title: 'Placement of cats',
  body: 'The parent of #7 is wrong.',
  status: 'OPEN',
  visibility: 'INTERNAL',
  authorId: 9,
  authorOrcid: null,
  authorName: 'alice',
  createdVia: 'UI',
  createdAt: '2026-07-20T00:00:00Z',
  updatedAt: '2026-07-20T00:00:00Z',
  version: 0,
  mentions: { usages: { '7': 'Panthera leo' }, users: {} },
};

const comment = {
  id: 1,
  projectId: 3,
  discussionId: 1,
  body: 'I agree.',
  authorId: 9,
  authorOrcid: null,
  authorName: 'bob',
  createdAt: '2026-07-20T00:00:00Z',
  updatedAt: '2026-07-20T00:00:00Z',
  version: 0,
  mentions: { usages: {}, users: {} },
};

function renderDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId/discussions/:id" element={<DiscussionDetailPage />} />
    </Routes>,
    { route: '/projects/3/discussions/1' },
  );
}

test('renders the body with a mention link and the comments', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.get('/api/projects/3/discussions/1', () => HttpResponse.json(discussion)),
    http.get('/api/projects/3/discussions/1/comments', () => HttpResponse.json([comment])),
  );
  renderDetail();

  expect(await screen.findByRole('heading', { name: 'Placement of cats' })).toBeInTheDocument();
  const mention = await screen.findByRole('link', { name: 'Panthera leo' });
  expect(mention).toHaveAttribute('href', '/projects/3/names?usage=7');
  expect(screen.getByText('I agree.')).toBeInTheDocument();
  expect(screen.getByText('bob')).toBeInTheDocument();
});

test('a public discussion shows the Public badge and a public-page link', async () => {
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.get('/api/projects/3/discussions/1', () =>
      HttpResponse.json({ ...discussion, visibility: 'PUBLIC' }),
    ),
    http.get('/api/projects/3/discussions/1/comments', () => HttpResponse.json([])),
  );
  renderDetail();

  await screen.findByRole('heading', { name: 'Placement of cats' });
  expect(screen.getByText('Public')).toBeInTheDocument();
  const link = screen.getByRole('link', { name: /View public page/ });
  expect(link).toHaveAttribute('href', '/p/3/discussions/1');
});

test('posting a comment sends the body', async () => {
  let posted: unknown = null;
  server.use(
    http.get('/api/projects/3', () => HttpResponse.json(project)),
    http.get('/api/projects/3/discussions/1', () => HttpResponse.json(discussion)),
    http.get('/api/projects/3/discussions/1/comments', () => HttpResponse.json([])),
    http.post('/api/projects/3/discussions/1/comments', async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json({ ...comment, body: 'Nice point' }, { status: 201 });
    }),
  );
  renderDetail();

  await screen.findByRole('heading', { name: 'Placement of cats' });
  await userEvent.type(screen.getByLabelText('Add a comment'), 'Nice point');
  await userEvent.click(screen.getByRole('button', { name: 'Comment' }));

  await waitFor(() => expect(posted).toEqual({ body: 'Nice point' }));
});
