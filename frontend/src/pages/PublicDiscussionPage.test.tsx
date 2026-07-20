import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '../test/utils';
import { server, http, HttpResponse } from '../test/server';
import PublicDiscussionPage from './PublicDiscussionPage';

const discussion = {
  id: 1,
  projectId: 3,
  title: 'Public topic',
  body: 'A question about #7.',
  status: 'OPEN',
  visibility: 'PUBLIC',
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
  body: 'thanks for flagging',
  authorId: 9,
  authorOrcid: null,
  authorName: 'bob',
  createdAt: '2026-07-20T00:00:00Z',
  updatedAt: '2026-07-20T00:00:00Z',
  version: 0,
  mentions: { usages: {}, users: {} },
};

function renderPublic() {
  return renderWithProviders(
    <Routes>
      <Route path="/p/:pid/discussions/:id" element={<PublicDiscussionPage />} />
    </Routes>,
    { route: '/p/3/discussions/1' },
  );
}

test('renders a public discussion and its comments read-only', async () => {
  server.use(
    http.get('/api/public/projects/3/discussions/1', () => HttpResponse.json(discussion)),
    http.get('/api/public/projects/3/discussions/1/comments', () => HttpResponse.json([comment])),
  );
  renderPublic();

  expect(await screen.findByRole('heading', { name: 'Public topic' })).toBeInTheDocument();
  // name mention renders as plain italic text (no link into the authed name view)
  expect(await screen.findByText('Panthera leo')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Panthera leo' })).not.toBeInTheDocument();
  expect(screen.getByText('thanks for flagging')).toBeInTheDocument();
  // no editing affordances on the public page
  expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument();
});

test('shows an unavailable message when the discussion is not public', async () => {
  server.use(
    http.get('/api/public/projects/3/discussions/1', () => new HttpResponse(null, { status: 404 })),
  );
  renderPublic();

  expect(await screen.findByText('This discussion is not available.')).toBeInTheDocument();
});
