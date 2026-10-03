import { STATUS_LABELS } from '../labels'

/** ステータスを色付きの小さなラベルで表示する。色は index.css の .status-◯◯ で決めている */
function StatusBadge({ status }: { status: string }) {
  return <span className={`badge status-${status}`}>{STATUS_LABELS[status] ?? status}</span>
}

export default StatusBadge
