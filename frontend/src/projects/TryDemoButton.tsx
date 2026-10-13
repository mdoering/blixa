import { Button } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCat } from '@tabler/icons-react';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getImportRun, startImport } from '../api/import';
import { messageFor } from '../api/client';

// The playground ColDP bundled with the help (built by demo/build.sh).
export const DEMO_ZIP_URL = '/help/demo/blixa-playground-felidae.zip';
const DEMO_TITLE = 'Blixa playground – Felidae';
const POLL_MS = 1000;

// Project titles are unique per user: number further copies "(2)", "(3)", ...
export function demoTitle(existingTitles: string[]): string {
  const taken = new Set(existingTitles);
  if (!taken.has(DEMO_TITLE)) return DEMO_TITLE;
  let n = 2;
  while (taken.has(`${DEMO_TITLE} (${n})`)) n++;
  return `${DEMO_TITLE} (${n})`;
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// One click: import the bundled Felidae playground as a new private project (the user becomes its
// owner) and open its tree -- the starting point of the /help tutorial.
export default function TryDemoButton({ existingTitles }: { existingTitles: string[] }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    try {
      const res = await fetch(DEMO_ZIP_URL);
      if (!res.ok) throw new Error(`could not load the demo dataset (${res.status})`);
      const file = new File([await res.blob()], 'blixa-playground-felidae.zip', {
        type: 'application/zip',
      });
      let r = await startImport(file, false, undefined, demoTitle(existingTitles));
      while (r.status === 'RUNNING') {
        await wait(POLL_MS);
        r = await getImportRun(r.id);
      }
      if (r.status !== 'DONE' || r.projectId == null) throw new Error(r.error ?? 'import failed');
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/projects/${r.projectId}/tree`);
    } catch (e) {
      notifications.show({
        color: 'red',
        message: messageFor(e, 'Could not create the demo project'),
      });
      setBusy(false);
    }
  }

  return (
    <Button variant="light" leftSection={<IconCat size={14} />} loading={busy} onClick={run}>
      Try it with demo data
    </Button>
  );
}
