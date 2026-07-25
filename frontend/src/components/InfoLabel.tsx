import { ActionIcon, Popover, Text } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useState } from 'react';
import type { ReactNode } from 'react';

export interface InfoLabelProps {
  /** the visible field label */
  label: ReactNode;
  /** the help text, shown in a popover behind a small (i) icon */
  info: ReactNode;
  /** accessible name for the info button (defaults to "More information") */
  iconLabel?: string;
}

/**
 * A form-field label with a trailing small (i) icon whose click opens a popover with the field's
 * help text. Keeps the name-editing forms compact by moving the (formerly inline) `description`
 * out of the field body.
 *
 * When used as a Mantine input's `label`, pin the input's `aria-label` to the plain label string so
 * the info button (an interactive descendant of the <label>) doesn't leak into the field's
 * accessible name.
 */
export default function InfoLabel({ label, info, iconLabel = 'More information' }: InfoLabelProps) {
  const [opened, setOpened] = useState(false);
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span>{label}</span>
      <Popover
        width={260}
        position="top"
        withArrow
        shadow="md"
        opened={opened}
        onChange={setOpened}
      >
        <Popover.Target>
          {/* a <span role=button>, not a real <button>: the icon lives inside the field's <label>,
              and a nested labelable element would get captured as a second control by
              getByLabelText and pollute the field's accessible name. */}
          <ActionIcon
            component="span"
            role="button"
            tabIndex={0}
            variant="subtle"
            color="gray"
            size="xs"
            radius="xl"
            aria-label={iconLabel}
            onClick={(e) => {
              // the icon may sit inside a <label> (Checkbox); don't toggle the field
              e.preventDefault();
              e.stopPropagation();
              setOpened((o) => !o);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                setOpened((o) => !o);
              }
            }}
          >
            <IconInfoCircle size={14} />
          </ActionIcon>
        </Popover.Target>
        <Popover.Dropdown>
          <Text size="xs" c="dimmed" style={{ fontWeight: 400 }}>
            {info}
          </Text>
        </Popover.Dropdown>
      </Popover>
    </span>
  );
}
