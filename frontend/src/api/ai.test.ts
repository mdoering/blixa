import { expect, test } from 'vitest';
import { server, http, HttpResponse } from '../test/server';
import { getAiConfig, requestSuggestions } from './ai';

test('getAiConfig GETs the project AI config', async () => {
  server.use(
    http.get('/api/projects/3/ai/config', () =>
      HttpResponse.json({ available: true, provider: 'anthropic', model: 'claude-opus-4-8' }),
    ),
  );
  const cfg = await getAiConfig(3);
  expect(cfg.available).toBe(true);
  expect(cfg.model).toBe('claude-opus-4-8');
});

test('requestSuggestions POSTs to the focal-taxon suggest endpoint', async () => {
  let method = '';
  server.use(
    http.post('/api/projects/3/usages/9/ai/suggest', ({ request }) => {
      method = request.method;
      return HttpResponse.json({
        provider: 'anthropic',
        model: 'm',
        synonyms: [],
        vernacularNames: [],
        distributions: [],
        descriptions: [],
        references: [],
        etymology: null,
      });
    }),
  );
  const set = await requestSuggestions(3, 9);
  expect(method).toBe('POST');
  expect(set.provider).toBe('anthropic');
});
