## Chart Time Navigation (2026-11-07)

**Decision:** Standardized chart time-browsing across the app via a reusable module `ui/charts/ChartTimeNav.kt`:
- `shortRangeLabel(from, to)` renders a compact "Sep 3 – Oct 3" date-range label (years appended when they differ).
- `Modifier.chartPan(enabled, onPan)` attaches a horizontal-drag gesture (80px threshold, one trigger per gesture) mapped to pan-older/pan-newer.
- `ChartTimeNavRow(label, canGoBack, canGoForward, onBack, onForward)` renders ‹ › icon buttons with the centered small range label.

Applied to three surfaces:
1. **Nutrient detail page** (`ui/nutrient/`): window chips now 7d/30d/90d/1y/All (rangeDays=0 means All). `loadSeries` queries full history once ("2000-01-01"→today) to derive the earliest data day; panning (step = days/2) is clamped by `maxOffset = span - days`; "All" spans earliest→today and disables panning.
2. **Insights** (`InsightsViewModel`): added D365("1y") and ALL("All") windows; ALL's `from` = min(first score snapshot day, first ledger day via dailyTotalsForWindow full scan), fallback 364d.
3. **History** (`HistoryViewModel`): already had 1y/All; added `_offset`/`_maxOffset` StateFlows folded into a combined `SelWin` flow driving `loadDetail(def, win, offset)`; offsets clamped per-nutrient to earliest data day; `HistoryScreen` chart card gained ChartTimeNavRow + swipeable chart.

**Rationale:** Users previously hit a 90-day ceiling with no way to inspect older data. Full-history single-query + offset clamping avoids re-querying per pan and guarantees pan stops exactly at the first day of recorded data. Gesture + arrows + visible range label cover intuitive, discoverable, and accessible time browsing.

**Consequences:** Fixed windows (not All) pan; All view is inherently static. Panning triggers a full series recompute per step (acceptable: queries are one Room call each).