import { Anchor, TypographyStylesProvider } from '@mantine/core';
import { Link } from 'react-router-dom';
import Markdown, { defaultUrlTransform } from 'react-markdown';
import type { Mentions } from '../api/discussions';

interface Props {
  pid: number;
  text: string | null | undefined;
  mentions?: Mentions | null;
}

const USAGE_RE = /#(\d+)/g;
const ORCID_RE = /@(\d{4}-\d{4}-\d{4}-\d{3}[\dXx])/g;
const USAGE_SCHEME = 'mention-usage:';
const ORCID_SCHEME = 'mention-orcid:';

// Turn resolved mentions into markdown links with a private URI scheme, so react-markdown parses
// them and the custom `a` renderer below maps them to the right component. Unresolved tokens are
// left as plain text.
function withMentionLinks(text: string, mentions?: Mentions | null): string {
  let out = text.replace(USAGE_RE, (m, id) => {
    const label = mentions?.usages?.[id];
    return label ? `[${label}](${USAGE_SCHEME}${id})` : m;
  });
  out = out.replace(ORCID_RE, (m, orcid) => {
    const label = mentions?.orcids?.[orcid];
    return label ? `[${label}](${ORCID_SCHEME}${orcid})` : m;
  });
  return out;
}

// Renders discussion/comment markdown, linkifying #nameID mentions (→ the name, italic) and @orcid
// mentions (→ the person, linking out to orcid.org).
export default function MentionMarkdown({ pid, text, mentions }: Props) {
  if (!text) return null;
  return (
    <TypographyStylesProvider>
      <Markdown
        // Keep our private mention-usage:/mention-orcid: schemes (the custom `a` renderer maps them
        // to safe internal/external links); sanitize every other URL with react-markdown's default
        // transform so a user-written [x](javascript:…) link in a body can't inject script.
        urlTransform={(url) =>
          url.startsWith(USAGE_SCHEME) || url.startsWith(ORCID_SCHEME) ? url : defaultUrlTransform(url)
        }
        components={{
          a({ href, children }) {
            if (href?.startsWith(USAGE_SCHEME)) {
              const id = href.slice(USAGE_SCHEME.length);
              return (
                <Anchor component={Link} to={`/projects/${pid}/names?usage=${id}`}>
                  <i>{children}</i>
                </Anchor>
              );
            }
            if (href?.startsWith(ORCID_SCHEME)) {
              const orcid = href.slice(ORCID_SCHEME.length);
              return (
                <Anchor href={`https://orcid.org/${orcid}`} target="_blank" rel="noopener noreferrer">
                  @{children}
                </Anchor>
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
