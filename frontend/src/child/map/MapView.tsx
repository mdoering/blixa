import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Alert } from '@mantine/core';
import type { Feature, FeatureCollection, GeoJSON as GeoJson, Geometry } from 'geojson';
import type { MapAreaRecord, MapPointRecord } from '../../api/map';
import { areaGeojsonUrl, gbifTileUrl } from './mapUrls';

// The ONLY module that imports maplibre-gl. Kept behind React.lazy from DistributionMapPanel so
// the ~230KB (gzip) maplibre bundle lands in its own chunk and stays out of the main entry.

const POSITRON_STYLE = 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json';

// Layer paint colours: focal taxon vs its descendants get distinct hues.
const DIST_FOCAL_FILL = '#1971c2'; // blue.7
const DIST_CHILDREN_FILL = '#868e96'; // gray.6
const TYPE_FOCAL_COLOR = '#e03131'; // red.7
const TYPE_CHILDREN_COLOR = '#f08c00'; // orange.7

export interface LayerVisibility {
  distFocal: boolean;
  distChildren: boolean;
  typeFocal: boolean;
  typeChildren: boolean;
}

export interface MapViewProps {
  colId: string | null;
  checklistKey: string;
  distributions: MapAreaRecord[];
  typeSpecimens: MapPointRecord[];
  layers: LayerVisibility;
  gbifEnabled: boolean;
}

// Layer ids grouped so visibility can be toggled without rebuilding the map.
const LAYER_IDS = {
  distFocal: ['dist-focal-fill', 'dist-focal-line'],
  distChildren: ['dist-children-fill', 'dist-children-line'],
  typeFocal: ['type-focal'],
  typeChildren: ['type-children'],
  gbif: ['gbif-density'],
};

type FeatureList = Feature[];

function pointsToFeatures(points: MapPointRecord[], focal: boolean): FeatureList {
  return points
    .filter((p) => p.focal === focal && p.latitude != null && p.longitude != null)
    .map((p) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [p.longitude as number, p.latitude as number] },
      properties: { name: p.name, locality: p.locality, status: p.status },
    }));
}

// Fetch one area's GeoJSON and normalise to a flat feature list (endpoint may return a
// FeatureCollection, a single Feature, or a bare Geometry). Failures resolve to [].
async function fetchAreaFeatures(rec: MapAreaRecord): Promise<FeatureList> {
  if (!rec.gazetteer || !rec.areaId) return [];
  try {
    const res = await fetch(areaGeojsonUrl(rec.gazetteer, rec.areaId));
    if (!res.ok) return [];
    const json = (await res.json()) as GeoJson;
    let features: Feature[] = [];
    if (json.type === 'FeatureCollection') features = json.features;
    else if (json.type === 'Feature') features = [json];
    else features = [{ type: 'Feature', geometry: json as Geometry, properties: {} }];
    return features.map((f) => ({ ...f, properties: { ...f.properties, name: rec.name } }));
  } catch {
    return [];
  }
}

function fc(features: FeatureList): FeatureCollection {
  return { type: 'FeatureCollection', features };
}

function setVisible(map: maplibregl.Map, ids: string[], visible: boolean) {
  for (const id of ids) {
    if (map.getLayer(id)) {
      try {
        map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none');
      } catch {
        /* layer not ready yet */
      }
    }
  }
}

export default function MapView({
  colId,
  checklistKey,
  distributions,
  typeSpecimens,
  layers,
  gbifEnabled,
}: MapViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const [failed, setFailed] = useState(false);
  // Latest visibility props, read inside async callbacks after layers get added.
  const visRef = useRef({ layers, gbifEnabled });
  visRef.current = { layers, gbifEnabled };

  // Build (or rebuild) the map whenever the underlying data changes. Visibility toggles are
  // handled by the lighter effect below without a rebuild.
  useEffect(() => {
    if (!containerRef.current) return;
    let cancelled = false;
    let map: maplibregl.Map;
    try {
      map = new maplibregl.Map({
        container: containerRef.current,
        style: POSITRON_STYLE,
        center: [0, 20],
        zoom: 1,
        attributionControl: { compact: true },
      });
    } catch {
      // WebGL unavailable / init failure: degrade to a notice, never crash the panel.
      setFailed(true);
      return;
    }
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl(), 'top-right');

    const applyVisibility = () => {
      const { layers: l, gbifEnabled: g } = visRef.current;
      setVisible(map, LAYER_IDS.distFocal, l.distFocal);
      setVisible(map, LAYER_IDS.distChildren, l.distChildren);
      setVisible(map, LAYER_IDS.typeFocal, l.typeFocal);
      setVisible(map, LAYER_IDS.typeChildren, l.typeChildren);
      setVisible(map, LAYER_IDS.gbif, g);
    };

    const build = async () => {
      // Distribution polygons: fetch all coded areas, partitioned focal vs children.
      const focalRecs = distributions.filter((d) => d.focal && d.gazetteer && d.areaId);
      const childRecs = distributions.filter((d) => !d.focal && d.gazetteer && d.areaId);
      const [focalGroups, childGroups] = await Promise.all([
        Promise.all(focalRecs.map(fetchAreaFeatures)),
        Promise.all(childRecs.map(fetchAreaFeatures)),
      ]);
      if (cancelled || !mapRef.current) return;
      const focalFeatures = focalGroups.flat();
      const childFeatures = childGroups.flat();

      const addAreaGroup = (key: 'dist-focal' | 'dist-children', feats: FeatureList, color: string) => {
        if (feats.length === 0) return;
        map.addSource(key, { type: 'geojson', data: fc(feats) });
        map.addLayer({
          id: `${key}-fill`,
          type: 'fill',
          source: key,
          paint: { 'fill-color': color, 'fill-opacity': 0.3 },
        });
        map.addLayer({
          id: `${key}-line`,
          type: 'line',
          source: key,
          paint: { 'line-color': color, 'line-width': 1 },
        });
      };
      addAreaGroup('dist-focal', focalFeatures, DIST_FOCAL_FILL);
      addAreaGroup('dist-children', childFeatures, DIST_CHILDREN_FILL);

      const addPointGroup = (id: 'type-focal' | 'type-children', focal: boolean, color: string) => {
        const feats = pointsToFeatures(typeSpecimens, focal);
        if (feats.length === 0) return;
        map.addSource(id, { type: 'geojson', data: fc(feats) });
        map.addLayer({
          id,
          type: 'circle',
          source: id,
          paint: {
            'circle-radius': 5,
            'circle-color': color,
            'circle-stroke-width': 1,
            'circle-stroke-color': '#fff',
          },
        });
      };
      addPointGroup('type-focal', true, TYPE_FOCAL_COLOR);
      addPointGroup('type-children', false, TYPE_CHILDREN_COLOR);

      // GBIF occurrence-density raster (only meaningful once matched to COL).
      if (colId) {
        map.addSource('gbif', {
          type: 'raster',
          tiles: [gbifTileUrl(colId, checklistKey)],
          tileSize: 512,
        });
        map.addLayer({ id: 'gbif-density', type: 'raster', source: 'gbif', paint: { 'raster-opacity': 0.7 } });
      }

      applyVisibility();
    };

    map.on('load', () => {
      if (cancelled) return;
      build().catch(() => setFailed(true));
    });
    map.on('error', () => {
      /* tile / source errors are non-fatal; keep the basemap */
    });

    return () => {
      cancelled = true;
      mapRef.current = null;
      try {
        map.remove();
      } catch {
        /* already gone */
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [colId, checklistKey, distributions, typeSpecimens]);

  // Cheap visibility updates when a checkbox toggles (no rebuild / re-fetch).
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    setVisible(map, LAYER_IDS.distFocal, layers.distFocal);
    setVisible(map, LAYER_IDS.distChildren, layers.distChildren);
    setVisible(map, LAYER_IDS.typeFocal, layers.typeFocal);
    setVisible(map, LAYER_IDS.typeChildren, layers.typeChildren);
    setVisible(map, LAYER_IDS.gbif, gbifEnabled);
  }, [layers, gbifEnabled]);

  if (failed) {
    return (
      <Alert color="yellow" variant="light">
        The map could not be initialised in this browser (WebGL may be unavailable).
      </Alert>
    );
  }
  return <div ref={containerRef} style={{ height: 360, width: '100%', borderRadius: 4 }} />;
}
