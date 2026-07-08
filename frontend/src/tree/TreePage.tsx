import { Box, Grid, Text } from '@mantine/core';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import Breadcrumb from './Breadcrumb';
import ClassificationTree from './ClassificationTree';

export default function TreePage() {
  const { projectId } = useParams();
  const pid = Number(projectId);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  return (
    <Grid gutter="md">
      <Grid.Col span={5} style={{ maxHeight: '75vh', overflowY: 'auto' }}>
        <ClassificationTree pid={pid} selectedId={selectedId} onSelect={setSelectedId} />
      </Grid.Col>
      <Grid.Col span={7}>
        {selectedId == null ? (
          <Text c="dimmed">Select a taxon in the tree to see its details.</Text>
        ) : (
          <Box>
            <Breadcrumb pid={pid} selectedId={selectedId} />
            {/* Placeholder detail pane -- Task 3 replaces this with the real taxon detail
                panel (view/edit fields, synonyms, validation issues). */}
            <Text mt="md" c="dimmed">
              Selected usage #{selectedId}
            </Text>
          </Box>
        )}
      </Grid.Col>
    </Grid>
  );
}
