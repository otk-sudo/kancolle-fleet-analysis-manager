import { STATUS_LABELS } from '../labels'

/**
 * ステータスを丸い札で表示する（共通の部品）。
 * 色は index.css の .status-◯◯ で決めている（分析中だけ紺地で目立たせる）。
 */
function StatusBadge({ status }: { status: string }) {
  return <span className={`status status-${status}`}>{STATUS_LABELS[status] ?? status}</span>
}

export default StatusBadge
