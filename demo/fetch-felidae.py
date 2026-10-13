#!/usr/bin/env python3
"""Build the Blixa playground dataset (demo/felidae/) from the Catalogue of Life.

Pulls the extant Felidae from the latest COL release in ChecklistBank (dataset 3LXR) via the
public API -- accepted taxa, their synonyms (incl. basionym links), references, a few vernacular
languages and distributions -- and writes them as a ColDP folder. It then applies the deliberate
*playground flaws* (see PLAYGROUND FLAWS below and demo/README.md) that the tutorial and how-to
guides ask the reader to find and fix.

Usage:  python3 -I demo/fetch-felidae.py [--cache DIR]
Then:   demo/build.sh   (zips demo/felidae/ into the frontend's public/help/demo/)

Only the standard library is used. Re-running overwrites demo/felidae/*.tsv and metadata.yaml.
"""
import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

API = 'https://api.checklistbank.org'
DATASET = '3LXR'          # latest COL release
ROOT = '623RM'            # Felidae Fischer de Waldheim, 1817
LANGUAGES = {'eng', 'deu', 'fra', 'spa'}
MAJOR_RANKS = {'kingdom', 'phylum', 'class', 'order'}   # higher classification kept above Felidae
OUT = Path(__file__).resolve().parent / 'felidae'


def get(path, cache) -> dict:
    f = cache / (path.strip('/').replace('/', '_').replace('?', '_').replace('&', '_') + '.json')
    if f.exists():
        return json.loads(f.read_text())
    for attempt in range(4):
        try:
            with urllib.request.urlopen(API + path, timeout=60) as r:
                data = json.load(r)
            f.write_text(json.dumps(data))
            return data
        except Exception as e:  # noqa: BLE001 -- retry transient API hiccups
            if attempt == 3:
                raise
            print(f'  retry {path}: {e}', file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    raise AssertionError('unreachable')


def year_of(name):
    for k in ('combinationAuthorship', 'basionymAuthorship'):
        y = (name.get(k) or {}).get('year')
        if y:
            return y
    return ''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cache', type=Path, default=Path('/tmp/blixa-felidae-cache'))
    args = ap.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)

    usages = {}       # id -> NameUsage row (dict)
    order = []        # output order
    refs = {}         # id -> Reference row
    vernaculars, distributions, relations = [], [], []

    def add_ref(ref):
        if not ref or ref['id'] in refs:
            return
        csl = ref.get('csl') or {}
        authors = '; '.join(
            ', '.join(x for x in (a.get('family'), a.get('given')) if x) or a.get('literal', '')
            for a in csl.get('author', [])
        )
        issued = ''
        parts = (csl.get('issued') or {}).get('date-parts') or []
        if parts and parts[0]:
            issued = '-'.join(str(p) for p in parts[0])
        elif ref.get('year'):
            issued = str(ref['year'])
        refs[ref['id']] = {
            'col:ID': ref['id'],
            'col:citation': ref.get('citation', ''),
            'col:author': authors,
            'col:title': csl.get('title', ''),
            'col:containerTitle': csl.get('container-title', ''),
            'col:issued': issued,
            'col:volume': csl.get('volume', ''),
            'col:page': csl.get('page', ''),
            'col:doi': csl.get('DOI', ''),
            'col:link': csl.get('URL', ''),
        }

    def add_usage(u, parent_id=None, accepted_id=None):
        n = u['name']
        status = u['status']
        usages[u['id']] = {
            'col:ID': u['id'],
            'col:parentID': accepted_id if status in ('synonym', 'ambiguous synonym', 'misapplied') else (parent_id or u.get('parentId', '')),
            'col:basionymID': '',
            'col:status': status,
            'col:scientificName': n['scientificName'],
            'col:authorship': n.get('authorship', ''),
            'col:rank': n.get('rank', ''),
            'col:code': 'zoological',
            'col:nameReferenceID': n.get('publishedInId', ''),
            'col:namePublishedInPage': n.get('publishedInPage', ''),
            'col:namePublishedInYear': n.get('publishedInYear', '') or year_of(n),
            'col:extinct': 'true' if u.get('extinct') else '',
            'col:link': n.get('link', ''),
            'col:remarks': '',
        }
        order.append(u['id'])

    # 1. Higher classification above Felidae (Animalia ... Carnivora) so the tree is realistic.
    root_info = get(f'/dataset/{DATASET}/taxon/{ROOT}/info', args.cache)
    parent = ''
    for c in root_info.get('classification', []):  # root-first
        if c['id'] == ROOT or c['rank'] not in MAJOR_RANKS:
            continue
        usages[c['id']] = {
            'col:ID': c['id'], 'col:parentID': parent, 'col:basionymID': '', 'col:status': 'accepted',
            'col:scientificName': c['name'], 'col:authorship': c.get('authorship', ''),
            'col:rank': c['rank'], 'col:code': 'zoological', 'col:nameReferenceID': '',
            'col:namePublishedInPage': '', 'col:namePublishedInYear': '', 'col:extinct': '', 'col:link': '',
            'col:remarks': '',
        }
        order.append(c['id'])
        parent = c['id']

    # 2. Every extant accepted taxon in the Felidae subtree, then its full info.
    hits = get(f'/dataset/{DATASET}/nameusage/search?TAXON_ID={ROOT}&extinct=false&limit=500', args.cache)
    accepted_ids = [h['id'] for h in hits['result']]
    print(f'{len(accepted_ids)} extant accepted taxa', file=sys.stderr)
    infos = {}
    for i, tid in enumerate(accepted_ids):
        infos[tid] = get(f'/dataset/{DATASET}/taxon/{tid}/info', args.cache)
        if i % 20 == 0:
            print(f'  {i}/{len(accepted_ids)}', file=sys.stderr)

    # Parents first, so the TSV reads top-down (Felidae has no extant-only parent gaps).
    depth = {tid: len(infos[tid].get('classification', [])) for tid in accepted_ids}
    for tid in sorted(accepted_ids, key=lambda t: (depth[t], infos[t]['usage']['name']['scientificName'])):
        info = infos[tid]
        add_usage(info['usage'], parent_id=parent if tid == ROOT else None)
        add_ref(info.get('publishedIn'))
        syn = info.get('synonyms') or {}
        for s in (syn.get('homotypic') or []) + (syn.get('heterotypic') or []) + (syn.get('misapplied') or []):
            if s['id'] not in usages:
                add_usage(s, accepted_id=tid)
        for ref in (info.get('references') or {}).values():
            add_ref(ref)
        for v in info.get('vernacularNames') or []:
            if v.get('language') in LANGUAGES:
                vernaculars.append({'col:taxonID': tid, 'col:name': v['name'], 'col:language': v['language']})
        for d in info.get('distributions') or []:
            area = d.get('area') or {}
            distributions.append({
                'col:taxonID': tid,
                'col:areaID': area.get('id', '') if area.get('gazetteer') != 'text' else '',
                'col:area': area.get('name', '') if area.get('gazetteer') == 'text' else '',
                'col:gazetteer': area.get('gazetteer', 'text'),
                'col:establishmentMeans': d.get('establishmentMeans', ''),
            })
        relations.extend(info.get('nameRelations') or [])

    # 3. Basionym links: COL relations are name<->name; in a NameUsage file the usage ID is the name.
    for r in relations:
        if r.get('type') == 'basionym':
            uid, bid = r.get('usageId'), r.get('relatedUsageId')
            if uid in usages and bid in usages and uid != bid:
                usages[uid]['col:basionymID'] = bid

    simplify(usages, order)
    apply_playground_flaws(usages, order, refs)

    write(OUT / 'NameUsage.tsv', [usages[i] for i in order])
    write(OUT / 'Reference.tsv', list(refs.values()))
    write(OUT / 'VernacularName.tsv', vernaculars)
    write(OUT / 'Distribution.tsv', distributions)
    write_metadata()
    print(f'wrote {len(order)} usages, {len(refs)} references, {len(vernaculars)} vernaculars, '
          f'{len(distributions)} distributions to {OUT}', file=sys.stderr)


def simplify(usages, order):
    """Trim real-data noise so the tutorial's flaws stand out on the Issues page.

    - synonyms whose rank differs from their accepted name (mostly subspecies names sunk into a
      species) would each raise a synonym_rank_differs warning -> dropped;
    - autonyms (e.g. Panthera leo leo) carrying their own authorship would each raise
      superfluous_authorship -> authorship cleared.
    """
    for uid in list(order):
        u = usages[uid]
        acc = usages.get(u['col:parentID'])
        if u['col:status'] == 'synonym' and acc and acc['col:rank'] != u['col:rank']:
            del usages[uid]
            order.remove(uid)
    for u in usages.values():
        parts = u['col:scientificName'].split()
        if len(parts) == 3 and parts[1] == parts[2]:
            u['col:authorship'] = ''
    # basionym links to dropped synonyms
    for u in usages.values():
        if u['col:basionymID'] and u['col:basionymID'] not in usages:
            u['col:basionymID'] = ''


# ---------------------------------------------------------------------------------------------
# PLAYGROUND FLAWS -- deliberate problems for the tutorial / how-tos. Keep demo/README.md in sync.
# All added rows use IDs prefixed "PG" so they're easy to spot.
# ---------------------------------------------------------------------------------------------
def apply_playground_flaws(usages, order, refs):
    by_name = {}
    for u in usages.values():
        by_name.setdefault(u['col:scientificName'], []).append(u)

    def one(name, status=None):
        cands = [u for u in by_name.get(name, []) if status is None or u['col:status'] == status]
        if not cands:
            sys.exit(f'playground flaw: {name!r} ({status}) not found in the COL data')
        return cands[0]

    def add(row):
        base = {k: '' for k in next(iter(usages.values()))}
        base.update({'col:code': 'zoological'})
        base.update(row)
        usages[row['col:ID']] = base
        order.append(row['col:ID'])
        by_name.setdefault(row['col:scientificName'], []).append(base)

    panthera = one('Panthera', 'accepted')
    felis = one('Felis', 'accepted')
    lynx = one('Lynx', 'accepted')

    # F1 missing authorship -- the tutorial's first edit.
    one('Lynx pardinus', 'accepted')['col:authorship'] = ''

    # F2 misspelled duplicate of the tiger, accepted next to the real one -> Demote it to a synonym.
    add({'col:ID': 'PG1', 'col:parentID': panthera['col:ID'], 'col:status': 'accepted',
         'col:scientificName': 'Panthera tigirs', 'col:authorship': '(Linnaeus, 1758)',
         'col:rank': 'species', 'col:remarks': 'Playground flaw F2: misspelling of Panthera tigris.'})

    # F3 exact duplicate -> DuplicateNameRule, fixed with Merge selected.
    add({'col:ID': 'PG2', 'col:parentID': lynx['col:ID'], 'col:status': 'accepted',
         'col:scientificName': 'Lynx rufus', 'col:authorship': '(Schreber, 1777)', 'col:rank': 'species',
         'col:remarks': 'Playground flaw F3: duplicate of Lynx rufus.'})

    # F4 homotypic conflict: the cougar's basionym also kept as an accepted Felis species.
    fc = by_name.get('Felis concolor')
    if fc:
        fc = fc[0]
        fc['col:status'] = 'accepted'
        fc['col:parentID'] = felis['col:ID']
        fc['col:remarks'] = 'Playground flaw F4: homotypic with Puma concolor.'
    else:
        add({'col:ID': 'PG3', 'col:parentID': felis['col:ID'], 'col:status': 'accepted',
             'col:scientificName': 'Felis concolor', 'col:authorship': 'Linnaeus, 1771',
             'col:rank': 'species', 'col:remarks': 'Playground flaw F4: homotypic with Puma concolor.'})

    # F5 species filed in the wrong genus -> GenusMismatchRule, fixed with Move / rename.
    serval = one('Leptailurus serval', 'accepted')
    serval['col:parentID'] = felis['col:ID']
    serval['col:remarks'] = 'Playground flaw F5: filed under Felis.'

    # F6 a look-alike Cyrillic letter (U+0430 'а' for Latin 'a') pasted into a name -- invisible,
    # but it breaks search and matching -> SuspiciousNameCharactersRule, fixed by retyping the name.
    amur = one('Panthera pardus orientalis', 'accepted')
    amur['col:scientificName'] = 'Panthera pardus orient\u0430lis'
    amur['col:remarks'] = 'Playground flaw F6: Cyrillic look-alike letter in the epithet.'

    # F7 a synonym whose accepted name is itself a synonym -> SynonymOfNonAcceptedRule.
    leo_syn = one('Felis leo', 'synonym')
    add({'col:ID': 'PG5', 'col:parentID': leo_syn['col:ID'], 'col:status': 'synonym',
         'col:scientificName': 'Leo leo', 'col:authorship': '(Linnaeus, 1758)', 'col:rank': 'species',
         'col:remarks': 'Playground flaw F7: points at a synonym.'})

    # F8 unparsable placeholder name -> UnparsableNameRule.
    add({'col:ID': 'PG6', 'col:parentID': felis['col:ID'], 'col:status': 'provisionally accepted',
         'col:scientificName': 'Felis sp. "Bornean form"', 'col:rank': 'species',
         'col:remarks': 'Playground flaw F8: informal placeholder name.'})

    # F9 provisional (UNASSESSED) names awaiting review: species without synonyms, so the
    # provisional status doesn't also flag their synonyms as "synonym of a non-accepted name".
    has_syn = {u['col:parentID'] for u in usages.values() if u['col:status'] == 'synonym'}
    # (the recently split pampas-cat species -- plausibly still "awaiting review")
    for n in ('Leopardus garleppi', 'Leopardus munoai', 'Leopardus narinensis'):
        u = (by_name.get(n) or [None])[0]
        if u and u['col:status'] == 'accepted' and u['col:ID'] not in has_syn:
            u['col:status'] = 'provisionally accepted'

    # F10 a reference without its DOI -> Find DOI.
    refs['PGR1'] = {
        'col:ID': 'PGR1',
        'col:citation': 'Johnson, W. E., Eizirik, E., Pecon-Slattery, J., et al. (2006). The late '
                        'Miocene radiation of modern Felidae: a genetic assessment. Science 311: 73-77.',
        'col:author': 'Johnson, W. E.; Eizirik, E.; Pecon-Slattery, J.; Murphy, W. J.; Antunes, A.; '
                      'Teeling, E.; O\'Brien, S. J.',
        'col:title': 'The late Miocene radiation of modern Felidae: a genetic assessment',
        'col:containerTitle': 'Science', 'col:issued': '2006', 'col:volume': '311',
        'col:page': '73-77', 'col:doi': '', 'col:link': '',
    }


def write(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    cols = []
    for r in rows:
        for k in r:
            if k not in cols:
                cols.append(k)
    clean = lambda v: (v or '').replace('\t', ' ').replace('\n', ' ')  # noqa: E731
    with path.open('w', encoding='utf-8') as f:
        f.write('\t'.join(cols) + '\n')
        for r in rows:
            f.write('\t'.join(clean(r.get(c)) for c in cols) + '\n')


def write_metadata():
    (OUT / 'metadata.yaml').write_text(f"""title: Blixa playground – Felidae
description: >-
  A practice dataset for the Blixa checklist editor: the extant cats (Felidae) taken from the
  Catalogue of Life, with a handful of deliberate errors added for the Blixa tutorial (see the
  Remarks of names with IDs starting PG). Not for scientific use.
license: CC-BY-4.0
taxonomicScope: Felidae (extant)
geographicScope: Global
source:
  - title: Catalogue of Life
    citation: Bánki, O. et al. Catalogue of Life (ChecklistBank dataset {DATASET}), retrieved {time.strftime('%Y-%m-%d')}.
    url: https://www.checklistbank.org/dataset/{DATASET}
""", encoding='utf-8')


if __name__ == '__main__':
    main()
