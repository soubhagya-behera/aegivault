# Aegivault Frontend

React + Vite (**TypeScript**) frontend for the Aegivault data privacy, security,
and AI governance platform. Talks to the existing Spring Boot backend without
modifying it.

> Status: **Phase 2B — TypeScript, API client, and authentication foundation.**
> The protected `/` route renders an honest placeholder. The landing page,
> dashboard, and remaining feature modules are built in later phases.

## Tech stack

- React 19 + Vite 8 (**TypeScript / TSX**, strict compiler config)
- Tailwind CSS v4 (CSS-first, via `@tailwindcss/vite`)
- `motion` (imported from `motion/react`)
- `lucide-react` (icons)
- `react-router-dom` (routing)
- Vitest + Testing Library + jsdom (unit tests)

Design tokens live in [`src/styles/tokens.css`](src/styles/tokens.css) and are
the single source of truth, bridged into Tailwind utilities in
[`src/index.css`](src/index.css). See [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md) and
[`ANIMATION_SPEC.md`](ANIMATION_SPEC.md).

## Prerequisites

- Node.js 18+ and npm (developed on Node 24 / npm 11)
- The Aegivault backend running locally (default `http://localhost:8080`)

## Local development

```bash
npm install
npm run dev
```

The dev server starts on <http://localhost:5173>. Open `/login` (or register a
real account against the backend).

## Commands

```bash
npm run dev        # start the Vite dev server
npm run build      # production build to dist/
npm run preview    # serve the production build locally
npm run typecheck  # strict TypeScript check (app + vite config)
npm test           # run the Vitest unit suite once
```

## Environment variables

Copy `.env.example` to `.env` and adjust as needed. Real secrets must never be
committed; only the example file is tracked.

| Variable                  | Used by        | Default                 | Purpose                                          |
| ------------------------- | -------------- | ----------------------- | ------------------------------------------------ |
| `VITE_API_BASE_URL`       | client code    | `/api`                  | Base URL the client uses for API calls.          |
| `AEGIVAULT_BACKEND_TARGET`| dev proxy only | `http://localhost:8080` | Where the Vite dev proxy forwards `/api` + `/actuator`. Server-side; never bundled into the client. |

### Development proxy

`vite.config.ts` proxies both `/api` and `/actuator` to
`AEGIVAULT_BACKEND_TARGET`, preserving the original request paths. This is a
**local development convenience only** — it is not a production cross-origin or
reverse-proxy solution. Production deployment topology (reverse proxy / CORS) is
addressed separately and requires no backend changes here.

## Authentication

- Real backend endpoints: `POST /api/auth/register`, `POST /api/auth/login`,
  `GET /api/auth/me` (see `src/lib/api/auth.ts` and `src/types/auth.ts`).
- The bearer JWT is held **in memory only** (a ref inside `AuthProvider`). It is
  never written to `localStorage`, `sessionStorage`, IndexedDB, cookies, URLs, or
  logs. **Reloading the page therefore signs the user out** — this is deliberate
  and there is no refresh-token flow. A secure persistent-session design can be
  reviewed separately if needed.
- `GET /api/auth/me` is called **only right after a successful login/register**
  to load the full profile; it is never called unauthenticated on page load.
- Route guards (`src/app/guards.tsx`) enforce access: signed-out users cannot
  enter the protected workspace, and signed-in users are redirected away from
  the login/register pages. Hiding a UI element is never treated as authorization.

## Notes

- Registration always creates the backend's default `USER` role; the form
  exposes no role selection and cannot self-assign `ADMIN`.
- Unit tests use a mocked `fetch` and require **no** live backend.
- No lint runner is configured yet; it can be added in a later phase if wanted.
