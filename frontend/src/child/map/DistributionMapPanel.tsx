import { Suspense, lazy, useState } from 'react';
import { Alert, Button, Center, Checkbox, Group, Loader, Paper, Stack, Text } from '@mantine/core';
import { IconMap } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { getMapData } from '../../api/map';
import { getProject } from '../../api/projects';
import type { LayerVisibility } from './MapView';

// COL GBIF checklist UUID — matches backend `coldp.col.gbif-checklist-key`.
const COL_CHECKLIST_KEY = '7ddf754f-d193-4cc9-b351-99906754a03b';

// maplibre-gl lives entirely inside MapView; lazy-load it so its ~230KB (gzip) bundle stays out
// of the main entry chunk.
const LazyMapView = lazy(() => import('./MapView'));

interface Props {
  pid: number;
  usageId: number;
  canEdit: boolean;
}

export default function DistributionMapPanel({ pid, usageId, canEdit }: Props) {
  const mapQuery = useQuery({
    queryKey: ['map', pid, usageId],
    queryFn: () => getMapData(pid, usageId),
  });
  const { data: project } = useQuery({
    queryKey: ['project', pid],
    queryFn: () => getProject(pid),
  });

  const [layers, setLayers] = useState<LayerVisibility>({
    distFocal: true,
    distChildren: false,
    typeFocal: true,
    typeChildren: false,
  });
  // GBIF default follows the project setting + a COL match; null = "follow default", otherwise a
  // user override. Kept user-toggleable either way.
  const [gbifOverride, setGbifOverride] = useState<boolean | null>(null);

  // Task 8 wires the match-to-COL modal into this state; the button is a no-op placeholder for now.
  const [, setMatchOpen] = useState(false);

  const mapData = mapQuery.data;
  const colId = mapData?.colId ?? null;
  const gbifDefault = !!(project?.gbifOccurrenceLayer && colId);
  const gbifOn = gbifOverride ?? gbifDefault;

  const toggle = (key: keyof LayerVisibility) => (checked: boolean) =>
    setLayers((prev) => ({ ...prev, [key]: checked }));

  if (mapQuery.isLoading) {
    return (
      <Center h={120}>
        <Loader size="sm" />
      </Center>
    );
  }
  if (mapQuery.isError || !mapData) {
    return (
      <Alert color="red" variant="light">
        Could not load map data.
      </Alert>
    );
  }

  // Free-text areas without a gazetteer code cannot be drawn on the map.
  const notMappable = mapData.distributions.filter((d) => d.area && !d.areaId);

  return (
    <Paper withBorder p="sm">
      <Stack gap="xs">
        <Group justify="space-between" align="center">
          <Group gap="lg">
            <Checkbox
              label="Distribution (focal)"
              checked={layers.distFocal}
              onChange={(e) => toggle('distFocal')(e.currentTarget.checked)}
            />
            <Checkbox
              label="Distribution (children)"
              checked={layers.distChildren}
              onChange={(e) => toggle('distChildren')(e.currentTarget.checked)}
            />
            <Checkbox
              label="Type specimens (focal)"
              checked={layers.typeFocal}
              onChange={(e) => toggle('typeFocal')(e.currentTarget.checked)}
            />
            <Checkbox
              label="Type specimens (children)"
              checked={layers.typeChildren}
              onChange={(e) => toggle('typeChildren')(e.currentTarget.checked)}
            />
            {colId && (
              <Checkbox
                label="GBIF occurrences"
                checked={gbifOn}
                onChange={(e) => setGbifOverride(e.currentTarget.checked)}
              />
            )}
          </Group>
        </Group>

        {!colId && (
          <Alert color="blue" variant="light" icon={<IconMap size={16} />} title="Not matched to COL yet">
            <Group justify="space-between" align="center">
              <Text size="sm">
                This taxon is not linked to a Catalogue of Life id, so GBIF occurrences cannot be shown.
              </Text>
              {canEdit && (
                <Button size="xs" variant="light" onClick={() => setMatchOpen(true)}>
                  Match to COL
                </Button>
              )}
            </Group>
          </Alert>
        )}

        <Suspense
          fallback={
            <Center h={360}>
              <Loader size="sm" />
            </Center>
          }
        >
          <LazyMapView
            colId={colId}
            checklistKey={COL_CHECKLIST_KEY}
            distributions={mapData.distributions}
            typeSpecimens={mapData.typeSpecimens}
            layers={layers}
            gbifEnabled={gbifOn && !!colId}
          />
        </Suspense>

        {notMappable.length > 0 && (
          <Text size="xs" c="dimmed">
            Not mappable (free-text area only):{' '}
            {notMappable.map((d) => d.area).join(', ')}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}
