import type { ReactNode } from 'react'

type Props = {
  /** error = 失敗（赤茶の枠）、ok = 成功 */
  kind: 'error' | 'ok'
  /** 何が起きたか（例:「夕凪 提督の変更を保存できませんでした」） */
  title: string
  /** 原因と、次にすること（デザインの決まり: エラーには原因と次にすることを書く） */
  children?: ReactNode
  /** 右に置くボタン（「最新の状態を読み込む」など） */
  action?: ReactNode
}

/**
 * 画面の上に出すお知らせ（共通の部品）。
 * 失敗のときは role="alert" にして、画面読み上げソフトがすぐに読み上げるようにする。
 * 成功のときは role="status"（ほかの読み上げを邪魔せず、区切りのよいところで読み上げる）。
 */
function Notice({ kind, title, children, action }: Props) {
  return (
    <div className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="notice-body">
        <div className="notice-title">{title}</div>
        {children && <div className="notice-text">{children}</div>}
      </div>
      {action}
    </div>
  )
}

export default Notice
