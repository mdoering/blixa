import { Anchor, Button, Divider, Group, Image, Loader, Modal, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { bhlItemPages, bhlNamePages, type BhlPage } from '../api/bhl';

interface Picked {
  url: string;
  pageNumber: string | null;
}

interface BhlPageModalProps {
  pid: number;
  itemId: number;
  name: string;
  opened: boolean;
  onClose: () => void;
  onPick: (page: Picked) => void;
}

function PageRow({ page, onPick }: { page: BhlPage; onPick: (p: Picked) => void }) {
  if (!page.url) return null;
  const url = page.url;
  return (
    <Group justify="space-between" wrap="nowrap">
      <Group gap="xs" wrap="nowrap" style={{ minWidth: 0 }}>
        {page.thumbnailUrl && (
          <Image src={page.thumbnailUrl} h={48} w={36} fit="contain" radius="sm" alt="" />
        )}
        <Anchor href={url} target="_blank" rel="noreferrer" size="sm">
          {page.pageNumber ? `p. ${page.pageNumber}` : 'page'}
        </Anchor>
      </Group>
      <Button size="xs" variant="light" onClick={() => onPick({ url, pageNumber: page.pageNumber })}>
        Use this page
      </Button>
    </Group>
  );
}

// Phase B: within a reference's linked BHL item, pick the exact page for a name. Shows pages where
// the name appears (BHL's OCR index -- the likely protologue) first, then all pages to browse. On
// pick, the caller fills publishedInPageLink + publishedInPage. Enabled only when the name's
// nomenclatural reference already has a BHL item.
export default function BhlPageModal({
  pid,
  itemId,
  name,
  opened,
  onClose,
  onPick,
}: BhlPageModalProps) {
  const suggested = useQuery({
    queryKey: ['bhlNamePages', pid, itemId, name],
    queryFn: () => bhlNamePages(pid, itemId, name),
    enabled: opened && !!name,
  });
  const all = useQuery({
    queryKey: ['bhlItemPages', pid, itemId],
    queryFn: () => bhlItemPages(pid, itemId),
    enabled: opened,
  });

  const pick = (p: Picked) => {
    onPick(p);
    onClose();
  };

  return (
    <Modal opened={opened} onClose={onClose} size="lg" title="Find page on BHL">
      <Stack gap="sm">
        <div>
          <Title order={6} mb="xs">
            Where “{name}” appears
          </Title>
          {suggested.isPending ? (
            <Loader size="sm" />
          ) : suggested.data && suggested.data.length > 0 ? (
            <Stack gap="xs">
              {suggested.data.map((p, i) => (
                <PageRow key={p.pageId ?? i} page={p} onPick={pick} />
              ))}
            </Stack>
          ) : (
            <Text size="sm" c="dimmed">
              No name matches in this item — browse the pages below.
            </Text>
          )}
        </div>

        <Divider />

        <div>
          <Title order={6} mb="xs">
            All pages in this item
          </Title>
          {all.isPending ? (
            <Loader size="sm" />
          ) : (
            <Stack gap="xs" style={{ maxHeight: 300, overflowY: 'auto' }}>
              {(all.data ?? []).map((p, i) => (
                <PageRow key={p.pageId ?? i} page={p} onPick={pick} />
              ))}
            </Stack>
          )}
        </div>
      </Stack>
    </Modal>
  );
}
