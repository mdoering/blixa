import {
  Anchor,
  Badge,
  Card,
  Container,
  Group,
  Loader,
  Paper,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconChevronRight,
  IconExclamationCircle,
  IconInbox,
  IconLock,
  IconMessage,
  IconPencil,
  IconUserCheck,
} from '@tabler/icons-react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { getDashboard, markDashboardSeen } from '../api/dashboard';

// One inbox row: an icon, a label (optionally a link), grouped under "Needs attention".
function InboxRow({
  icon,
  color,
  children,
}: {
  icon: React.ReactNode;
  color: string;
  children: React.ReactNode;
}) {
  return (
    <Group gap="sm" wrap="nowrap" align="center">
      <ThemeIcon variant="light" color={color} size="md" radius="xl">
        {icon}
      </ThemeIcon>
      <div style={{ flex: 1 }}>{children}</div>
    </Group>
  );
}

export default function DashboardPage() {
  const { data, isLoading } = useQuery({ queryKey: ['dashboard'], queryFn: getDashboard });

  // Reset the server-side "new pings" count once, after this load -- the current render still shows
  // the count we just fetched; the reset takes effect next visit.
  const seen = useMutation({ mutationFn: markDashboardSeen });
  const fired = useRef(false);
  useEffect(() => {
    if (data && !fired.current) {
      fired.current = true;
      seen.mutate();
    }
  }, [data, seen]);

  if (isLoading || !data) {
    return (
      <Container size="lg" py="md">
        <Loader />
      </Container>
    );
  }

  const hasInbox =
    (data.pendingUsers ?? 0) > 0 ||
    data.pings.count > 0 ||
    data.reviewSubmissions.length > 0 ||
    data.missingMetadata.length > 0 ||
    data.openErrors.length > 0 ||
    data.myLocks.length > 0;

  return (
    <Container size="lg" py="md">
      <Stack gap="lg">
        <Title order={2}>Dashboard</Title>

        <Paper withBorder p="md" radius="md">
          <Stack gap="sm">
            <Text fw={600}>Needs attention</Text>
            {!hasInbox && <Text c="dimmed" size="sm">You're all caught up.</Text>}

            {data.pendingUsers != null && data.pendingUsers > 0 && (
              <InboxRow icon={<IconUserCheck size={16} />} color="grape">
                <Anchor component={Link} to="/admin/users">
                  {data.pendingUsers} {data.pendingUsers === 1 ? 'person' : 'people'} awaiting approval
                </Anchor>
              </InboxRow>
            )}

            {data.pings.count > 0 && (
              <InboxRow icon={<IconMessage size={16} />} color="blue">
                <Text size="sm">
                  {data.pings.count} new{' '}
                  {data.pings.count === 1 ? 'ping' : 'pings'} in discussions since your last visit
                </Text>
                <Stack gap={2} mt={4}>
                  {data.pings.items.map((p) => (
                    <Anchor
                      key={`${p.projectId}-${p.discussionId}-${p.createdAt}`}
                      component={Link}
                      to={`/projects/${p.projectId}/discussions/${p.discussionId}`}
                      size="xs"
                    >
                      {p.title} <Text span c="dimmed">— {p.projectTitle}</Text>
                    </Anchor>
                  ))}
                </Stack>
              </InboxRow>
            )}

            {data.reviewSubmissions.map((r) => (
              <InboxRow key={`rev-${r.projectId}`} icon={<IconInbox size={16} />} color="teal">
                <Anchor component={Link} to={`/projects/${r.projectId}/discussions`} size="sm">
                  {r.count} review {r.count === 1 ? 'submission' : 'submissions'} to triage — {r.projectTitle}
                </Anchor>
              </InboxRow>
            ))}

            {data.missingMetadata.map((m) => (
              <InboxRow key={`meta-${m.projectId}`} icon={<IconAlertTriangle size={16} />} color="yellow">
                <Anchor component={Link} to={`/projects/${m.projectId}/metadata`} size="sm">
                  {m.projectTitle}: missing {m.missing.join(', ')}
                </Anchor>
              </InboxRow>
            ))}

            {data.openErrors.map((e) => (
              <InboxRow key={`err-${e.projectId}`} icon={<IconExclamationCircle size={16} />} color="red">
                <Anchor component={Link} to={`/projects/${e.projectId}/issues`} size="sm">
                  {e.count} open {e.count === 1 ? 'error' : 'errors'} — {e.projectTitle}
                </Anchor>
              </InboxRow>
            ))}

            {data.myLocks.length > 0 && (
              <InboxRow icon={<IconLock size={16} />} color="orange">
                <Text size="sm">
                  {data.myLocks.length} {data.myLocks.length === 1 ? 'taxon' : 'taxa'} you still have locked
                </Text>
                <Stack gap={2} mt={4}>
                  {data.myLocks.map((l) => (
                    <Anchor
                      key={`${l.projectId}-${l.usageId}`}
                      component={Link}
                      to={`/projects/${l.projectId}/names?usage=${l.usageId}`}
                      size="xs"
                    >
                      {l.scientificName ?? `#${l.usageId}`}{' '}
                      <Text span c="dimmed">— {l.projectTitle}</Text>
                    </Anchor>
                  ))}
                </Stack>
              </InboxRow>
            )}
          </Stack>
        </Paper>

        {data.recentTaxa.length > 0 && (
          <Paper withBorder p="md" radius="md">
            <Stack gap="sm">
              <Text fw={600}>Recently edited by you</Text>
              {data.recentTaxa.map((t) => (
                <Group key={`${t.projectId}-${t.usageId}`} gap="sm" wrap="nowrap">
                  <ThemeIcon variant="light" color="gray" size="sm" radius="xl">
                    <IconPencil size={13} />
                  </ThemeIcon>
                  <Anchor
                    component={Link}
                    to={`/projects/${t.projectId}/names?usage=${t.usageId}`}
                    size="sm"
                  >
                    {t.scientificName ?? `#${t.usageId}`}
                  </Anchor>
                  <Text span c="dimmed" size="xs">
                    {t.projectTitle}
                  </Text>
                </Group>
              ))}
            </Stack>
          </Paper>
        )}

        <Stack gap="sm">
          <Text fw={600}>My projects</Text>
          {data.projects.length === 0 ? (
            <Text c="dimmed" size="sm">You're not a member of any project yet.</Text>
          ) : (
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
              {data.projects.map((p) => (
                <Card key={p.id} withBorder radius="md" padding="md">
                  <Group justify="space-between" align="flex-start" wrap="nowrap">
                    <Anchor component={Link} to={`/projects/${p.id}/tree`} fw={600}>
                      {p.title}
                    </Anchor>
                    <Badge variant="light">{p.role}</Badge>
                  </Group>
                  <Text size="sm" c="dimmed" mt={4}>
                    {p.accepted.toLocaleString()} accepted · {p.synonyms.toLocaleString()} synonyms ·{' '}
                    {p.openIssues.toLocaleString()} open {p.openIssues === 1 ? 'issue' : 'issues'}
                  </Text>
                  <Group gap="xs" mt="sm">
                    <Anchor component={Link} to={`/projects/${p.id}/tree`} size="xs">tree</Anchor>
                    <Anchor component={Link} to={`/projects/${p.id}/names`} size="xs">names</Anchor>
                    <Anchor component={Link} to={`/projects/${p.id}/issues`} size="xs">issues</Anchor>
                    <IconChevronRight size={13} style={{ opacity: 0.4 }} />
                  </Group>
                </Card>
              ))}
            </SimpleGrid>
          )}
        </Stack>
      </Stack>
    </Container>
  );
}
