import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { fetchAllApplications } from '../api/applications'
import { api } from '../api/client'
import { errorMessage, type Application, type FlagType, type SkipReason } from '../api/types'
import FlagBadges from '../components/FlagBadges'
import StatusBadge from '../components/StatusBadge'
import {
  BULK_LIMIT,
  BULK_STATUSES,
  FLAG_LABELS,
  PURPOSES,
  RANKING_EFFORTS,
  SKIP_REASON_LABELS,
  STATUS_LABELS,
  formatDateTime,
} from '../labels'

/** ステータスの絞り込みの選択肢。"open" は「まだ終わっていないもの」をまとめて見るための特別な値 */
const OPEN_STATUSES = ['analyzing', 'scheduled', 'pending']

/**
 * 応募一覧（仕様 7.1）。「次に分析する順」に並べ、上下ボタンで並べ替えられる。
 * 提督名・XのIDで検索でき、複数を選んでまとめてステータスを変えられる（仕様 5.6）。
 */
function ApplicationListPage() {
  // useState: 画面が覚えておく値（状態）。値を変えると、その値を使っている部分が描き直される
  const [statusFilter, setStatusFilter] = useState('open')
  const [purpose, setPurpose] = useState('')
  const [rankingEffort, setRankingEffort] = useState('')
  const [flag, setFlag] = useState('')
  const [order, setOrder] = useState<'queue' | 'received'>('queue')
  // 検索欄に入力中の文字（keywordInput）と、実際に検索に使う文字（keyword）を分ける。
  // 1文字打つたびに検索し直すと重いので、「検索」ボタン（または Enter）で keyword に移す
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [items, setItems] = useState<Application[]>([])
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  // まとめて変更するために選んだ応募のID。Set は「同じ値を2回入れない入れ物」
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkStatus, setBulkStatus] = useState('skipped')
  const [bulkReason, setBulkReason] = useState<SkipReason | ''>('')

  // useCallback: 絞り込み条件が変わったときだけ、新しい load 関数を作り直す（下の useEffect が反応するため）
  const load = useCallback(async () => {
    const status = statusFilter === 'open' ? OPEN_STATUSES : statusFilter === 'all' ? undefined : [statusFilter]
    const { items, error } = await fetchAllApplications({
      status,
      purpose: purpose || undefined,
      rankingEffort: rankingEffort || undefined,
      flag: (flag || undefined) as FlagType | undefined,
      q: keyword || undefined,
      order,
    })
    return { items, error }
  }, [statusFilter, purpose, rankingEffort, flag, keyword, order])

  /** 読み込んだ結果を画面に反映する */
  const applyResult = useCallback((result: { items: Application[]; error?: unknown }) => {
    if (result.error) {
      setError(errorMessage(result.error))
      return
    }
    setError('')
    setItems(result.items)
    // 読み込み直したら選択は外す（古い版のまま変更しないように）
    setSelected(new Set())
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

  function search(event: FormEvent) {
    // フォームの送信でページが読み込み直されないようにする（ブラウザの標準の動きを止める）
    event.preventDefault()
    setKeyword(keywordInput.trim())
  }

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

  function toggle(id: string) {
    // Set は中身を直接変えず、新しい Set を作って渡す（React が「変わった」と気づけるように）
    const next = new Set(selected)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    setSelected(next)
  }

  function toggleAll() {
    setSelected(selected.size === items.length ? new Set() : new Set(items.map((item) => item.id)))
  }

  async function changeSelected() {
    const targets = items.filter((item) => selected.has(item.id))
    if (bulkStatus === 'skipped' && !bulkReason) {
      setError('見送りにするときは理由を選んでください')
      return
    }
    const label = STATUS_LABELS[bulkStatus] + (bulkStatus === 'skipped' && bulkReason ? `（${SKIP_REASON_LABELS[bulkReason]}）` : '')
    // window.confirm: 「OK」「キャンセル」を選ぶ確認の小さな画面を出す
    if (!window.confirm(`選んだ ${targets.length} 件を「${label}」にします。よろしいですか？`)) return
    const { error } = await api.POST('/applications/bulk-status', {
      body: {
        // 画面で読み込んだときの版も送る。ほかの人が先に変えていたら、どれも変えずにエラーになる
        items: targets.map((item) => ({ id: item.id, version: item.version })),
        status: bulkStatus,
        skipReason: bulkStatus === 'skipped' ? bulkReason || undefined : undefined,
      },
    })
    if (error) {
      setMessage('')
      setError(errorMessage(error))
      return
    }
    setMessage(`${targets.length} 件を「${label}」にしました`)
    applyResult(await load())
  }

  // 目的や印などで絞り込んでいると、見えていない応募をまたいで動かすことになるため、並べ替えは絞り込みなしのときだけにする
  const canReorder = order === 'queue' && !purpose && !rankingEffort && !flag && !keyword

  return (
    <section>
      <h1>応募一覧</h1>
      <form className="filters" onSubmit={search}>
        <label>
          検索
          <input
            type="search"
            placeholder="提督名・XのID"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
          />
        </label>
        <button type="submit">検索</button>
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
          戦果
          <select value={rankingEffort} onChange={(e) => setRankingEffort(e.target.value)}>
            <option value="">すべて</option>
            {RANKING_EFFORTS.map((r) => (
              <option key={r} value={r}>
                {r}
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
      </form>

      {/* まとめての変更（1件以上選んだときだけ出す） */}
      {selected.size > 0 && (
        <div className="bulk-bar">
          <span>{selected.size} 件を選択中</span>
          <label>
            変更後
            <select value={bulkStatus} onChange={(e) => setBulkStatus(e.target.value)}>
              {BULK_STATUSES.map((code) => (
                <option key={code} value={code}>
                  {STATUS_LABELS[code]}
                </option>
              ))}
            </select>
          </label>
          {bulkStatus === 'skipped' && (
            <label>
              理由
              <select value={bulkReason} onChange={(e) => setBulkReason(e.target.value as SkipReason | '')}>
                <option value="">選んでください</option>
                {Object.entries(SKIP_REASON_LABELS).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          )}
          <button
            type="button"
            className="primary"
            disabled={selected.size > BULK_LIMIT}
            onClick={() => void changeSelected()}
          >
            まとめて変更
          </button>
          {selected.size > BULK_LIMIT && <span className="error">一度に変えられるのは {BULK_LIMIT} 件までです</span>}
        </div>
      )}

      {message && <p className="ok">{message}</p>}
      {error && <p className="error">{error}</p>}

      <table className="list">
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                aria-label="すべて選ぶ"
                checked={items.length > 0 && selected.size === items.length}
                onChange={toggleAll}
              />
            </th>
            <th>ステータス</th>
            <th>提督名</th>
            <th>XのID</th>
            <th>目的</th>
            <th>戦果</th>
            <th>縛り</th>
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
                  <input
                    type="checkbox"
                    aria-label={`${item.admiralName} を選ぶ`}
                    checked={selected.has(item.id)}
                    onChange={() => toggle(item.id)}
                  />
                </td>
                <td>
                  <StatusBadge status={item.status} />
                </td>
                <td>
                  <Link to={`/applications/${item.id}`}>{item.admiralName}</Link>
                  {item.anonymous && <span className="badge anonymous">匿名希望</span>}
                </td>
                <td>@{item.xId}</td>
                <td>{String(item.answers.purpose ?? '')}</td>
                <td>{String(item.answers.rankingEffort ?? '')}</td>
                <td className="nowrap">{String(item.answers.hasRestrictions ?? '')}</td>
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
