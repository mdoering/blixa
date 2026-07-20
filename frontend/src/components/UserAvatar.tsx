import { Avatar, type MantineSize } from '@mantine/core';

// ORCID's public API exposes no profile photo, so users get an initials avatar with a deterministic
// colour derived from their name/handle. Shown next to authors in discussions, comments, etc.
const COLORS = [
  'blue',
  'cyan',
  'grape',
  'green',
  'indigo',
  'orange',
  'pink',
  'red',
  'teal',
  'violet',
];

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function colorFor(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return COLORS[h % COLORS.length];
}

export default function UserAvatar({
  name,
  size = 'sm',
}: {
  name: string | null | undefined;
  size?: MantineSize | number;
}) {
  const label = name?.trim() || 'Unknown';
  return (
    <Avatar radius="xl" size={size} color={colorFor(label)} title={label}>
      {initials(label)}
    </Avatar>
  );
}
