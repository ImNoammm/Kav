# Extensions on kavapp.link

What the site does for Kav's extensions (PLAN.md sections 7 and 9). None of it is live yet.

| Page | File |
|---|---|
| `/add-source`, where shared source links land when Kav doesn't open them itself | `site/add-source.md`, `components/AddSource.vue` |
| `/community`, the community list of sources | `site/community.md`, `components/Community.vue` |
| `/docs/extensions`, for developers, with the Add to Kav badge maker | `site/docs/extensions.md`, `components/BadgeMaker.vue` |
| `/third-party-extensions`, what Kav does and doesn't take on for extensions | `site/third-party-extensions.md` |
| The App Link statement for `kavapp.link/add-source` | `site/public/.well-known/assetlinks.json` |
| The badge | `site/public/img/add-to-kav.svg`, `add-to-kav-he.svg` |
| The security block list | `site/public/extensions/block-list.json` (signed, with no entries), see `block-list.md` |

Each has a Hebrew twin under `site/he/`. Links are read with `site/.vitepress/theme/sources.js`, which follows the app's
`GitHub.repoOf` (`ext/GitHub.kt`) line by line; `scripts/sources.test.mjs` holds it to the app's own answers.

## The community list

Fully automatic: nobody at Kav reviews, features, ranks or pins anything (PLAN.md 9.3). `site/scripts/community.mjs`
gathers candidates from

- public GitHub repositories with the `kav-extension` topic,
- `submissions.txt` here, and
- issues on Kav's repository titled `Kav source: <address>` with the developer terms ticked, which the page's Submit
  button opens already filled in (so a source can be submitted with no server of Kav's and nobody merging anything),

checks each with the developer kit's own reader (`readBundle`, what `kavx verify` runs), and writes
`listing.json` here (the page's data), `community.log` (what was left out and why) and the sources' pictures into
`site/public/community/`, so the page loads nothing from other hosts. All three are generated and not kept in git.
The list is in one order, by name.

A source is listed if, and only if, its latest GitHub Release has exactly one `.kavb` bundle that the kit reads, with
at least one extension Kav runs, and it is on neither `exclusions.txt` nor the security block list.

### Unlisting a source

Add it to `exclusions.txt`, with the reason after a `#`. That wins over every way in. A source that came only through
`submissions.txt` can instead have its line deleted. Either way it's gone from the page with the next run, and anyone
can still add it to Kav by link or by hand.

### Running it

```sh
cd site
node scripts/community.mjs --kit <kit checkout>                 # asks GitHub
node scripts/community.mjs --kit <kit> --offline <folder>       # every https GET from <folder>/<host>/<path>
node scripts/community.mjs --topic-only                         # only the topic search: how many repositories have it
KAV_KIT=<kit checkout> node --test scripts/*.test.mjs           # the tests, against bundles made on the spot with the kit
```

`GITHUB_TOKEN`, when set, is sent to GitHub's API only (for its search limits).

### The nightly workflow

`community.yml` here runs the script each night and builds the site. It is **not enabled**: GitHub runs workflows only
from `.github/workflows/`. To turn it on, once the kit's repository is public at the address in `sources.js` (`KIT`):

1. move `community.yml` to `.github/workflows/`;
2. decide how the built site reaches the server (the site is copied by hand today; the workflow uploads it as an
   artifact until then).
