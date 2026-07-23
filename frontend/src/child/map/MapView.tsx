import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Alert } from '@mantine/core';
import type { Feature, FeatureCollection, GeoJSON as GeoJson } from 'geojson';
import type { MapAreaRecord, MapPointRecord } from '../../api/map';
import { areaGeojsonUrl, gbifTileUrl } from './mapUrls';
import { boundsOfFeatures } from './mapBounds';
import { areaPopupHtml, colorForEstablishment, type AreaPopupProps } from './distributionStyle';

// The ONLY module that imports maplibre-gl. Kept behind React.lazy from DistributionMapPanel so
// the ~230KB (gzip) maplibre bundle lands in its own chunk and stays out of the main entry.

const POSITRON_STYLE = 'https://basemaps.cartocdn.com/gl/positron-gl-style/style.json';

// Distribution polygons are coloured per-feature by establishment means (see distributionStyle,
// mirroring ChecklistBank); focal vs descendants are distinguished by fill opacity + outline width
// instead of hue. Type-specimen points keep distinct focal/children hues.
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
  gbifAvailable: boolean;
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
// FeatureCollection or a single Feature). Unrecognised shapes and failures resolve to [].
async function fetchAreaFeatures(rec: MapAreaRecord): Promise<FeatureList> {
  if (!rec.gazetteer || !rec.areaId) return [];
  try {
    const res = await fetch(areaGeojsonUrl(rec.gazetteer, rec.areaId), {
      headers: { Accept: 'application/geo+json' },
    });
    if (!res.ok) return [];
    const json = (await res.json()) as GeoJson;
    // The gazetteer area endpoint content-negotiates between a plain vocab-term JSON and real
    // GeoJSON on the same path. Only trust shapes we recognise as GeoJSON; anything else (e.g.
    // a vocab object returned despite the Accept header) is skipped rather than wrapped into a
    // malformed feature.
    let features: Feature[];
    if (json.type === 'FeatureCollection') features = json.features;
    else if (json.type === 'Feature') features = [json];
    else return [];
    // Attach the whole distribution record (for the click popover) + a precomputed establishment
    // colour (data-driven fill via ['get','color']).
    return features.map((f) => ({
      ...f,
      properties: {
        ...f.properties,
        name: rec.name,
        area: rec.area,
        areaId: rec.areaId,
        gazetteer: rec.gazetteer,
        establishmentMeans: rec.establishmentMeans,
        threatStatus: rec.threatStatus,
        referenceId: rec.referenceId,
        remarks: rec.remarks,
        color: colorForEstablishment(rec.establishmentMeans),
      },
    }));
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
  gbifAvailable,
}: MapViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const [failed, setFailed] = useState(false);
  // Latest visibility props, read inside async callbacks after layers get added.
  const visRef = useRef({ layers, gbifEnabled, gbifAvailable });
  visRef.current = { layers, gbifEnabled, gbifAvailable };

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
      map.addControl(new maplibregl.NavigationControl(), 'top-right');
    } catch {
      // WebGL unavailable / init failure: degrade to a notice, never crash the panel.
      setFailed(true);
      return;
    }
    mapRef.current = map;

    const applyVisibility = () => {
      const { layers: l, gbifEnabled: g, gbifAvailable: ga } = visRef.current;
      setVisible(map, LAYER_IDS.distFocal, l.distFocal);
      setVisible(map, LAYER_IDS.distChildren, l.distChildren);
      setVisible(map, LAYER_IDS.typeFocal, l.typeFocal);
      setVisible(map, LAYER_IDS.typeChildren, l.typeChildren);
      setVisible(map, LAYER_IDS.gbif, g && ga);
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

      const addAreaGroup = (
        key: 'dist-focal' | 'dist-children',
        feats: FeatureList,
        fillOpacity: number,
        lineWidth: number,
      ) => {
        if (feats.length === 0) return;
        map.addSource(key, { type: 'geojson', data: fc(feats) });
        map.addLayer({
          id: `${key}-fill`,
          type: 'fill',
          source: key,
          // Per-feature establishment colour, precomputed onto each feature's `color` property.
          paint: { 'fill-color': ['get', 'color'], 'fill-opacity': fillOpacity },
        });
        map.addLayer({
          id: `${key}-line`,
          type: 'line',
          source: key,
          paint: { 'line-color': ['get', 'color'], 'line-width': lineWidth },
        });
      };
      // Focal = more opaque + thicker outline; descendants fainter -- hue is reserved for establishment.
      addAreaGroup('dist-focal', focalFeatures, 0.55, 2);
      addAreaGroup('dist-children', childFeatures, 0.3, 1);

      // Click a distribution polygon -> a popover with the complete distribution record.
      const popup = new maplibregl.Popup({ closeButton: true, closeOnClick: true });
      const areaClick = (e: maplibregl.MapLayerMouseEvent) => {
        const f = e.features?.[0];
        if (!f) return;
        popup.setLngLat(e.lngLat).setHTML(areaPopupHtml(f.properties as AreaPopupProps)).addTo(map);
      };
      for (const id of ['dist-focal-fill', 'dist-children-fill']) {
        map.on('click', id, areaClick);
        map.on('mouseenter', id, () => {
          map.getCanvas().style.cursor = 'pointer';
        });
        map.on('mouseleave', id, () => {
          map.getCanvas().style.cursor = '';
        });
      }

      const typeFocalFeats = pointsToFeatures(typeSpecimens, true);
      const typeChildFeats = pointsToFeatures(typeSpecimens, false);
      const addPointGroup = (id: 'type-focal' | 'type-children', feats: FeatureList, color: string) => {
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
      addPointGroup('type-focal', typeFocalFeats, TYPE_FOCAL_COLOR);
      addPointGroup('type-children', typeChildFeats, TYPE_CHILDREN_COLOR);

      // Fit the initial view to the taxon's rendered geometry (polygons + type points) instead of
      // opening on the whole world -- the single biggest "preview" win. Done once here on build,
      // not on every visibility toggle, so flipping a checkbox never re-pans the map. GBIF's raster
      // has no vector bounds and is global anyway, so it doesn't participate; when there is no
      // coded geometry at all (free-text areas / GBIF-only), bounds is null and the world view
      // stays.
      const fitBounds = boundsOfFeatures([
        ...focalFeatures,
        ...childFeatures,
        ...typeFocalFeats,
        ...typeChildFeats,
      ]);
      if (fitBounds) {
        map.fitBounds(fitBounds, { padding: 40, maxZoom: 6, duration: 0 });
      }

      // GBIF occurrence-density raster (only meaningful once matched to COL). Gated on colId only
      // (not gbifEnabled or gbifAvailable): this mirrors every other layer group above, which is
      // added unconditionally and then shown/hidden by applyVisibility() below, so toggling the
      // checkbox — or a preflight count resolving — stays a cheap visibility flip and never needs
      // a map rebuild. An invisible raster costs nothing: maplibre doesn't request tiles for a
      // layer whose visibility is 'none'.
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

  // Cheap visibility updates when a checkbox toggles, or the GBIF preflight count resolves (no
  // rebuild / re-fetch). gbifAvailable only ever affects visibility here — never the heavy build
  // effect above — so a zero-count result hides the (already-created) GBIF layer in place instead
  // of tearing down and rebuilding the whole map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    setVisible(map, LAYER_IDS.distFocal, layers.distFocal);
    setVisible(map, LAYER_IDS.distChildren, layers.distChildren);
    setVisible(map, LAYER_IDS.typeFocal, layers.typeFocal);
    setVisible(map, LAYER_IDS.typeChildren, layers.typeChildren);
    setVisible(map, LAYER_IDS.gbif, gbifEnabled && gbifAvailable);
  }, [layers, gbifEnabled, gbifAvailable]);

  if (failed) {
    return (
      <Alert color="yellow" variant="light">
        The map could not be initialised in this browser (WebGL may be unavailable).
      </Alert>
    );
  }
  return <div ref={containerRef} style={{ height: 360, width: '100%', borderRadius: 4 }} />;
}
