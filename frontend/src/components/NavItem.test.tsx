import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { IconList } from '@tabler/icons-react';
import { renderWithProviders } from '../test/utils';
import NavItem from './NavItem';

test('expanded item shows its label and fires onClick', async () => {
  const onClick = vi.fn();
  renderWithProviders(<NavItem icon={<IconList />} label="Names" onClick={onClick} />);
  await userEvent.click(screen.getByText('Names'));
  expect(onClick).toHaveBeenCalled();
});

test('collapsed item hides the visible label but keeps it accessible', () => {
  renderWithProviders(<NavItem icon={<IconList />} label="Names" collapsed onClick={() => {}} />);
  // No visible text node...
  expect(screen.queryByText('Names')).not.toBeInTheDocument();
  // ...but the control is still reachable by its accessible name.
  expect(screen.getByLabelText('Names')).toBeInTheDocument();
});
