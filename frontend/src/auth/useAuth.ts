// ログインしている人の情報を、どの画面からでも取り出せるようにするしくみ。
//
// React の「コンテキスト」は、親のコンポーネントで用意した値を、間の子を経由せずに孫・ひ孫から直接読める入れ物。
// RequireLogin（ログインの確認をする枠）が /me の結果をここに入れ、各画面は useAuth() で読む。
import { createContext, useContext } from 'react'
import type { Me, Permission } from '../api/types'

export type Auth = {
  /** ログインしている人（名前・役割・できることの一覧） */
  me: Me
  /**
   * その操作をしてよいか。画面でボタンを出すか・押せるようにするかを決めるのに使う。
   * 画面で隠すのは「押しても断られるボタンを見せない」ためで、本当の確認はバックエンドが毎回行う（403）。
   */
  can: (permission: Permission) => boolean
  /** ログアウトする（保存しているトークンを捨てて、ログイン画面へ移る） */
  logout: () => void
}

export const AuthContext = createContext<Auth | null>(null)

/** ログインしている人の情報を取り出す。RequireLogin の内側の画面でだけ使える */
export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) {
    throw new Error('useAuth は RequireLogin の内側で使ってください')
  }
  return auth
}
