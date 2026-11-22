# Architecture diagrams (Archify)

Interactive standalone HTML diagrams for the banking playground, authored with
[Archify](https://github.com/tt-a1i/archify).

| Diagram | Source | HTML |
|---|---|---|
| Runtime topology | [`banking-runtime.architecture.json`](banking-runtime.architecture.json) | [`banking-runtime.html`](banking-runtime.html) |
| Transfer path | [`transfer-money.sequence.json`](transfer-money.sequence.json) | [`transfer-money.html`](transfer-money.html) |
| Event pipeline | [`event-pipeline.dataflow.json`](event-pipeline.dataflow.json) | [`event-pipeline.html`](event-pipeline.html) |

Open an HTML file in a browser (dark/light themes, Live/Still motion, PNG/SVG export).
Source JSON is pinned to a git revision with `link_mode: local-only` (SSH origin).

To regenerate after editing a candidate (from the repository root):

```bash
node ~/.agents/skills/archify/bin/archify.mjs finalize architecture \
  docs/architecture/banking-runtime.architecture.json \
  docs/architecture/banking-runtime.html \
  --repo-root . --quality showcase --json

node ~/.agents/skills/archify/bin/archify.mjs finalize sequence \
  docs/architecture/transfer-money.sequence.json \
  docs/architecture/transfer-money.html \
  --repo-root . --quality showcase --json

node ~/.agents/skills/archify/bin/archify.mjs finalize dataflow \
  docs/architecture/event-pipeline.dataflow.json \
  docs/architecture/event-pipeline.html \
  --repo-root . --quality showcase --json
```

Working folders under `.archify/` are local authoring scratch and are gitignored.
