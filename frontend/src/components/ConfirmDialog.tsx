import { useEffect, useRef, type ReactNode } from 'react'

/** 確認のダイアログに出す内容 */
export type ConfirmOptions = {
  /** 何をするか（例:「2件を見送りにしますか？」） */
  title: string
  /** 何が起きるかの説明 */
  body?: ReactNode
  /** 実行するボタンの文言。何が起きるかがわかる言葉にする（例:「2件を見送りにする」） */
  confirmLabel: string
  /** やめるボタンの文言。省略すると「やめる」（例: 抽選では「戻って見直す」） */
  cancelLabel?: string
  /** 元に戻しにくい操作（抽選など）のときは true にして、ボタンを赤茶にする */
  danger?: boolean
}

/** 確認のダイアログの見た目と動き。使うときは useConfirm（useConfirm.tsx）から呼ぶ */
function ConfirmDialog({ options, onClose }: { options: ConfirmOptions; onClose: (ok: boolean) => void }) {
  // useRef: 描画した <dialog> 要素そのものを覚えておく入れ物（showModal を呼ぶため）
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = ref.current
    if (element && !element.open) {
      element.showModal()
    }
  }, [])

  return (
    <dialog
      ref={ref}
      className="confirm"
      aria-labelledby="confirm-title"
      // Esc キーで閉じたときは「やめる」と同じ扱いにする
      onCancel={(event) => {
        event.preventDefault()
        onClose(false)
      }}
    >
      <h2 id="confirm-title" className="confirm-title">
        {options.title}
      </h2>
      {options.body && <div className="confirm-body">{options.body}</div>}
      <div className="confirm-actions">
        {/* 間違えて Enter を押しても実行しないよう、最初にキーボードが当たる（autoFocus）のは「やめる」にする */}
        <button type="button" className="button" autoFocus onClick={() => onClose(false)}>
          {options.cancelLabel ?? 'やめる'}
        </button>
        <button
          type="button"
          className={options.danger ? 'button danger filled' : 'button primary'}
          onClick={() => onClose(true)}
        >
          {options.confirmLabel}
        </button>
      </div>
    </dialog>
  )
}

export default ConfirmDialog
