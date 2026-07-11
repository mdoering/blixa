import { Anchor, Group, Text } from '@mantine/core';

const REPO_URL = 'https://github.com/mdoering/blixa';

// Slim, dimmed, scheme-aware footer: identity + build info on the left, repo link on the right.
export default function AppFooter() {
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap">
      <Text size="xs" c="dimmed">
        Blixa · v{__APP_VERSION__} · {import.meta.env.MODE}
      </Text>
      <Anchor size="xs" c="dimmed" href={REPO_URL} target="_blank" rel="noreferrer">
        GitHub
      </Anchor>
    </Group>
  );
}
