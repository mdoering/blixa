import { ActionIcon, Button, Group, Stack, Switch, Text, TextInput } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import type { CslName } from '../api/types';

export interface CslNameEditorProps {
  value: CslName[];
  onChange: (value: CslName[]) => void;
  label?: string;
}

// A compact, controlled editor for a CslName[] (structured author/editor names -- see backend
// life.catalogue.api.model.CslName / ReferenceResponse.author|editor). Each row is either a
// family/given pair, or -- once its "institution" toggle is on -- a single `literal` name (CSL-
// JSON's convention for a corporate/institutional author, e.g. "Bishop Museum"). Deliberately a
// plain controlled component, not a Mantine `useForm` field array: ReferenceForm holds
// `author`/`editor` as ordinary CslName[] values on its own form and swaps in a whole new array on
// every edit, so this component carries no state of its own -- every callback rebuilds the array
// immutably and hands it to `onChange`. `family`/`given`/`literal` cover the vast majority of
// names; the rarer CSL particle/suffix parts have no dedicated control here but round-trip
// untouched (the `update` spread below only overwrites the field being edited).
export default function CslNameEditor({ value, onChange, label }: CslNameEditorProps) {
  const rowLabel = label ?? 'Name';

  const update = (index: number, patch: Partial<CslName>) => {
    onChange(value.map((n, i) => (i === index ? { ...n, ...patch } : n)));
  };
  const remove = (index: number) => onChange(value.filter((_, i) => i !== index));
  const add = () => onChange([...value, {}]);

  return (
    <Stack gap={4}>
      {label && (
        <Text size="sm" fw={500}>
          {label}
        </Text>
      )}
      {value.map((name, i) => (
        <Group key={i} gap="xs" align="center" wrap="nowrap">
          {name.isInstitution ? (
            <TextInput
              placeholder="Institution name"
              aria-label={`${rowLabel} name ${i + 1}`}
              value={name.literal ?? ''}
              onChange={(e) => update(i, { literal: e.currentTarget.value })}
              style={{ flex: 1 }}
            />
          ) : (
            <>
              <TextInput
                placeholder="Family"
                aria-label={`${rowLabel} family ${i + 1}`}
                value={name.family ?? ''}
                onChange={(e) => update(i, { family: e.currentTarget.value })}
                style={{ flex: 1 }}
              />
              <TextInput
                placeholder="Given"
                aria-label={`${rowLabel} given ${i + 1}`}
                value={name.given ?? ''}
                onChange={(e) => update(i, { given: e.currentTarget.value })}
                style={{ flex: 1 }}
              />
            </>
          )}
          <Switch
            size="sm"
            aria-label={`${rowLabel} institution ${i + 1}`}
            checked={!!name.isInstitution}
            onChange={(e) => update(i, { isInstitution: e.currentTarget.checked })}
          />
          <ActionIcon
            type="button"
            variant="subtle"
            color="red"
            aria-label={`Remove ${rowLabel.toLowerCase()} ${i + 1}`}
            onClick={() => remove(i)}
          >
            <IconTrash size={16} />
          </ActionIcon>
        </Group>
      ))}
      <Button
        type="button"
        variant="subtle"
        size="xs"
        leftSection={<IconPlus size={14} />}
        onClick={add}
        style={{ alignSelf: 'flex-start' }}
      >
        Add {rowLabel.toLowerCase()}
      </Button>
    </Stack>
  );
}
