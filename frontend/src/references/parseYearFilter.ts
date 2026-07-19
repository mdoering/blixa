export interface YearFilter {
  yearFrom?: number;
  yearTo?: number;
}

// Parse a user-typed year filter into inclusive numeric bounds sent to the reference search API.
//   "1941"       -> exact year          { yearFrom: 1941, yearTo: 1941 }
//   "1941-1944"  -> closed range        { yearFrom: 1941, yearTo: 1944 }
//   "1941-"      -> open upper end      { yearFrom: 1941 }
//   "-1944"      -> open lower end      { yearTo: 1944 }
// Blank or non-numeric input yields {} (no filter). Whitespace around either bound is ignored.
export function parseYearFilter(input: string): YearFilter {
  const trimmed = input.trim();
  if (!trimmed) return {};

  const toYear = (s: string): number | undefined => {
    const t = s.trim();
    return /^\d+$/.test(t) ? Number(t) : undefined;
  };

  if (trimmed.includes('-')) {
    const [fromRaw, toRaw = ''] = trimmed.split('-');
    const yearFrom = toYear(fromRaw);
    const yearTo = toYear(toRaw);
    const out: YearFilter = {};
    if (yearFrom !== undefined) out.yearFrom = yearFrom;
    if (yearTo !== undefined) out.yearTo = yearTo;
    return out;
  }

  const year = toYear(trimmed);
  return year === undefined ? {} : { yearFrom: year, yearTo: year };
}
