import { api } from './client';

export interface MapAreaRecord {
  usageId: number;
  name: string | null;
  focal: boolean;
  gazetteer: string | null;
  areaId: string | null;
  area: string | null;
}

export interface MapPointRecord {
  usageId: number;
  name: string | null;
  focal: boolean;
  status: string | null;
  latitude: number | null;
  longitude: number | null;
  locality: string | null;
}

export interface MapData {
  colId: string | null;
  distributions: MapAreaRecord[];
  typeSpecimens: MapPointRecord[];
}

// GET /api/projects/{pid}/usages/{usageId}/map -- distributions + type-specimen points for the
// usage AND its whole (accepted) subtree, for the map view (Task 7).
export function getMapData(pid: number, usageId: number): Promise<MapData> {
  return api<MapData>(`/api/projects/${pid}/usages/${usageId}/map`);
}
