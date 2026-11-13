# kavapp.link

Kav's website: the home page, downloads and version history, the docs and the privacy policy. Built with
[VitePress](https://vitepress.dev) 1.6.

The docs under `docs/` (self-hosting, API, live sources, planner, predictor, training) are copied from the repo's own
Markdown by `scripts/sync-docs.mjs` on every build, so edit them where they live (`server/...`), not here. Links between
them become site links; links to anything else in the repo go to GitHub. The release list is fetched from GitHub's API
when the site is built, and again in the visitor's browser.

## Building

Needs Node 18 or newer.

```sh
cd site
npm install
npm run dev      # http://localhost:5173, the docs synced once at start
npm run build    # into .vitepress/dist
```

Built away from the repo (in a container, say), point `KAV_REPO` at a checkout so the docs can be found.
