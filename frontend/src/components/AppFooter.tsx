import { Anchor, Group, Text } from '@mantine/core';
import BlixaLogo from './BlixaLogo';

const REPO_URL = 'https://github.com/mdoering/blixa';

// Slim, dimmed, scheme-aware footer: brand mark + build info on the left, repo link on the right.
export default function AppFooter() {
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap">
      <Group gap={6} wrap="nowrap" c="dimmed">
        <BlixaLogo variant="icon" height={14} />
        <Text size="xs" c="dimmed">
          v{__APP_VERSION__} · {import.meta.env.MODE}
        </Text>
      </Group>
      <Anchor size="xs" c="dimmed" href={REPO_URL} target="_blank" rel="noreferrer">
        GitHub
      </Anchor>
    </Group>
  );
}
