import {
  Anchor,
  Box,
  Group,
  Image,
  Loader,
  NavLink,
  Paper,
  Stack,
  Table,
  Text,
  TypographyStylesProvider,
} from '@mantine/core';
import { IconArrowLeft, IconArrowRight, IconLink } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import type { ReactNode } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Link, Navigate, useLocation, useParams } from 'react-router-dom';
import { HELP_PAGES, HELP_TOC, helpPath } from './toc';
import { extractHeadings, slugify, textOf } from './markdown';
import type { Heading } from './markdown';

// Every help page is a markdown file bundled as its own lazy chunk.
const loaders = import.meta.glob<string>('./pages/*.md', { query: '?raw', import: 'default' });

export function helpPageFiles(): string[] {
  return Object.keys(loaders).map((k) => k.replace(/^\.\/pages\/(.+)\.md$/, '$1'));
}

function HeadingWithAnchor({ level, children }: { level: 2 | 3; children: ReactNode }) {
  const slug = slugify(textOf(children));
  const Tag = `h${level}` as const;
  return (
    <Tag id={slug} style={{ scrollMarginTop: 72 }}>
      {children}{' '}
      <Anchor
        href={`#${slug}`}
        aria-label="Link to this section"
        c="dimmed"
        style={{ opacity: 0.5 }}
      >
        <IconLink size={14} />
      </Anchor>
    </Tag>
  );
}

function HelpMarkdown({ text }: { text: string }) {
  return (
    <TypographyStylesProvider>
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={{
          h2: ({ children }) => <HeadingWithAnchor level={2}>{children}</HeadingWithAnchor>,
          h3: ({ children }) => <HeadingWithAnchor level={3}>{children}</HeadingWithAnchor>,
          a({ href, children }) {
            if (href?.startsWith('/') && !href.startsWith('/help/demo/')) {
              return (
                <Anchor component={Link} to={href}>
                  {children}
                </Anchor>
              );
            }
            if (href?.startsWith('#') || href?.startsWith('/help/demo/')) {
              return <Anchor href={href}>{children}</Anchor>;
            }
            return (
              <Anchor href={href} target="_blank" rel="noreferrer">
                {children}
              </Anchor>
            );
          },
          // Screenshots: bordered, click opens the full-size image.
          img({ src, alt }) {
            const url = typeof src === 'string' ? src : undefined;
            return (
              <a
                href={url}
                target="_blank"
                rel="noreferrer"
                style={{ display: 'block', margin: '1rem 0' }}
              >
                <Image
                  src={url}
                  alt={alt ?? ''}
                  radius="sm"
                  style={{ border: '1px solid var(--mantine-color-default-border)' }}
                />
              </a>
            );
          },
          table: ({ children }) => (
            <Table withTableBorder withColumnBorders striped my="md">
              {children}
            </Table>
          ),
        }}
      >
        {text}
      </Markdown>
    </TypographyStylesProvider>
  );
}

function HelpSidebar({ current }: { current: string }) {
  return (
    <Stack gap={4}>
      {HELP_TOC.map((section) => (
        <Box key={section.title} mb="xs">
          <Text size="xs" fw={700} tt="uppercase" c="dimmed" px="sm" mb={4}>
            {section.title}
          </Text>
          {section.pages.map((p) => (
            <NavLink
              key={p.slug}
              component={Link}
              to={helpPath(p.slug)}
              label={p.title}
              active={p.slug === current}
            />
          ))}
        </Box>
      ))}
    </Stack>
  );
}

function OnThisPage({ headings }: { headings: Heading[] }) {
  if (headings.length < 2) return null;
  return (
    <nav aria-label="On this page">
      <Text size="xs" fw={700} tt="uppercase" c="dimmed" mb="xs">
        On this page
      </Text>
      <Stack gap={4}>
        {headings.map((h) => (
          <Anchor
            key={h.slug}
            href={`#${h.slug}`}
            size="sm"
            c="dimmed"
            pl={h.level === 3 ? 'sm' : 0}
          >
            {h.label}
          </Anchor>
        ))}
      </Stack>
    </nav>
  );
}

function PrevNext({ current }: { current: string }) {
  const i = HELP_PAGES.findIndex((p) => p.slug === current);
  const prev = HELP_PAGES[i - 1];
  const next = HELP_PAGES[i + 1];
  return (
    <Group justify="space-between" mt="xl">
      {prev ? (
        <Anchor component={Link} to={helpPath(prev.slug)}>
          <Group gap={4}>
            <IconArrowLeft size={14} /> {prev.title}
          </Group>
        </Anchor>
      ) : (
        <span />
      )}
      {next && (
        <Anchor component={Link} to={helpPath(next.slug)}>
          <Group gap={4}>
            {next.title} <IconArrowRight size={14} />
          </Group>
        </Anchor>
      )}
    </Group>
  );
}

// The in-app user documentation: /help (index) and /help/:page. Public — readable before sign-in.
export default function HelpPage() {
  const { page = 'index' } = useParams();
  const { hash } = useLocation();
  const loader = loaders[`./pages/${page}.md`];

  const { data: text, isLoading } = useQuery({
    queryKey: ['help', page],
    queryFn: () => loader(),
    enabled: !!loader,
    staleTime: Infinity,
  });

  // Deep links (/help/tree#moving-names): scroll once the page body has rendered.
  useEffect(() => {
    if (!text) return;
    if (hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView?.();
    else document.scrollingElement?.scrollTo?.(0, 0);
  }, [text, hash]);

  if (!loader) return <Navigate to="/help" replace />;

  return (
    <Group align="flex-start" gap="xl" wrap="nowrap" maw={1280} mx="auto">
      <Box
        w={240}
        visibleFrom="sm"
        style={{
          position: 'sticky',
          top: 72,
          flexShrink: 0,
          maxHeight: 'calc(100vh - 120px)',
          overflowY: 'auto',
        }}
      >
        <HelpSidebar current={page} />
      </Box>
      <Paper component="article" p="lg" withBorder style={{ flex: 1, minWidth: 0, maxWidth: 860 }}>
        {isLoading || text == null ? (
          <Loader />
        ) : (
          <>
            <HelpMarkdown text={text} />
            <PrevNext current={page} />
          </>
        )}
      </Paper>
      <Box w={220} visibleFrom="lg" style={{ position: 'sticky', top: 72, flexShrink: 0 }}>
        {text && <OnThisPage headings={extractHeadings(text)} />}
      </Box>
    </Group>
  );
}
