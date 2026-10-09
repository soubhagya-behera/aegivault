# Aegivault Design System — "Controlled Contrast"

> Frontend design foundation. This document is the source of truth for visual
> decisions. It pairs with `ANIMATION_SPEC.md` (motion) and
> `src/styles/tokens.css` (the machine-readable tokens). When this file and the
> code disagree, treat it as a bug and reconcile — do not let raw values leak
> into components.

---

## 1. Design intent

Aegivault is a **production data privacy, security, and AI governance
platform**. The interface is an *operational control room* for sensitive data:
it must feel deliberate, editorial, tactile, and technically credible. It is a
place where a developer decides whether a dataset is safe to share, whether an
AI request may be forwarded, and whether the audit ledger still holds.

The visual language is **"Aegivault Controlled Contrast."** Dark graphite
surfaces carry the working application; warm bone paper is reserved for the
public marketing experience to create a hard editorial contrast. A single
restrained oxide-red accent marks interactive intent. Security states use
natural, semantic colors and are **always labeled in text**, never signaled by
color alone.

Tone: **serious but legible.** Dense where data lives, calm where narrative
lives. No theatrics that get in the way of a security decision.

---

## 2. Anti-AI-slop rules (things that must NOT appear)

These are explicit prohibitions. A reviewer should be able to reject a screen
for any of them.

- **No generic purple / neon-violet gradients.** No "AI startup" indigo→pink.
- **No blue-gray template styling** (the default `slate`/`zinc` dashboard look).
- **No glowing glass cards everywhere.** Glassmorphism is not a system. If
  translucency is used once, it must mean something.
- **No large decorative gradient orb** behind the hero as a substitute for art
  direction.
- **No stock SaaS template with different colors.** Same layout (three feature
  cards + a logo strip + a pricing table), recolored, is a rejection.
- **No "six identical icon-circle-heading-description" cards.** Vary
  composition: typography, diagrams, structured metadata, flat regions.
- **No animated gradients in every section.**
- **No random particle fields, cursor-following decoration, or perpetual
  bouncing** in the application.
- **No fake production data.** No hardcoded dashboard counts presented as live,
  no fabricated audit rows, no mock API responses mixed silently with real ones.
- **No compliance claims** (GDPR/HIPAA certification) and the audit ledger is
  **never** called a blockchain — it is a *"tamper-evident cryptographically
  linked audit ledger."*
- **No claiming mock LLM output is a real model response.** When the configured
  provider is MOCK, label it clearly.
- **No exposing** raw PII, uploaded rows, credentials, tokens, or raw server
---

## 3. Color

### 3.1 Palette (starting values)

| Token (semantic)                 | Value     | Purpose                                             |
| -------------------------------- | --------- | --------------------------------------------------- |
| `--color-canvas`                 | `#0B0C0E` | Obsidian application canvas                         |
| `--color-surface`                | `#141619` | Graphite surface (default panels)                   |
| `--color-surface-raised`         | `#1C2022` | Raised surface (inputs, raised rows)                |
| `--color-surface-highest`        | `#232826` | Highest elevation (menus, popovers)                 |
| `--color-surface-paper`          | `#ECE6D8` | Bone/paper — **marketing only**, selective           |
| `--color-text`                   | `#F2EDE2` | Primary light text on dark                          |
| `--color-text-muted`             | `#A7A99F` | Secondary text, labels, metadata                    |
| `--color-text-inverse`           | `#171917` | Dark text on `--color-surface-paper`                |
| `--color-border`                 | `#333833` | Borders and dividers                                |
| `--color-border-strong`          | `#464A45` | Stronger divider for tables (3:1 where applicable)  |
| `--color-accent`                 | `#CF5A3D` | Oxide accent — interactive intent                   |
| `--color-success`                | `#4F9F81` | Positive / security-ALLOW                           |
| `--color-warning`                | `#DA9969` | Warning / review                                    |
| `--color-danger`                 | `#D95D52` | Blocked / error / failed                            |
| `--color-info`                   | `#708285` | Informational accent                                |

Supporting non-semantic raws (hover, badge fills, contrast text) are defined in
`tokens.css` next to each semantic token. Use the semantic names in components;
never scatter hex values through JSX.

### 3.2 Usage rules

- **Dark is the default for the operational dashboard.** `--color-canvas`
  background, `--color-surface` panels, `--color-text` body.
- **Bone paper is a marketing instrument.** Use `--color-surface-paper` with
  `--color-text-inverse` on the public site to punctuate editorial contrast.
  Do not mix large paper regions into the dark dashboard.
- **Oxide is scarce.** It marks primary actions, active navigation, focus, and
  links. If everything is oxide, nothing is.
- **Elevation is restrained.** Prefer flat regions and `--color-border`
  dividers. Reach for `--color-surface-raised` / `-highest` and the elevation
  shadows only when a surface genuinely floats (menus, popovers, modals).

### 3.3 Security-state semantics (critical)

Security states are **meaning, not decoration**. Every state pairs a color with
a **text label and, where useful, a distinct shape/icon**, so status survives
color-blindness and grayscale.

| State              | Color token       | Label    | Typical UI meaning                                             |
| ------------------ | ----------------- | -------- | -------------------------------------------------------------- |
| Allowed / positive | `--color-success` | `ALLOW`  | Gateway verdict ALLOW; run COMPLETED; dataset ready            |
| Review / caution   | `--color-warning` | `REVIEW` | Pending/queued/needs-attention; warnings                       |
| Blocked / error    | `--color-danger`  | `BLOCK`  | Gateway verdict BLOCK; run FAILED; destructive/error states    |
| Informational      | `--color-info`    | `INFO`   | Neutral notes, provider/runtime info                           |

Run lifecycle mapping (from `RunStatus`): `QUEUED` → warning, `RUNNING` →
info (with an activity affordance), `COMPLETED` → success, `FAILED` → danger.
A FAILED run is **never** shown as success because a request returned 2xx;
render the real `errorCode`/`errorStage`/`errorMessage` from the API.

Gateway refusals must stay **distinguishable**: a security `BLOCK` is HTTP 200
data; rate-limit (429), usage-policy rejection (429), token-budget rejection
(429), and provider/infrastructure failure (500) are different and are not
"successful completions." Do not show a rejected prompt as a completion, and do
not render provider payload after a block.

---

## 4. Typography

### 4.1 Pairing

| Role        | Family            | Token          | Fallbacks                            |
| ----------- | ----------------- | -------------- | ------------------------------------ |
| Display     | Space Grotesk     | `--font-display` | Inter → system-ui → sans            |
| UI / body   | Inter             | `--font-sans`    | system-ui → Segoe UI → sans        |
| Technical   | IBM Plex Mono     | `--font-mono`    | ui-monospace → Cascadia → Menlo    |

- **Space Grotesk** for large headings and expressive display type (hero,
  section openers). Headlines may be expressive.
- **Inter** for body text, forms, controls, and general interface content.
- **IBM Plex Mono** for compact metadata: request IDs, hashes, token counts,
  model names, audit details, and technical labels. Tables and security
  messages use the practical sans/mono, not the display face.

Prefer locally hosted fonts or installed font packages. The token stacks assume
system fallbacks so the UI never blocks on a third-party runtime request; if
fonts are self-hosted later, keep the same token names.

### 4.2 Type scale

| Token         | Size      | Typical use                              |
| ------------- | --------- | ---------------------------------------- |
| `--text-xs`   | 12px      | micro labels, table metadata             |
| `--text-sm`   | 13px      | dense UI, form help, table cells         |
| `--text-base` | 15px      | default body                             |
| `--text-md`   | 16px      | comfortable body, lead paragraphs        |
| `--text-lg`   | 18px      | lead copy, card headings                 |
| `--text-xl`   | 24px      | section heading                          |
| `--text-2xl`  | 32px      | page heading (app), section opener (mkt) |
| `--text-3xl`  | 44px      | hero heading                             |
| `--text-4xl`  | 56px      | display hero (desktop)                   |

Line heights: `--leading-tight` (1.1, display), `--leading-snug` (1.25,
headings), `--leading-normal` (1.5, body), `--leading-relaxed` (1.65, prose).
Tracking: `--tracking-tight` (-0.02em) for large headings; `--tracking-wide`
(0.08em, uppercase) for eyebrows and technical labels.

Rules: **tables and security messages stay practical** — never the display
face at small sizes; keep mono for identifiers so digits align.

---

## 5. Spacing, layout, grid, radius, border, elevation

- **Spacing scale** (`--space-*`): 4px base, `0.25 / 0.5 / 0.75 / 1 / 1.25 /
  1.5 / 2 / 2.5 / 3 / 4 / 5 / 6` rem. Use the scale; avoid arbitrary one-off
  gaps. Dense data views may use `--space-2`/`--space-3`; marketing uses
  generous `--space-16`–`--space-24` between sections.
- **Containers**: application content is capped at `--container-app` (1200px)
  and centered. Editorial/marketing prose uses `--container-measure` (68ch) for a
  readable measure. Do not stretch forms/tables edge-to-edge on wide screens.
- **App shell metrics**: left nav `--sidebar-width` (248px) / collapsed
  `--sidebar-width-collapsed` (68px); top bar `--topbar-height` (60px).
- **Grid**: 12-column on desktop for marketing; collapse to a single, reflowed
  column on mobile (see §7). App pages favor full-width regions with internal
  dividers rather than a card grid.
- **Radius** is restrained: `--radius-sm` (4px) for inputs/badges,
  `--radius-md` (6px) for buttons/controls, `--radius-lg` (10px) for the rare
  elevated panel, `--radius-pill` (999px) only for genuine pills (status
  badges). **Do not round every element** — mix flat regions, divider-separated
  groups, and structured tables with occasional elevated surfaces.
- **Borders**: `--color-border` at `--border-width` (1px) for most dividers;
  `--color-border-strong` for table headers/rules where a 3:1 boundary helps.
- **Elevation** is a tool for floating layers only: `--elevation-1` (subtle
  raise), `-2` (menus/popovers), `-3` (modals). Static content uses borders, not
  shadows.

---

## 6. Components

Shared presentation primitives live under `components/ui/` (later phases). They
hold **presentation behavior only** — no business logic, no API calls. Every
interactive control targets a ≥44×44px hit area where practicable and shows a
visible focus ring (`--focus-ring`, 2px, offset 2px).

### 6.1 Buttons
- **Primary**: oxide fill (`--color-accent`), `--color-accent-contrast` label,
  `--radius-md`. Hover → `--color-accent-hover`. Used once per view for the main
  action.
- **Secondary**: transparent with `--color-border` outline; hover raises to
  `--color-surface-raised`.
- **Ghost / link**: text-only, oxide on hover; underline on focus/hover for
  links.
- **Danger**: `--color-danger` for destructive confirmations; requires a
  confirmation dialog for irreversible actions (e.g., delete policy).
- States: disabled (reduced opacity, no pointer), loading (inline spinner, label
  preserved, button disabled to prevent double-submit).

### 6.2 Form fields
- Label above input, always associated (`htmlFor`/`id`). Required/optional
  marked in text, not color alone.
- Inputs on `--color-surface-raised`, `--color-border`, `--radius-sm`; focus →
  oxide ring. Help text `--text-sm` muted; **errors use `--color-danger` with an
  icon and a text message** tied via `aria-describedby`.
- Never echo server traces, PII, or credentials in errors.

### 6.3 Tables
- Header row on `--color-surface` with `--color-border-strong` bottom rule;
  `--text-sm`, mono for identifiers (IDs, counts).
- Rows separated by 1px `--color-border`; zebra only if it aids scanning (sparing
  `--color-surface-raised`). Numeric columns right-aligned, tabular-nums.
- Empty state is a real, helpful empty state (guidance + primary action), never
  a fabricated row.

### 6.4 Badges / status pills
- `--radius-pill`, `--text-xs`, uppercase, with a **text label** (ALLOW / BLOCK /
  QUEUED / RUNNING / COMPLETED / FAILED) on the matching `--color-*-bg` fill with
  the full-strength foreground. Optional leading dot/shape. Never color-only.

### 6.5 Alerts / toasts / banners
- Inline alerts and toasts share the state colors + icon + text. Destructive /
  blocking = danger; success only after the server confirms. Auto-dismiss only
  for non-critical toasts; errors persist until dismissed.

### 6.6 Cards
- Cards are the **exception, not the default** in the app. Use flat regions with
  divider-separated groups and structured tables first. A card is a bounded
  cluster of related controls; give it `--color-surface`, 1px border,
  `--radius-lg`, and no glow. Marketing feature cards may use the pointer-tilt
  primitive (see ANIMATION_SPEC.md); **never tilt dashboard tables, dense
  surfaces, modals, or essential controls.**

### 6.7 Navigation
- **Public**: compact top nav with the animated SVG mark, wordmark, a few section
  links, one primary action, and a keyboard-accessible mobile menu. No oversized
  floating pill header inside a gradient.
- **App**: consistent shell — collapsible sidebar (grouped: Overview, Data,
  Sanitization, PostgreSQL, AI Gateway, Usage & budgets, Audit — **only real,
  implemented destinations**), top bar with current workspace/page label, main
  content within `--container-app`. Active item = oxide left-rule + oxide text.

### 6.8 Status & feedback states
- Every data surface defines **loading (skeleton), empty, error, blocked, and
  success** states. Skeletons match the content's shape. Errors are safe and
  actionable. Blocked/denied states explain the real reason (e.g., ownership 404,
  active-run conflict) without leaking internals.

---

## 7. Responsive breakpoints & mobile rules

Breakpoints (mobile-first; min-width):

| Name | Width  | Notes                                                       |
| ---- | ------ | ----------------------------------------------------------- |
| sm   | 640px  | large phones                                                |
| md   | 768px  | tablets; sidebar may appear                                 |
| lg   | 1024px | small laptops; full sidebar + top bar                       |
| xl   | 1280px | desktops; container max applies                             |

- **Mobile is a real layout, not a scaled-down desktop.** The app switches to a
  touch-friendly drawer/overlay nav; tables become stacked key/value rows or
  horizontally scrollable with a sticky first column; forms go single-column with
  full-width controls; the hero steps type down (`--text-3xl` → `--text-2xl`).
- Do not pin an entire mobile page for scrollytelling; provide natural scrolling
  and fully readable content on small screens (see ANIMATION_SPEC.md).
- Interactive hit areas stay ≥44×44px on touch.

---

## 8. Accessibility & contrast targets

- **Contrast minimums:** normal text ≥ **4.5:1**, large text (≥18.66px bold or
  ≥24px) ≥ **3:1**, UI component boundaries and meaningful graphical indicators
  ≥ **3:1** where applicable. Verify the pairing of every foreground/background
  combination against these before shipping.
- **Never rely on color alone** for security/status (see §3.3): always pair with
  a text label and, where useful, a shape/icon.
- **Keyboard:** all actions reachable and operable; visible focus
  (`--focus-ring`); logical tab order; menus/dialogs trap and restore focus;
  Escape closes overlays.
- **Semantic HTML:** real buttons/links/inputs/labels; landmarks (`nav`, `main`,
  `header`); headings in order; tables use `<table>` with headers.
- **Forms:** labels, `aria-describedby` for help/error, `aria-invalid` on error,
  announced errors. **Motion:** honor `prefers-reduced-motion` everywhere
  (details in ANIMATION_SPEC.md).
- Sensitive values are never placed in titles, logs, URLs, or `aria-label`s where
  they could leak.

---

## 9. Tailwind token mapping

`tokens.css` is framework-agnostic. Map the **semantic** tokens into whichever
Tailwind format the project uses; do **not** duplicate raw hex values in two
places. (The active Tailwind version/config is decided with the frontend
toolchain in the next phase — see the Phase 0 report. No Tailwind config file is
added in Phase 1 to avoid committing to a version before the build exists.)

**If Tailwind v4 (CSS-first):** reference the tokens inside `@theme` in the CSS
entry point, e.g. `--color-canvas: var(--color-canvas);` exposed through the
existing `tokens.css`. Keep `tokens.css` as the single source and import it.

**If Tailwind v3 (JS config):** extend `theme.colors` by pointing at the CSS
variables so JSX uses semantic utility names:

```js
// tailwind.config.js (only when Tailwind v3 is the installed version)
colors: {
  canvas: "var(--color-canvas)",
  surface: "var(--color-surface)",
  "surface-raised": "var(--color-surface-raised)",
  "surface-highest": "var(--color-surface-highest)",
  paper: "var(--color-surface-paper)",
  text: "var(--color-text)",
  "text-muted": "var(--color-text-muted)",
  "text-inverse": "var(--color-text-inverse)",
  border: "var(--color-border)",
  "border-strong": "var(--color-border-strong)",
  accent: "var(--color-accent)",
  success: "var(--color-success)",
  warning: "var(--color-warning)",
  danger: "var(--color-danger)",
  info: "var(--color-info)",
},
fontFamily: {
  display: "var(--font-display)",
  sans: "var(--font-sans)",
  mono: "var(--font-mono)",
},
borderRadius: {
  sm: "var(--radius-sm)",
  md: "var(--radius-md)",
  lg: "var(--radius-lg)",
},
```

Never introduce `tailwind.config.ts`; the app is JavaScript-only.

---

## 10. Component reuse rules

- **Semantic tokens only** in components; raw hex is confined to `tokens.css`.
- Shared UI primitives (`components/ui/`) are **presentational** — no API calls,
  no business rules. Data fetching lives in feature modules/hooks.
- Prefer composition over a prop explosion; a primitive should do one job well.
- Reuse before you add: if a pattern repeats twice, extract it once. Do not build
  a generic abstraction with a single use.
- Accessibility is part of the primitive contract (focus, labels, roles), not a
  later add-on.

## 11. Prohibited patterns (explicit "must not appear")

- Purple/neon-violet gradients; blue-gray `slate/zinc` template styling.
- Glassmorphism everywhere; a big decorative gradient orb behind the hero.
- Six identical icon-circle-heading-description cards; a recolored stock SaaS
  layout; animated gradients in every section.
- Everything-as-a-rounded-card; a floating oversized pill nav inside a gradient.
- Color-only status; a green "success" for a FAILED run or a blocked request.
- Hardcoded counts/statistics presented as live; fabricated audit rows; mock data
  silently mixed with real data.
- "Blockchain" language; GDPR/HIPAA certification claims; calling mock LLM output
  a real model response.
- Raw PII, uploaded rows, tokens, or server traces surfaced anywhere in the UI.