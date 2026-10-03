import type { Flag } from '../api/types'
import { FLAG_LABELS } from '../labels'

/**
 * 重複・再応募・条件外の印を並べて表示する（共通の部品）。
 * 重複と条件外は赤茶（確認が必要）、再応募は青（お知らせ）。色は index.css の .mark-◯◯ で決めている。
 * マウスを乗せると理由が出る（title 属性）。
 */
function FlagBadges({ flags }: { flags: Flag[] }) {
  return (
    <>
      {flags.map((flag) => (
        <span key={flag.type} className={`mark mark-${flag.type}`} title={flag.reason}>
          {FLAG_LABELS[flag.type]}
        </span>
      ))}
    </>
  )
}

export default FlagBadges
