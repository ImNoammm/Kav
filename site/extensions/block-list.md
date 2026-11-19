# The security block list

Kav's list of extension sources (GitHub repositories) and bundles that are dangerous (PLAN.md 9.1): on by default, the rider can
turn it off in Settings, and for security only. Kav reads it in its background check and refuses to add anything on it,
marks anything already added as Blocked and stops using it. The community list's script leaves out anything on it.

**State today:** the key was made on 19 November 2026 (fingerprint `9083ae718b4b6eba6a4fc42aee7f5631`) and its public
half is `BlockList.KEY` in the app from Kav 5. `site/public/extensions/block-list.json` is a signed list, sequence 1,
with no entries.

## Where it lives

One file, in two places at once:

- `https://kavapp.link/extensions/block-list.json` on this site, from `site/public/extensions/block-list.json`;
- `https://raw.githubusercontent.com/ImNoammm/kav/HEAD/site/public/extensions/block-list.json`, the same file on GitHub.

The app's `BlockList.URL` (`ext/BlockList.kt`) is the GitHub one: Kav already talks to GitHub in the same background
check (for its own updates), so it adds no new party. The kavapp.link one goes through Cloudflare to Kav's site, which
keeps no access log.

## The file

A signed envelope with the role `block-list` (the kit's `docs/format.md`, "Signed files"), at most 512 KB, whose payload
is:

```json
{
  "format": 1,
  "sequence": 3,
  "entries": [
    { "github": "owner/repo", "reason": "malware" },
    { "bundle": "<one .kavb file's SHA-256>", "reason": "impersonation" }
  ]
}
```

- `sequence` is a whole number that goes up with every new list. Kav never takes a list older than the one it has,
  and takes the same number again only with the very same bytes, so a replay can't unblock anything.
- `reason` is one of `malware`, `credential-theft`, `impersonation` and `kav-bug-abuse`. Kav ignores an entry with any
  other reason, so the list can't be used for anything but security, even by whoever holds its key. A legal complaint
  or a request to be unlisted is not a reason: unlisting from the community page is `exclusions.txt`.
- `github` names a source by its repository, compared without case; `bundle` names one bundle file by its SHA-256, in
  hex. An entry of any other kind is ignored.

## Making the key (once, by Noam, on his own computer)

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out block-list.pem   # the private key: keep it offline, with a backup
openssl pkey -in block-list.pem -pubout -outform DER | base64 -w0   # the public key, for the two places below
```

The public key, as that one line of base64, goes:

1. into the app, as `BlockList.KEY` in `ext/BlockList.kt` (with `BlockList.URL` as above), in a release;
2. into the community script, as `BLOCK_KEY` in `site/scripts/community.mjs` (the variable `KAV_BLOCK_KEY` overrides
   it).

No workflow should hold the private key: a list is signed by hand, on the computer that keeps the key.

## Signing a list

Write the payload above to a file (here `payload.json`), with a higher `sequence` than the last list, then sign it with
the kit's own code, from the kit's folder:

```sh
node --input-type=module -e '
import { readFileSync, writeFileSync } from "node:fs"
import { createPrivateKey } from "node:crypto"
import { seal } from "./lib/signing.js"
const [payload, key, out] = process.argv.slice(1)
writeFileSync(out, seal("block-list", readFileSync(payload), [createPrivateKey(readFileSync(key))]))
' payload.json block-list.pem <kav>/site/public/extensions/block-list.json
```

Check it the way the community script reads it, which also verifies the signature:

```sh
node site/scripts/community.mjs --kit <kit> --offline /nonexistent
```

The log's line `block list: N entries` counts the entries Kav would apply. A list that doesn't verify stops the script
before it lists anything.
