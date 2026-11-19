# Banking frontend

The Next.js App Router serves the banking UI and same-origin API routes.
Browser requests use relative URLs under `/api`. Server-side handlers proxy
most banking requests to the gateway using `API_GATEWAY_URL`, which defaults to
`http://localhost:8080` for host development. Login stores the JWT in an
HttpOnly cookie used by authenticated routes. The AI chat handler calls the
AI service through `AI_SERVICE_URL`.

## Local development

Use the Node version in [`.nvmrc`](.nvmrc). Start the backend using the
[root guide](../README.md#quick-start). If Compose already runs the frontend,
stop that container before starting the host development server on port 3000.

```bash
npm ci
npm run dev
```

Open <http://localhost:3000>. Routes and pages live in `src/app`. Check
`/api/healthz` for frontend health.

Run `npm run build` for the production build and `npm test` for Jest.
The [Playwright guide](e2e/README.md) explains which browser test mode to use.
