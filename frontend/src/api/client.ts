import createClient from 'openapi-fetch'
import type { paths } from './schema.gen'

// 型はapi/openapi.yamlから生成する（npm run generate:api）
export const api = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
})
