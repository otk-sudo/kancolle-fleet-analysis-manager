// 応募の一覧を「全件」取り出すための関数。
// API は一度に最大200件までしか返さず、続きがあるときは nextCursor（続きの位置）を返す。
// nextCursor がなくなるまで繰り返し呼ぶことで、応募が多くても取りこぼさない。
import { api } from './client'
import type { components, operations } from './schema.gen'

type Query = NonNullable<operations['listApplications']['parameters']['query']>
type Application = components['schemas']['Application']
type ApplicationPage = components['schemas']['ApplicationPage']

export async function fetchAllApplications(
  query: Omit<Query, 'cursor' | 'limit'>,
): Promise<{ items: Application[]; error?: unknown }> {
  const items: Application[] = []
  let cursor: string | undefined = undefined
  do {
    // 型を明示しておく（cursor と data がお互いの型に依存するため、TypeScript が自動では型を決められない）
    const { data, error }: { data?: ApplicationPage; error?: unknown } = await api.GET('/applications', {
      params: { query: { ...query, cursor, limit: 200 } },
    })
    if (error || !data) {
      return { items, error: error ?? 'error' }
    }
    items.push(...data.items)
    cursor = data.nextCursor ?? undefined
  } while (cursor)
  return { items }
}
