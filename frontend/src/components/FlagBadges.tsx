import type { Flag } from '../api/types'
import { FLAG_LABELS } from '../labels'

/** 重複・再応募などの印を並べて表示する。マウスを乗せると理由が出る（title 属性） */
function FlagBadges({ flags }: { flags: Flag[] }) {
  return (
    <>
      {flags.map((flag) => (
        <span key={flag.type} className={`badge flag-${flag.type}`} title={flag.reason}>
          {FLAG_LABELS[flag.type]}
        </span>
      ))}
    </>
  )
}

export default FlagBadges
