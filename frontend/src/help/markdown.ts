import type { ReactNode } from 'react';
import { Children, isValidElement } from 'react';

export interface Heading {
  slug: string;
  level: number;
  label: string;
}

// GitHub-style heading slug: lower-case, drop punctuation, spaces → dashes. Used both for the
// rendered heading ids and for the on-page contents, so the two always agree.
export function slugify(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s/g, '-');
}

// Strip inline markdown (links, emphasis, code) from a heading line to get its plain label.
function plainHeading(text: string): string {
  return text
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_`]/g, '')
    .trim();
}

// The ## and ### headings of a markdown page (skipping fenced code), for the on-page contents.
export function extractHeadings(md: string): Heading[] {
  const headings: Heading[] = [];
  let inFence = false;
  for (const line of md.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) inFence = !inFence;
    if (inFence) continue;
    const m = /^(#{2,3})\s+(.+?)\s*#*\s*$/.exec(line);
    if (m) {
      const label = plainHeading(m[2]);
      headings.push({ slug: slugify(label), level: m[1].length, label });
    }
  }
  return headings;
}

// Flatten rendered heading children back to plain text, to compute the same slug as extractHeadings.
export function textOf(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textOf).join('');
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children);
  return Children.toArray(node).map(textOf).join('');
}
