import { Badge, NavLink, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';

export interface NavItemProps {
  icon: ReactNode;
  label: string;
  active?: boolean;
  collapsed?: boolean;
  badge?: number;
  onClick: () => void;
}

// One sidebar row, built on Mantine NavLink (gets hover/active styling for free). When collapsed it
// renders icon-only inside a right-anchored Tooltip; the label stays as the NavLink's aria-label so
// the item is still findable/announced. `badge`, when > 0, renders a small pending-count indicator
// (e.g. pending join requests on the Members item) -- shown even while collapsed, since a count is
// exactly the kind of thing you don't want to lose just because the sidebar is narrow.
export default function NavItem({ icon, label, active, collapsed, badge, onClick }: NavItemProps) {
  const showBadge = !!badge && badge > 0;
  const link = (
    <NavLink
      leftSection={icon}
      label={collapsed ? undefined : label}
      aria-label={showBadge ? `${label} (${badge} pending)` : label}
      active={active}
      onClick={onClick}
      rightSection={
        showBadge ? (
          <Badge size="xs" circle color="red">
            {badge}
          </Badge>
        ) : undefined
      }
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
