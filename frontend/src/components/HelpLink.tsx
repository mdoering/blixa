import { ActionIcon, Group, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import { IconHelpCircle } from '@tabler/icons-react';
import { helpPath } from '../help/toc';

export interface HelpLinkProps {
  /** help page slug (src/help/pages/<page>.md); defaults to the help index */
  page?: string;
  /** optional heading anchor on that page */
  section?: string;
  /** tooltip / accessible name */
  label?: string;
  size?: number;
}

/** A small (?) icon that opens the matching user-documentation page in a new tab. */
export default function HelpLink({
  page = 'index',
  section,
  label = 'Help',
  size = 18,
}: HelpLinkProps) {
  return (
    <Tooltip label={label} withArrow>
      <ActionIcon
        component="a"
        href={helpPath(page, section)}
        target="_blank"
        rel="noreferrer"
        variant="subtle"
        color="gray"
        aria-label={label}
      >
        <IconHelpCircle size={size} />
      </ActionIcon>
    </Tooltip>
  );
}

/** A modal / section title followed by its (?) help link. */
export function WithHelp({ children, ...link }: HelpLinkProps & { children: ReactNode }) {
  return (
    <Group gap={6} wrap="nowrap">
      {children}
      <HelpLink size={16} {...link} />
    </Group>
  );
}
