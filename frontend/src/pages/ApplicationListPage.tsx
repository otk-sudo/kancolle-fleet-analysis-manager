import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { fetchAllApplications } from '../api/applications'
import { api } from '../api/client'
import { errorMessage, type Application, type FlagType } from '../api/types'
import FlagBadges from '../components/FlagBadges'
import StatusBadge from '../components/StatusBadge'
import { FLAG_LABELS, PURPOSES, STATUS_LABELS, formatDateTime } from '../labels'

/** ステータスの絞り込みの選択肢。"open" は「まだ終わっていないもの」をまとめて見るための特別な値 */
const OPEN_STATUSES = ['analyzing', 'scheduled', 'pending']

/**
 * 応募一覧（仕様 7.1）。「次に分析する順」に並べ、上下ボタンで並べ替えられる。
 */
function ApplicationListPage() {
  // useState: 画面が覚えておく値（状態）。値を変えると、その値を使っている部分が描き直される
  const [statusFilter, setStatusFilter] = useState('open')
  const [purpose, setPurpose] = useState('')
  const [flag, setFlag] = useState('')
  const [order, setOrder] = useState<'queue' | 'received'>('queue')
  const [items, setItems] = useState<Application[]>([])
  const [error, setError] = useState('')

  // useCallback: 絞り込み条件が変わったときだけ、新しい load 関数を作り直す（下の useEffect が反応するため）
  const load = useCallback(async () => {
    const status = statusFilter === 'open' ? OPEN_STATUSES : statusFilter === 'all' ? undefined : [statusFilter]
    const { items, error } = await fetchAllApplications({
      status,
      purpose: purpose || undefined,
      flag: (flag || undefined) as FlagType | undefined,
      order,
    })
    return { items, error }
  }, [statusFilter, purpose, flag, order])

  /** 読み込んだ結果を画面に反映する */
  const applyResult = useCallback((result: { items: Application[]; error?: unknown }) => {
    if (result.error) {
      setError(errorMessage(result.error))
      return
    }
    setError('')
    setItems(result.items)
  }, [])

  // useEffect: 画面を表示したときと、load が変わったとき（＝絞り込み条件が変わったとき）にデータを読み込む
  useEffect(() => {
    // 絞り込みを素早く切り替えると、古い条件の結果が後から届くことがある。
    // 後片付け（return の関数）で ignore を true にし、古い結果は画面に反映しない
    let ignore = false
    void load().then((result) => {
      if (!ignore) applyResult(result)
    })
    return () => {
      ignore = true
    }
  }, [load, applyResult])

  /** 同じステータスの中で1つ上（direction=-1）または1つ下（+1）へ動かす */
  async function move(target: Application, direction: -1 | 1) {
    const group = items.filter((item) => item.status === target.status)
    const index = group.findIndex((item) => item.id === target.id)
    // APIは「どの応募の後ろに入れるか（after）」で指定する。先頭に入れるときは null
    let after: string | null
    if (direction === -1) {
      if (index === 0) return
      after = index >= 2 ? group[index - 2].id : null
    } else {
      if (index === group.length - 1) return
      after = group[index + 1].id
    }
    const { error } = await api.PUT('/applications/{applicationId}/position', {
      params: { path: { applicationId: target.id } },
      body: { after },
    })
    if (error) {
      setError(errorMessage(error))
      return
    }
    applyResult(await load())
  }

  // 目的や印で絞り込んでいると、見えていない応募をまたいで動かすことになるため、並べ替えは絞り込みなしのときだけにする
  const canReorder = order === 'queue' && !purpose && !flag

  return (
    <section>
      <h1>応募一覧</h1>
      <div className="filters">
        <label>
          ステータス
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="open">未完了（分析中・分析予定・未着手）</option>
            <option value="all">すべて</option>
            {Object.entries(STATUS_LABELS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          目的
          <select value={purpose} onChange={(e) => setPurpose(e.target.value)}>
            <option value="">すべて</option>
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
        <label>
          印
          <select value={flag} onChange={(e) => setFlag(e.target.value)}>
            <option value="">すべて</option>
            {Object.entries(FLAG_LABELS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          並び順
          <select value={order} onChange={(e) => setOrder(e.target.value as 'queue' | 'received')}>
            <option value="queue">次に分析する順</option>
            <option value="received">受付順</option>
          </select>
        </label>
      </div>

      {error && <p className="error">{error}</p>}

      <table className="list">
        <thead>
          <tr>
            <th>ステータス</th>
            <th>提督名</th>
            <th>XのID</th>
            <th>目的</th>
            <th>印</th>
            <th>受付日時</th>
            <th>配信日</th>
            {canReorder && <th>並べ替え</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const movable = item.status === 'pending' || item.status === 'scheduled'
            return (
              <tr key={item.id}>
                <td>
                  <StatusBadge status={item.status} />
                </td>
                <td>
                  <Link to={`/applications/${item.id}`}>{item.admiralName}</Link>
                  {item.anonymous && <span className="badge anonymous">匿名希望</span>}
                </td>
                <td>@{item.xId}</td>
                <td>{String(item.answers.purpose ?? '')}</td>
                <td>
                  <FlagBadges flags={item.flags} />
                </td>
                <td>{formatDateTime(item.receivedAt)}</td>
                <td>{item.streamDate ?? ''}</td>
                {canReorder && (
                  <td className="nowrap">
                    {movable && (
                      <>
                        <button type="button" onClick={() => void move(item, -1)} aria-label="上へ">
                          ↑
                        </button>
                        <button type="button" onClick={() => void move(item, 1)} aria-label="下へ">
                          ↓
                        </button>
                      </>
                    )}
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
      {items.length === 0 && !error && <p>該当する応募はありません。</p>}
    </section>
  )
}

export default ApplicationListPage
