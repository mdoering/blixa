import { beforeEach, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen } from '../test/utils';
import CollapsibleSplit from './CollapsibleSplit';

const KEY = 'test:split:collapsed';

function renderSplit() {
  return render(
    <CollapsibleSplit
      storageKey={KEY}
      leftPercent={42}
      left={<div>LEFT PANE</div>}
      right={<div>RIGHT PANE</div>}
    />,
  );
}

beforeEach(() => localStorage.clear());

test('renders both panes when expanded', () => {
  renderSplit();
  expect(screen.getByText('LEFT PANE')).toBeInTheDocument();
  expect(screen.getByText('RIGHT PANE')).toBeInTheDocument();
});

test('collapsing hides the left pane and keeps the right', async () => {
  renderSplit();
  await userEvent.click(screen.getByRole('button', { name: 'Collapse panel' }));
  expect(screen.queryByText('LEFT PANE')).not.toBeInTheDocument();
  expect(screen.getByText('RIGHT PANE')).toBeInTheDocument();
});

test('expanding restores the left pane', async () => {
  renderSplit();
  await userEvent.click(screen.getByRole('button', { name: 'Collapse panel' }));
  await userEvent.click(screen.getByRole('button', { name: 'Expand panel' }));
  expect(screen.getByText('LEFT PANE')).toBeInTheDocument();
});

test('persists the collapsed state to localStorage', async () => {
  renderSplit();
  await userEvent.click(screen.getByRole('button', { name: 'Collapse panel' }));
  expect(localStorage.getItem(KEY)).toBe('true');
});

test('mounts collapsed when localStorage says so', () => {
  localStorage.setItem(KEY, 'true');
  renderSplit();
  expect(screen.queryByText('LEFT PANE')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Expand panel' })).toBeInTheDocument();
});
