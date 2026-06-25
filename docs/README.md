# OpenEco Docs

Documentation site for OpenEco, built with [VitePress](https://vitepress.dev/).

## Structure

```
docs/
├── .vitepress/
│   ├── components/        # Vue components (CommandRow, ConfigProperty, …)
│   ├── theme/             # Custom theme overrides
│   └── config.mts         # Nav, sidebar, base path
├── docs/                  # Documentation pages (English)
├── public/                # Static assets (logo)
├── index.md               # Home page
└── package.json
```

## Development

```bash
npm install
npm run docs:dev      # http://localhost:5173/OpenEco/
npm run docs:build    # Production build → .vitepress/dist/
npm run docs:preview  # Preview production build
```

## Custom components

| Component | Usage |
|-----------|-------|
| `<CommandRow>` | Command with permission badge and description |
| `<PermRow>` | Permission table row (inside `<BaseTable>`) |
| `<BaseTable>` | Table wrapper for permission rows |
| `<ConfigProperty>` | Config key with type, default, description |
| `<ConfigGroup>` | Groups multiple ConfigProperty entries |
| `<DocCard>` | Link card for navigation |
| `<CardGrid>` | Grid layout for DocCards |

## Deployment

The site deploys to GitHub Pages via `.github/workflows/deploy-docs.yml` on every push to `main` that touches `docs/`.

Live site: https://alexteens24.github.io/OpenEco/

## Contributing

Edit the relevant `.md` file under `docs/docs/` and open a PR. For new pages, register them in `.vitepress/config.mts` under `themeConfig.sidebar`.
