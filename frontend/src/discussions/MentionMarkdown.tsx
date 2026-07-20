import { Anchor, Text, TypographyStylesProvider } from '@mantine/core';
import { Link } from 'react-router-dom';
import Markdown, { defaultUrlTransform } from 'react-markdown';
import type { Mentions } from '../api/discussions';

interface Props {
  pid: number;
  text: string | null | undefined;
  mentions?: Mentions | null;
  // Public pages can't link into the authenticated name view -> render name mentions as plain italic.
  linkUsages?: boolean;
}

const USAGE_RE = /#(\d+)/g;
const USER_RE = /@([A-Za-z0-9][A-Za-z0-9_-]*)/g;
const USAGE_SCHEME = 'mention-usage:';
const USER_SCHEME = 'mention-user:';

// Turn resolved mentions into markdown links with a private URI scheme, so react-markdown parses
// them and the custom `a` renderer below maps them to the right component. Unresolved tokens are
// left as plain text.
function withMentionLinks(text: string, mentions?: Mentions | null): string {
  let out = text.replace(USAGE_RE, (m, id) => {
    const label = mentions?.usages?.[id];
    return label ? `[${label}](${USAGE_SCHEME}${id})` : m;
  });
  out = out.replace(USER_RE, (m, token) => {
    const user = mentions?.users?.[token];
    return user ? `[${user.label}](${USER_SCHEME}${token})` : m;
  });
  return out;
}

// Renders discussion/comment markdown, linkifying #nameID mentions (→ the name, italic) and
// @orcid/@username mentions (→ the person; links out to orcid.org when the user has an ORCID).
export default function MentionMarkdown({ pid, text, mentions, linkUsages = true }: Props) {
  if (!text) return null;
  return (
    <TypographyStylesProvider>
      <Markdown
        // Keep our private mention-usage:/mention-user: schemes (the custom `a` renderer maps them
        // to safe internal/external links); sanitize every other URL with react-markdown's default
        // transform so a user-written [x](javascript:…) link in a body can't inject script.
        urlTransform={(url) =>
          url.startsWith(USAGE_SCHEME) || url.startsWith(USER_SCHEME) ? url : defaultUrlTransform(url)
        }
        components={{
          a({ href, children }) {
            if (href?.startsWith(USAGE_SCHEME)) {
              const id = href.slice(USAGE_SCHEME.length);
              if (!linkUsages) return <i>{children}</i>;
              return (
                <Anchor component={Link} to={`/projects/${pid}/names?usage=${id}`}>
                  <i>{children}</i>
                </Anchor>
              );
            }
            if (href?.startsWith(USER_SCHEME)) {
              const token = href.slice(USER_SCHEME.length);
              const orcid = mentions?.users?.[token]?.orcid;
              if (orcid) {
                return (
                  <Anchor href={`https://orcid.org/${orcid}`} target="_blank" rel="noopener noreferrer">
                    @{children}
                  </Anchor>
                );
              }
              return (
                <Text span fw={600} c="blue.6">
                  @{children}
                </Text>
              );
            }
            return (
              <Anchor href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </Anchor>
            );
          },
        }}
      >
        {withMentionLinks(text, mentions)}
      </Markdown>
    </TypographyStylesProvider>
  );
}
