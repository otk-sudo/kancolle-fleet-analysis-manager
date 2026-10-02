import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api } from '../api/client'
import { errorMessage, type Application } from '../api/types'
import AnswerTable from '../components/AnswerTable'
import FlagBadges from '../components/FlagBadges'
import StatusBadge from '../components/StatusBadge'
import { NEXT_STATUSES, STATUS_LABELS, formatDateTime } from '../labels'

/**
 * 応募の詳細（仕様 7.1）。回答の確認、ステータス・配信日・メモの変更、同じ人の過去の応募との比較ができる。
 */
function ApplicationDetailPage() {
  // useParams: URLの :applicationId の部分を取り出す（例: /applications/abc → "abc"）
  const { applicationId = '' } = useParams()
  const [application, setApplication] = useState<Application | null>(null)
  const [history, setHistory] = useState<Application[]>([])
  const [nextStatus, setNextStatus] = useState('')
  const [streamDate, setStreamDate] = useState('')
  const [memo, setMemo] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  /** APIから応募と、同じ人の応募の一覧を読み込む（画面にはまだ反映しない） */
  const load = useCallback(async () => {
    const { data, error } = await api.GET('/applications/{applicationId}', {
      params: { path: { applicationId } },
    })
    if (error || !data) {
      return { error: error ?? 'error' }
    }
    const historyResponse = await api.GET('/applicants/{xId}/applications', {
      params: { path: { xId: data.xId } },
    })
    return { application: data, history: historyResponse.data ?? [] }
  }, [applicationId])

  /** 読み込んだ結果を画面に反映する */
  const applyResult = useCallback((result: { application?: Application; history?: Application[]; error?: unknown }) => {
    if (!result.application) {
      setError(errorMessage(result.error))
      return
    }
    setApplication(result.application)
    setHistory(result.history ?? [])
    setNextStatus('')
    setStreamDate(result.application.streamDate ?? '')
    setMemo(result.application.memo ?? '')
  }, [])

  useEffect(() => {
    // 「同じ人の応募」のリンクで別の応募に移ったとき、前の応募の結果が後から届いて上書きしないよう、
    // 後片付け（return の関数）で ignore を true にして古い結果を捨てる
    let ignore = false
    void load().then((result) => {
      if (!ignore) {
        setMessage('')
        setError('')
        applyResult(result)
      }
    })
    return () => {
      ignore = true
    }
  }, [load, applyResult])

  async function save() {
    if (!application) return
    if (application.streamDate && !streamDate) {
      // TODO(段階2): 配信日を消せるようにする（今はAPIで「消す」と「変えない」を区別できない）
      setMessage('')
      setError('試作版では配信日を消せません。別の日付を選んでください')
      return
    }
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: {
        // 変えない項目は送らない（undefined の項目はJSONに入らない）
        status: nextStatus || undefined,
        streamDate: streamDate || undefined,
        memo,
      },
    })
    if (error) {
      setMessage('')
      setError(errorMessage(error))
      return
    }
    setError('')
    setMessage('保存しました')
    applyResult(await load())
  }

  if (!application) {
    return error ? <p className="error">{error}</p> : <p>読み込み中…</p>
  }

  // 前回の応募 = 同じ人の応募のうち、この応募より前に受け付けたものの中で一番新しいもの（history は新しい順）
  const previous = history.find((item) => item.id !== application.id && Date.parse(item.receivedAt) < Date.parse(application.receivedAt))
  const others = history.filter((item) => item.id !== application.id)

  return (
    <section>
      <p>
        <Link to="/">← 一覧へ戻る</Link>
      </p>
      <h1>
        {application.admiralName} <small>@{application.xId}</small>
      </h1>
      <p className="meta">
        <StatusBadge status={application.status} />
        {application.anonymous && <span className="badge anonymous">匿名希望</span>}
        <FlagBadges flags={application.flags} />
        <span>受付: {formatDateTime(application.receivedAt)}</span>
      </p>
      {application.flags.map((flag) => (
        <p key={flag.type} className="flag-reason">
          ※ {flag.reason}
        </p>
      ))}
      <p>
        {/* 艦隊データは制空権シミュレータで開く（仕様 4.2 の項目5。URLを貼るだけで中身は読み取らない） */}
        <a href={application.simulatorUrl} target="_blank" rel="noreferrer">
          制空権シミュレータで艦隊を開く ↗
        </a>
      </p>

      <div className="columns">
        <div>
          <h2>回答{previous && '（前回との比較）'}</h2>
          <AnswerTable answers={application.answers} previous={previous?.answers} hidden={['simulatorUrl']} />
        </div>

        <div>
          <h2>進捗の変更</h2>
          <div className="form">
            <label>
              ステータス
              <select value={nextStatus} onChange={(e) => setNextStatus(e.target.value)}>
                <option value="">変えない（{STATUS_LABELS[application.status]}）</option>
                {(NEXT_STATUSES[application.status] ?? []).map((code) => (
                  <option key={code} value={code}>
                    {STATUS_LABELS[code]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              配信日
              <input type="date" value={streamDate} onChange={(e) => setStreamDate(e.target.value)} />
            </label>
            <label>
              メモ
              <textarea rows={4} value={memo} onChange={(e) => setMemo(e.target.value)} />
            </label>
            <button type="button" className="primary" onClick={() => void save()}>
              保存
            </button>
            {message && <p className="ok">{message}</p>}
            {error && <p className="error">{error}</p>}
          </div>

          <h2>同じ人の応募</h2>
          {others.length === 0 ? (
            <p>ほかの応募はありません。</p>
          ) : (
            <ul>
              {others.map((item) => (
                <li key={item.id}>
                  <Link to={`/applications/${item.id}`}>{formatDateTime(item.receivedAt)}</Link>{' '}
                  <StatusBadge status={item.status} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  )
}

export default ApplicationDetailPage
