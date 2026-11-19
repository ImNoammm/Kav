// The community list as scripts/community.mjs last wrote it. A build without it shows an empty list.
import { existsSync, readFileSync } from 'node:fs'
import { defineLoader } from 'vitepress'

export interface Listing {
  key: string
  path: string
  name: string
  owner: string
  page: string
  image: string | null
  extensions: number
  updated: string | null
  add: string
  issues: string
}

export interface Community {
  generated: string | null
  order: 'name'
  sources: Listing[]
}

declare const data: Community
export { data }

const FILE = new URL('../../extensions/listing.json', import.meta.url)

export default defineLoader({
  load(): Community {
    if (!existsSync(FILE)) return { generated: null, order: 'name', sources: [] }
    return JSON.parse(readFileSync(FILE, 'utf8'))
  },
})
