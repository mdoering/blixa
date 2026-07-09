import { Anchor, Group, Text } from '@mantine/core';

// Assumed repository path (no git remote is configured yet — update when the remote exists).
const REPO_URL = 'https://github.com/CatalogueOfLife/coldp-editor';

// Slim, dimmed, scheme-aware footer: identity + build info on the left, repo link on the right.
export default function AppFooter() {
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap">
      <Text size="xs" c="dimmed">
        ColDP Editor · v{__APP_VERSION__} · {import.meta.env.MODE}
      </Text>
      <Anchor size="xs" c="dimmed" href={REPO_URL} target="_blank" rel="noreferrer">
        GitHub
      </Anchor>
    </Group>
  );
}
