import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api } from '../api/client'
import { errorMessage, type Application, type HistoryEntry, type SkipReason } from '../api/types'
import AnswerTable from '../components/AnswerTable'
import FlagBadges from '../components/FlagBadges'
import StatusBadge from '../components/StatusBadge'
import { NEXT_STATUSES, SKIP_REASON_LABELS, STATUS_LABELS, formatDateTime } from '../labels'

/** 画面に読み込むもの一式 */
type Loaded = {
  application: Application
  /** 同じ人の応募（新しい順。この応募も含む） */
  sameApplicant: Application[]
  /** この応募の変更履歴（古い順） */
  history: HistoryEntry[]
}

/**
 * 応募の詳細（仕様 7.1）。
 * 回答の確認、ステータス・配信日・メモ・分析メモの変更、XのIDの修正、重複の解消、
 * 前回分析した応募との比較、変更履歴の確認ができる。
 */
function ApplicationDetailPage() {
  // useParams: URLの :applicationId の部分を取り出す（例: /applications/abc → "abc"）
  const { applicationId = '' } = useParams()
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [nextStatus, setNextStatus] = useState('')
  const [skipReason, setSkipReason] = useState<SkipReason | ''>('')
  const [streamDate, setStreamDate] = useState('')
  const [memo, setMemo] = useState('')
  const [analysisMemo, setAnalysisMemo] = useState('')
  const [archiveUrl, setArchiveUrl] = useState('')
  const [xIdInput, setXIdInput] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  // 保存などに失敗したときに「読み込み直す」ボタンを出すため
  const [conflict, setConflict] = useState(false)

  /** APIから応募・同じ人の応募・変更履歴を読み込む（画面にはまだ反映しない） */
  const load = useCallback(async (): Promise<{ loaded?: Loaded; error?: unknown }> => {
    const { data, error } = await api.GET('/applications/{applicationId}', {
      params: { path: { applicationId } },
    })
    if (error || !data) {
      return { error: error ?? 'error' }
    }
    // Promise.all: 2つの読み込みを同時に始めて、両方が終わるのを待つ（1つずつ待つより速い）
    const [sameResponse, historyResponse] = await Promise.all([
      api.GET('/applicants/{xId}/applications', { params: { path: { xId: data.xId } } }),
      api.GET('/applications/{applicationId}/history', { params: { path: { applicationId } } }),
    ])
    return {
      loaded: { application: data, sameApplicant: sameResponse.data ?? [], history: historyResponse.data ?? [] },
    }
  }, [applicationId])

  /** 読み込んだ結果を画面に反映する（入力欄も、保存されている値に戻す） */
  const applyResult = useCallback((result: { loaded?: Loaded; error?: unknown }) => {
    if (!result.loaded) {
      setError(errorMessage(result.error))
      return
    }
    const application = result.loaded.application
    setLoaded(result.loaded)
    setConflict(false)
    setNextStatus('')
    setSkipReason(application.skipReason ?? '')
    setStreamDate(application.streamDate ?? '')
    setMemo(application.memo ?? '')
    setAnalysisMemo(application.analysisMemo ?? '')
    setArchiveUrl(application.archiveUrl ?? '')
    setXIdInput(application.xId)
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

  /** 保存などの結果を画面に出す。失敗したら理由を出し、成功したら読み込み直す */
  async function finish(error: unknown, okMessage: string) {
    if (error) {
      setMessage('')
      setError(errorMessage(error))
      // ほかの人が先に変更していた（409）などのとき、最新の内容を読み込み直してからやり直せるようにボタンを出す
      setConflict(true)
      return
    }
    setError('')
    setMessage(okMessage)
    applyResult(await load())
  }

  async function reload() {
    setMessage('')
    setError('')
    applyResult(await load())
  }

  if (!loaded) {
    return error ? <p className="error">{error}</p> : <p>読み込み中…</p>
  }
  const { application, sameApplicant, history } = loaded

  async function save() {
    // 見送りにするとき（または見送りのまま理由を直すとき）だけ理由を送る
    const willBeSkipped = (nextStatus || application.status) === 'skipped'
    if (willBeSkipped && !skipReason) {
      setMessage('')
      setError('見送りにするときは理由を選んでください')
      return
    }
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: {
        // 画面で読み込んだときの版。ほかの人が先に変更していたら保存されず、エラーになる
        version: application.version,
        // 変えない項目は送らない（undefined の項目はJSONに入らない）
        status: nextStatus || undefined,
        skipReason: willBeSkipped && skipReason !== application.skipReason ? skipReason || undefined : undefined,
        streamDate: streamDate || undefined,
        // 配信日が入っていたのに空にしたら「消す」
        clearStreamDate: application.streamDate && !streamDate ? true : undefined,
        memo,
        analysisMemo,
        archiveUrl,
      },
    })
    await finish(error, '保存しました')
  }

  async function fixXId() {
    if (!window.confirm(`XのIDを「@${application.xId}」から「${xIdInput}」に直します。よろしいですか？\n（重複・再応募の印と、同じ人の応募のつながりが付け直されます）`)) {
      return
    }
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: { version: application.version, xId: xIdInput },
    })
    await finish(error, 'XのIDを直しました')
  }

  async function keep() {
    if (!window.confirm('この応募を残し、同じ人のほかの「未着手」「分析予定」の応募を「見送り（重複（再送のため））」にします。よろしいですか？')) {
      return
    }
    const { error } = await api.POST('/applications/{applicationId}/keep', {
      params: { path: { applicationId } },
      body: { version: application.version },
    })
    await finish(error, '重複を解消しました（ほかの応募を見送りにしました）')
  }

  const others = sameApplicant.filter((item) => item.id !== application.id)
  // 比較の相手は「前回分析した応募」（仕様 5.5）: 同じ人の「分析済み」のうち、この応募より前に受け付けた中で一番新しいもの。
  // sameApplicant は新しい順なので、条件に合う最初のものが前回
  const previous = others.find(
    (item) => item.status === 'done' && Date.parse(item.receivedAt) < Date.parse(application.receivedAt),
  )
  // 重複の解消ができるのは、この応募が「未着手・分析予定」で、同じ人のほかの「未着手・分析予定」の応募があるとき
  const queued = (status: string) => status === 'pending' || status === 'scheduled'
  const canKeep = queued(application.status) && others.some((item) => queued(item.status))
  const showSkipReason = (nextStatus || application.status) === 'skipped'

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
        {application.skipReason && <span className="badge">{SKIP_REASON_LABELS[application.skipReason]}</span>}
        {application.anonymous && <span className="badge anonymous">匿名希望</span>}
        <FlagBadges flags={application.flags} />
        <span>受付: {formatDateTime(application.receivedAt)}</span>
      </p>
      {application.flags.map((flag) => (
        <p key={flag.type} className="flag-reason">
          ※ {flag.reason}
        </p>
      ))}
      {canKeep && (
        <p>
          <button type="button" onClick={() => void keep()}>
            重複の解消: この応募を残す
          </button>{' '}
          <small className="muted">同じ人のほかの「未着手」「分析予定」の応募を見送りにします</small>
        </p>
      )}
      <p>
        {/* 艦隊データは制空権シミュレータで開く（仕様 4.2 の項目5。URLを貼るだけで中身は読み取らない） */}
        <a href={application.simulatorUrl} target="_blank" rel="noreferrer">
          制空権シミュレータで艦隊を開く ↗
        </a>
      </p>

      {message && <p className="ok">{message}</p>}
      {error && (
        <p className="error">
          {error}{' '}
          {conflict && (
            <button type="button" onClick={() => void reload()}>
              読み込み直す
            </button>
          )}
        </p>
      )}

      <div className="columns">
        <div>
          <h2>回答{previous && '（前回分析した応募との比較）'}</h2>
          <AnswerTable answers={application.answers} previous={previous?.answers} hidden={['simulatorUrl']} />

          {previous && (
            <>
              <h2>前回の分析</h2>
              <dl className="previous">
                <dt>分析した日</dt>
                <dd>{formatDateTime(previous.statusChangedAt)}</dd>
                <dt>分析メモ</dt>
                <dd className="pre">{previous.analysisMemo || '（なし）'}</dd>
                <dt>配信アーカイブ</dt>
                <dd>
                  {previous.archiveUrl ? (
                    <a href={previous.archiveUrl} target="_blank" rel="noreferrer">
                      アーカイブを開く ↗
                    </a>
                  ) : (
                    '（なし）'
                  )}
                </dd>
              </dl>
            </>
          )}
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
            {application.status === 'lost' && <small className="muted">「落選」は抽選でだけ付き、手では変えられません</small>}
            {showSkipReason && (
              <label>
                見送りの理由
                <select value={skipReason} onChange={(e) => setSkipReason(e.target.value as SkipReason | '')}>
                  <option value="">選んでください</option>
                  {Object.entries(SKIP_REASON_LABELS).map(([code, label]) => (
                    <option key={code} value={code}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label>
              配信日
              <span className="inline">
                <input type="date" value={streamDate} onChange={(e) => setStreamDate(e.target.value)} />
                {streamDate && (
                  <button type="button" onClick={() => setStreamDate('')}>
                    消す
                  </button>
                )}
              </span>
            </label>
            <label>
              メモ
              <textarea rows={3} value={memo} onChange={(e) => setMemo(e.target.value)} />
            </label>
            <label>
              分析メモ（配信者が残す要点。管理画面だけに出ます）
              <textarea rows={3} value={analysisMemo} onChange={(e) => setAnalysisMemo(e.target.value)} />
            </label>
            <label>
              配信アーカイブのURL（時刻付きも可）
              <input
                type="url"
                placeholder="https://..."
                value={archiveUrl}
                onChange={(e) => setArchiveUrl(e.target.value)}
              />
            </label>
            <button type="button" className="primary" onClick={() => void save()}>
              保存
            </button>
          </div>

          <h2>XのIDの修正</h2>
          <div className="form">
            <label>
              正しいXのID
              <span className="inline">
                <input value={xIdInput} onChange={(e) => setXIdInput(e.target.value)} />
                <button type="button" disabled={!xIdInput.trim()} onClick={() => void fixXId()}>
                  直す
                </button>
              </span>
            </label>
            <small className="muted">応募者が打ち間違えたときに使います。変更前のIDは変更履歴に残ります</small>
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

          <h2>変更履歴</h2>
          {history.length === 0 ? (
            <p>まだ変更はありません。</p>
          ) : (
            <ul className="history">
              {history.map((entry) => (
                <li key={entry.id}>
                  {formatDateTime(entry.at)} {entry.actor}:{' '}
                  {entry.kind === 'status'
                    ? `${STATUS_LABELS[entry.from] ?? entry.from} → ${STATUS_LABELS[entry.to] ?? entry.to}`
                    : `XのID @${entry.from} → @${entry.to}`}
                  {entry.note && <small className="muted">（{entry.note}）</small>}
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
