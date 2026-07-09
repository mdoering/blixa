import { NavLink, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';

export interface NavItemProps {
  icon: ReactNode;
  label: string;
  active?: boolean;
  collapsed?: boolean;
  onClick: () => void;
}

// One sidebar row, built on Mantine NavLink (gets hover/active styling for free). When collapsed it
// renders icon-only inside a right-anchored Tooltip; the label stays as the NavLink's aria-label so
// the item is still findable/announced.
export default function NavItem({ icon, label, active, collapsed, onClick }: NavItemProps) {
  const link = (
    <NavLink
      leftSection={icon}
      label={collapsed ? undefined : label}
      aria-label={label}
      active={active}
      onClick={onClick}
    />
  );
  return collapsed ? (
    <Tooltip label={label} position="right" withArrow>
      {link}
    </Tooltip>
  ) : (
    link
  );
}
