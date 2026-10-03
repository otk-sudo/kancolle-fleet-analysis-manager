import { useCallback, useState, type ReactNode } from 'react'
import ConfirmDialog, { type ConfirmOptions } from './ConfirmDialog'

type Pending = ConfirmOptions & { resolve: (ok: boolean) => void }

/**
 * 「よろしいですか？」の確認ダイアログを出すための仕組み（共通の部品）。
 *
 * 使い方:
 *   const [dialog, confirm] = useConfirm()
 *   if (!(await confirm({ title: '…', confirmLabel: '…' }))) return   // 「やめる」なら false
 *   ...
 *   return <>{dialog} ...</>
 *
 * ブラウザの window.confirm は見た目を変えられず、ボタンも「OK」「キャンセル」で何が起きるかわからないため、自前で作る。
 * HTML の <dialog> 要素の showModal() を使うと、後ろの画面を押せなくする・Esc キーで閉じる・
 * キーボードの移動をダイアログの中に閉じ込める、を、ブラウザがやってくれる。
 */
export function useConfirm(): [ReactNode, (options: ConfirmOptions) => Promise<boolean>] {
  const [pending, setPending] = useState<Pending | null>(null)

  // Promise（あとで結果が決まる値）を返し、ボタンが押されたら resolve で結果を渡す
  const confirm = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => setPending({ ...options, resolve })),
    [],
  )

  const close = useCallback(
    (ok: boolean) => {
      pending?.resolve(ok)
      setPending(null)
    },
    [pending],
  )

  const dialog = pending ? <ConfirmDialog options={pending} onClose={close} /> : null
  return [dialog, confirm]
}
