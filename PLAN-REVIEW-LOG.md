# Plan Review Log: Hermes Mobile UI redesign (Hermes Teal)

Full plan: C:/Users/chris/.claude/plans/misty-floating-heron.md (approved via plan mode).


Act 1 (grill-with-docs) complete — plan locked. MAX_ROUNDS=5. Codex model: gpt-5.6-luna (config.toml default).

## Round 1 — Codex

16 findings, VERDICT: REVISE. Highlights: `libs.versions.toml` doesn't exist (plan bug — deps are inline); Lucide symbols unverified; `Search` icon omitted from mapping; AGSL applied to root would grain text; edge-to-edge/system bars unaddressed for targetSdk 35; muted-text contrast; 34–39dp touch targets below 48dp; Material Typography not actually wired by setting a token; ambiguous font weights; non-reproducible font download; incomplete M3 color roles (purple leaks); remaining hardcoded color literals; color-only status; fixed sizes clip at large font scale/320dp; verification too manual.

### Claude's response (arbiter)

Accepted 12: inline-gradle fix, Lucide pin+compile gate, full icon inventory (+Search), background-layer-only atmosphere, edge-to-edge config, muted-contrast raise, 48dp targets, explicit font weights, committed pinned fonts, complete colorScheme roles, full color-literal inventory, font-scale/width resilience. Partially accepted: perf (static uniform-free shader in isolated layer + device check, no benchmarking mandate); verification (device matrix + rg gates + TalkBack pass, no screenshot-test infra — out of scope); typography (token TextStyles serve the goal; wiring Material Typography rejected as larger diff for same result). Rejected: hairline-divider contrast (decorative separators are WCAG-exempt; interactive borders addressed separately); "null contentDescription wrong" (null = decorative is correct Compose semantics; status already textual).

## Round 2 — Codex

10 findings, VERDICT: REVISE. Prior findings largely addressed; new: license file invalid inside `res/font`; AGSL mechanism unspecified; layer isolation not guaranteed by composable separation alone; system-bar icons over amber glow; colorScheme still missing roles (containers/inverse/surfaceTint/scrim); TextStyle tokens don't bind M3 component slots; verification grep misses non-hex color/brush constructions; unicode symbols read as mojibake; Lucide version still unpinned; API 26/29 nav-bar scrim behavior unspecified.

### Claude's response (arbiter)

Accepted 9: license to repo-root `THIRD-PARTY-LICENSES.md`; concrete AGSL spec (`drawWithCache` + `ShaderBrush(RuntimeShader)` in `@RequiresApi(33)` helper); cache/isolation requirement + streaming redraw verification; glow clamped near-Abyss in top ~40dp band for status-bar icon contrast; ALL `darkColorScheme` params set explicitly; explicit migration rule — every Text call site + M3 component slots use token styles (full Typography wiring still rejected, same rationale); broadened audit `rg "Color\(|Color\.|Brush\."`; ASCII-normalized thresholds (>=, ->); Lucide pinned NOW — `com.composables:icons-lucide:1.1.0` verified on Maven Central via search.maven.org; `SystemBarStyle.dark(TRANSPARENT)` + `isNavigationBarContrastEnforced=false` on 29+, tested both nav modes. Rejected: none outright — mojibake was reviewer-side codepage but ASCII normalization applied anyway (robustness > blame).

## Round 3 — Codex

4 findings, VERDICT: REVISE. Round-2 items confirmed addressed. New: JetBrains Mono license is OFL-1.1 not Apache-2.0 (desktop's own CSS comment is stale); Lucide attribution omitted; `drawWithCache` caches objects not pixels — not layer isolation; `surfaceContainer*` darkColorScheme params unverified against pinned M3 version.

### Claude's response (arbiter)

Accepted all 4: license corrected to OFL-1.1 with full text + reserved-name notice; Lucide ISC attribution added to `THIRD-PARTY-LICENSES.md`; explicit `Modifier.graphicsLayer()` (own RenderNode) added ahead of `drawWithCache` for true pixel isolation, profiling check retained; Compose BOM 2024.12.01 → M3 1.3.x verified in app/build.gradle.kts, `surfaceContainer*` overload present since 1.2.0 — recorded in plan.

## Round 4 — Codex

2 findings, VERDICT: REVISE. Round-3 items confirmed addressed. New: `surfaceBright`/`surfaceDim` omitted from the enumerated colorScheme roles; repo-root license file doesn't ship in the APK — OFL notices must accompany the distributed font.

### Claude's response (arbiter)

Accepted both: surfaceBright/surfaceDim added to the explicit role list; licenses also bundled as `app/src/main/assets` asset + minimal "Open-source licenses" row on Host screen showing the text in a scrollable dialog (smallest compliant surface — full About screen rejected as scope creep).

## Round 5 — Codex

**VERDICT: APPROVED.** "Rev 5 packages license notices in assets, exposes them in-app, and explicitly includes surfaceBright/surfaceDim. No new material implementation-breaking flaws found."

Converged in 5 rounds (16 -> 10 -> 4 -> 2 -> 0 findings). Reviewer: Codex gpt-5.6-luna, read-only sandbox every round.
