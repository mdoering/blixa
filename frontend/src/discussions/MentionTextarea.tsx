import { Box, Group, Paper, Stack, Text, Textarea, UnstyledButton, type TextareaProps } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { listMembers } from '../api/projects';
import { searchUsages } from '../api/usages';

interface Suggestion {
  label: string;
  hint?: string;
  insert: string;
}
interface ActiveToken {
  kind: 'name' | 'user';
  query: string;
  start: number;
}

interface Props extends Omit<TextareaProps, 'value' | 'onChange'> {
  pid: number;
  value: string;
  onChange: (value: string) => void;
}

// Name trigger: `#` + a capital letter, then letters (`#Xyz…`) -- so `#123` id references and mid-word
// `#` are left alone. User trigger: `@` + name chars.
const NAME_RE = /#([A-Z][A-Za-z]*)$/;
const USER_RE = /@([A-Za-z0-9_-]*)$/;
const NAME_MIN = 3;

function detectToken(text: string, caret: number): ActiveToken | null {
  const before = text.slice(0, caret);
  const nameM = before.match(NAME_RE);
  if (nameM) return { kind: 'name', query: nameM[1], start: caret - nameM[0].length };
  const userM = before.match(USER_RE);
  if (userM) return { kind: 'user', query: userM[1], start: caret - userM[0].length };
  return null;
}

// A Textarea that offers an inline autocomplete for discussion mentions: selecting a name inserts
// the stable `#<id>` (never the typed string), and selecting a member inserts `@username`.
export default function MentionTextarea({ pid, value, onChange, onKeyDown, onBlur, onClick, ...rest }: Props) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const [token, setToken] = useState<ActiveToken | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [pendingCaret, setPendingCaret] = useState<number | null>(null);

  const [debouncedName] = useDebouncedValue(token?.kind === 'name' ? token.query : '', 200);
  const namesEnabled = token?.kind === 'name' && debouncedName.length >= NAME_MIN;

  const { data: names } = useQuery({
    queryKey: ['mentionNames', pid, debouncedName],
    queryFn: () => searchUsages(pid, { q: debouncedName, limit: 6, offset: 0 }),
    enabled: namesEnabled,
  });
  const { data: members } = useQuery({
    queryKey: ['members', pid],
    queryFn: () => listMembers(pid),
    enabled: token?.kind === 'user',
  });

  let suggestions: Suggestion[] = [];
  if (token?.kind === 'name' && token.query.length >= NAME_MIN) {
    suggestions = (names?.items ?? []).map((u) => ({
      label: u.scientificName ?? `#${u.id}`,
      hint: `#${u.id}`,
      insert: `#${u.id}`,
    }));
  } else if (token?.kind === 'user') {
    const q = token.query.toLowerCase();
    suggestions = (members ?? [])
      .filter((m) => m.username.toLowerCase().startsWith(q))
      .slice(0, 6)
      .map((m) => ({ label: `@${m.username}`, insert: `@${m.username}` }));
  }
  const open = suggestions.length > 0;

  useEffect(() => {
    if (activeIndex >= suggestions.length) setActiveIndex(0);
  }, [suggestions.length, activeIndex]);

  // Restore the caret after a selection rewrites the value (which remounts the controlled textarea).
  useEffect(() => {
    if (pendingCaret != null && ref.current) {
      ref.current.focus();
      ref.current.setSelectionRange(pendingCaret, pendingCaret);
      setPendingCaret(null);
    }
  }, [pendingCaret, value]);

  function refreshToken(text: string, caret: number) {
    setToken(detectToken(text, caret));
    setActiveIndex(0);
  }

  function select(s: Suggestion) {
    if (!token || !ref.current) return;
    const caret = ref.current.selectionStart ?? value.length;
    const next = value.slice(0, token.start) + s.insert + ' ' + value.slice(caret);
    onChange(next);
    setToken(null);
    setPendingCaret(token.start + s.insert.length + 1);
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (open) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIndex((i) => (i - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        select(suggestions[activeIndex]);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setToken(null);
        return;
      }
    }
    onKeyDown?.(e);
  }

  return (
    <Box pos="relative">
      <Textarea
        ref={ref}
        value={value}
        onChange={(e) => {
          onChange(e.currentTarget.value);
          refreshToken(e.currentTarget.value, e.currentTarget.selectionStart ?? e.currentTarget.value.length);
        }}
        onKeyDown={handleKeyDown}
        onClick={(e) => {
          refreshToken(e.currentTarget.value, e.currentTarget.selectionStart ?? 0);
          onClick?.(e);
        }}
        onBlur={(e) => {
          // let a click on a suggestion register before closing
          setTimeout(() => setToken(null), 150);
          onBlur?.(e);
        }}
        {...rest}
      />
      {open && (
        <Paper
          withBorder
          shadow="md"
          p={4}
          pos="absolute"
          left={0}
          right={0}
          style={{ zIndex: 300, top: '100%' }}
        >
          <Stack gap={0}>
            {suggestions.map((s, i) => (
              <UnstyledButton
                key={s.insert}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => select(s)}
                p="xs"
                style={{
                  borderRadius: 4,
                  backgroundColor: i === activeIndex ? 'var(--mantine-color-blue-light)' : undefined,
                }}
              >
                <Group justify="space-between" gap="xs" wrap="nowrap">
                  <Text size="sm">{s.label}</Text>
                  {s.hint && (
                    <Text size="xs" c="dimmed">
                      {s.hint}
                    </Text>
                  )}
                </Group>
              </UnstyledButton>
            ))}
          </Stack>
        </Paper>
      )}
    </Box>
  );
}
