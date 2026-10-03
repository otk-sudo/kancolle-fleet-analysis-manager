// 画面でよく使うAPIの型に、短い名前を付けておく。
// 型そのものは api/openapi.yaml から自動生成した schema.gen.ts にあり、ここでは名前を付け直すだけ（手で型を書かない）。
import type { components } from './schema.gen'

type Schemas = components['schemas']

export type Application = Schemas['Application']
export type Flag = Schemas['Flag']
export type FlagType = Schemas['FlagType']
export type Answers = Schemas['Answers']
export type StreamApplicant = Schemas['StreamApplicant']
export type Lottery = Schemas['Lottery']
export type Settings = Schemas['Settings']
export type ApiError = Schemas['ApiError']

/** エラー応答から、画面に出すメッセージを取り出す。 */
export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
    return error.message
  }
  return '通信に失敗しました。バックエンドが起動しているか確認してください'
}
