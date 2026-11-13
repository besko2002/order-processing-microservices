# Order Processing: web UI

React 18 + TypeScript + Vite. Plain CSS, no UI library.

```bash
npm install
npm run dev            # http://localhost:5173, proxies /api to http://localhost:8080 (the gateway)
npm run test -- --run  # Vitest + Testing Library
npm run lint && npm run build
```

The API client (`src/api/`) adds the bearer token, parses the backend `ApiError` JSON and ends the
session on a 401. A 429 shows a "too many requests" message, and a 503 shows "temporarily unavailable".
Order pages poll every 2 seconds only while the order is not in a final state.

Routes: `/login`, `/register`, `/` (products), `/cart` (checkout with an `Idempotency-Key`),
`/orders`, `/orders/:id` (saga timeline and payment), `/notifications`, and the admin-only
`/admin/orders`, `/admin/products`, `/admin/reservations`.
