import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api } from '../api/client'
import { errorMessage, isConflict, type Application, type HistoryEntry, type SkipReason } from '../api/types'
import AnswerTable from '../components/AnswerTable'
import { useConfirm } from '../components/useConfirm'
import FlagBadges from '../components/FlagBadges'
import Notice from '../components/Notice'
import StatusBadge from '../components/StatusBadge'
import {
  NEXT_STATUSES,
  SKIP_REASON_LABELS,
  STATUS_LABELS,
  formatDateTime,
  formatLongDate,
  formatShortDateTime,
} from '../labels'

/** 画面に読み込むもの一式 */
type Loaded = {
  application: Application
  /** 同じ人の応募（新しい順。この応募も含む） */
  sameApplicant: Application[]
  /** この応募の変更履歴（古い順） */
  history: HistoryEntry[]
}

/** 失敗したときに画面の上に出す内容 */
type Failure = { title: string; message: string; conflict: boolean }

/**
 * 読み込み直したときに、保存されている値へ戻す入力欄のまとまり。
 * status = 「ステータスと配信日」のカード、memo = 「メモ」のカード、xId = 「XのIDを直す」。
 * カードごとに保存ボタンがあるので、保存したカードの入力欄だけを戻し、ほかのカードで入力中の内容は残す
 */
type Section = 'status' | 'memo' | 'xId'
const ALL_SECTIONS: Section[] = ['status', 'memo', 'xId']

/**
 * 「応募内容と前回との比較」の表に出さない項目。
 * 提督名・XのIDは上の見出しに、艦隊データはボタンに、目標・相談内容は「書いてくれたこと」に、別に出すため
 */
const HIDDEN_IN_TABLE = ['admiralName', 'xId', 'simulatorUrl', 'goal', 'comment']

/**
 * 応募の詳細（仕様 7.1）。
 * 左に応募の内容（前回分析した応募との比較、書いてくれたこと、前回の分析の振り返り）、
 * 右にこの応募の操作（ステータスと配信日、メモ、重複の解消、変更の履歴）を置く。
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
  const [failure, setFailure] = useState<Failure | null>(null)
  const [dialog, confirm] = useConfirm()

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

  /**
   * 読み込んだ結果を画面に反映する。sections に入っているまとまりの入力欄は、保存されている値に戻す。
   * 応募の版（version）などの表示は、いつも最新にする
   */
  const applyResult = useCallback(
    (result: { loaded?: Loaded; error?: unknown }, sections: Section[] = ALL_SECTIONS) => {
      if (!result.loaded) {
        setFailure({
          title: '応募を読み込めませんでした',
          message: `${errorMessage(result.error)}。時間をおいてから、画面を読み込み直してください。`,
          conflict: false,
        })
        return
      }
      const application = result.loaded.application
      setLoaded(result.loaded)
      setFailure(null)
      if (sections.includes('status')) {
        setNextStatus(application.status)
        setSkipReason(application.skipReason ?? '')
        setStreamDate(application.streamDate ?? '')
        setAnalysisMemo(application.analysisMemo ?? '')
        setArchiveUrl(application.archiveUrl ?? '')
      }
      if (sections.includes('memo')) {
        setMemo(application.memo ?? '')
      }
      if (sections.includes('xId')) {
        setXIdInput(application.xId)
      }
    },
    [],
  )

  useEffect(() => {
    // 「同じ人の応募」のリンクで別の応募に移ったとき、前の応募の結果が後から届いて上書きしないよう、
    // 後片付け（return の関数）で ignore を true にして古い結果を捨てる
    let ignore = false
    void load().then((result) => {
      if (!ignore) {
        setMessage('')
        applyResult(result)
      }
    })
    return () => {
      ignore = true
    }
  }, [load, applyResult])

  async function reload() {
    setMessage('')
    applyResult(await load())
  }

  if (!loaded) {
    return failure ? (
      <Notice kind="error" title={failure.title}>
        {failure.message}
      </Notice>
    ) : (
      <p>読み込み中…</p>
    )
  }
  const { application, sameApplicant, history } = loaded
  const name = `${application.admiralName} 提督`

  /**
   * 保存などの結果を画面に出す。失敗したら原因を出し、成功したら読み込み直す。
   * ほかの人が先に変更していた（409）ときだけ「最新の状態を読み込む」ボタンを出す。
   * 入力の誤り（400）のときに出すと、押したときに入力中の内容が消えてしまうため
   */
  async function finish(error: unknown, okMessage: string, sections: Section[]) {
    if (error) {
      setMessage('')
      setFailure({ title: `${name}の変更を保存できませんでした`, message: errorMessage(error), conflict: isConflict(error) })
      return
    }
    applyResult(await load(), sections)
    setMessage(okMessage)
  }

  /** 入力の誤りを、保存する前に知らせる */
  function invalid(text: string) {
    setMessage('')
    setFailure({ title: `${name}の変更を保存できませんでした`, message: text, conflict: false })
  }

  async function saveStatus() {
    const willBeSkipped = nextStatus === 'skipped'
    if (willBeSkipped && !skipReason) {
      invalid('見送りにするときは、見送りの理由を選んでください。')
      return
    }
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: {
        // 画面で読み込んだときの版。ほかの人が先に変更していたら保存されず、エラーになる
        version: application.version,
        // 変えない項目は送らない（undefined の項目はJSONに入らない）
        status: nextStatus !== application.status ? nextStatus : undefined,
        // 見送りにするとき（または見送りのまま理由を直すとき）だけ理由を送る
        skipReason: willBeSkipped && skipReason !== application.skipReason ? skipReason || undefined : undefined,
        streamDate: streamDate || undefined,
        // 配信日が入っていたのに空にしたら「消す」
        clearStreamDate: application.streamDate && !streamDate ? true : undefined,
        analysisMemo,
        archiveUrl,
      },
    })
    await finish(error, '変更を保存しました', ['status'])
  }

  async function saveMemo() {
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: { version: application.version, memo },
    })
    await finish(error, 'メモを保存しました', ['memo'])
  }

  async function fixXId() {
    const ok = await confirm({
      title: 'XのIDを直しますか？',
      body: (
        <p className="readable">
          「@{application.xId}」を「{xIdInput.trim()}」に直します。重複・再応募の印と、同じ人の応募のつながりが付け直されます。
          直す前のIDは変更の履歴に残ります。
        </p>
      ),
      confirmLabel: 'XのIDを直す',
    })
    if (!ok) return
    const { error } = await api.PATCH('/applications/{applicationId}', {
      params: { path: { applicationId } },
      body: { version: application.version, xId: xIdInput },
    })
    await finish(error, 'XのIDを直しました', ['xId'])
  }

  const others = sameApplicant.filter((item) => item.id !== application.id)
  // 重複の解消で見送りになる、同じ人のほかの「未着手」「分析予定」の応募
  const queued = (status: string) => status === 'pending' || status === 'scheduled'
  const othersQueued = others.filter((item) => queued(item.status))
  // 重複の解消ができるのは、この応募が「未着手・分析予定」で、同じ人のほかの「未着手・分析予定」の応募があるとき
  const canKeep = queued(application.status) && othersQueued.length > 0

  async function keep() {
    const ok = await confirm({
      title: 'この応募を残しますか？',
      body: (
        <p className="readable">
          同じ人のほかの応募（{othersQueued.map((item) => `${formatShortDateTime(item.receivedAt)} 受付`).join('、')}
          ）を「見送り（重複・再送のため）」にします。
        </p>
      ),
      confirmLabel: `ほかの${othersQueued.length}件を見送りにする`,
    })
    if (!ok) return
    const { error } = await api.POST('/applications/{applicationId}/keep', {
      params: { path: { applicationId } },
      body: { version: application.version },
    })
    await finish(error, '重複を解消しました（ほかの応募を見送りにしました）', ['status'])
  }

  // 比較の相手は「前回分析した応募」（仕様 5.5）: 同じ人の「分析済み」のうち、この応募より前に受け付けた中で一番新しいもの。
  // sameApplicant は新しい順なので、条件に合う最初のものが前回
  const previous = others.find(
    (item) => item.status === 'done' && Date.parse(item.receivedAt) < Date.parse(application.receivedAt),
  )
  // ステータスの選択肢: 今のステータスと、そこから手で変えられるもの（仕様 5.1）
  const statusChoices = [application.status, ...(NEXT_STATUSES[application.status] ?? [])]
  // 分析済みにするとき（または分析済みのとき）は、分析メモとアーカイブのURLを書ける
  const showAnalysis = nextStatus === 'done' || application.status === 'done'
  const goal = String(application.answers.goal ?? '')
  const comment = String(application.answers.comment ?? '')
  // 履歴は新しいものを上に出す（APIは古い順に返す）
  const newestFirst = [...history].reverse()

  return (
    <>
      {dialog}
      <Link className="back-link" to="/">
        応募一覧に戻る
      </Link>

      <div className="detail-head">
        <h1 className="detail-name">
          {application.admiralName}
          <small>提督</small>
        </h1>
        <div className="detail-meta">
          <StatusBadge status={application.status} />
          {application.skipReason && <span className="tag">{SKIP_REASON_LABELS[application.skipReason]}</span>}
          <FlagBadges flags={application.flags} />
          {application.anonymous && <span className="tag">匿名希望</span>}
          <span className="small sub num">
            @{application.xId} ・ {formatDateTime(application.receivedAt)} 受付
          </span>
        </div>
        {/* 艦隊データは制空権シミュレータで開く（仕様 4.2 の項目5。URLを貼るだけで中身は読み取らない） */}
        <a className="button primary simulator" href={application.simulatorUrl} target="_blank" rel="noreferrer">
          艦隊データを開く（制空権シミュレータ）
          <ExternalIcon />
        </a>
      </div>

      {failure && (
        <Notice
          kind="error"
          title={failure.title}
          action={
            failure.conflict && (
              <button type="button" className="button primary" onClick={() => void reload()}>
                最新の状態を読み込む
              </button>
            )
          }
        >
          {failure.message}
        </Notice>
      )}
      {message && <Notice kind="ok" title={message} />}

      <div className="two-columns">
        <div className="main-column">
          {application.flags.length > 0 && (
            <section className="card">
              <h2 className="card-title">印の理由</h2>
              <ul className="plain">
                {application.flags.map((flag) => (
                  <li key={flag.type}>
                    <FlagBadges flags={[flag]} /> {flag.reason}
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className="card">
            <div>
              <h2 className="card-title">{previous ? '応募内容と前回との比較' : '応募内容'}</h2>
              {previous && (
                <p className="card-note row">
                  前回は {formatLongDate(previous.statusChangedAt)}に分析した応募です。
                  <span className="row legend-dot">
                    <span className="changed-dot" aria-hidden="true" />
                    変わった項目
                  </span>
                </p>
              )}
            </div>
            <AnswerTable answers={application.answers} previous={previous?.answers} hidden={HIDDEN_IN_TABLE} />
          </section>

          <section className="card written">
            <h2 className="card-title">書いてくれたこと</h2>
            <div>
              <h3>目標</h3>
              <p className="pre">{goal || '（なし）'}</p>
            </div>
            <div>
              <h3>相談内容・コメント</h3>
              <p className="pre">{comment || '（なし）'}</p>
            </div>
          </section>

          {previous && (
            <section className="card tinted">
              <h2 className="card-title">前回の分析の振り返り</h2>
              <p className="card-note">
                {formatLongDate(previous.statusChangedAt)} 分析済み ・ この欄は管理画面だけに表示されます
              </p>
              <p className="readable pre flush">{previous.analysisMemo || '分析メモはありません。'}</p>
              {previous.archiveUrl && (
                <a className="inline-link" href={previous.archiveUrl} target="_blank" rel="noreferrer">
                  配信アーカイブを開く
                </a>
              )}
              <Link className="inline-link" to={`/applications/${previous.id}`}>
                前回の応募を見る
              </Link>
            </section>
          )}
        </div>

        <aside aria-label="この応募の操作" className="side-column">
          {canKeep && (
            <section className="card compact warn">
              <h2 className="card-title">重複の解消</h2>
              <p className="card-note">
                同じ人の、まだ終わっていない応募がほかに{othersQueued.length}件あります。この応募を残すと、ほかの応募は
                「見送り（重複・再送のため）」になります。
              </p>
              <button type="button" className="button" onClick={() => void keep()}>
                この応募を残す
              </button>
            </section>
          )}

          <section className="card compact">
            <h2 className="card-title">ステータスと配信日</h2>
            <div className="field">
              <label htmlFor="status">ステータス</label>
              <select id="status" value={nextStatus} onChange={(e) => setNextStatus(e.target.value)}>
                {statusChoices.map((code) => (
                  <option key={code} value={code}>
                    {STATUS_LABELS[code]}
                  </option>
                ))}
              </select>
              <span className="field-help">
                {application.status === 'lost'
                  ? '「落選」は抽選でだけ付き、手では変えられません。'
                  : 'いまのステータスから変えられるものだけを出しています。'}
              </span>
            </div>
            {nextStatus === 'skipped' && (
              <div className="field">
                <label htmlFor="skip-reason">見送りの理由</label>
                <select
                  id="skip-reason"
                  value={skipReason}
                  onChange={(e) => setSkipReason(e.target.value as SkipReason | '')}
                >
                  <option value="">選んでください</option>
                  {Object.entries(SKIP_REASON_LABELS).map(([code, label]) => (
                    <option key={code} value={code}>
                      {label}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="field">
              <label htmlFor="stream-date">配信日（なくてもかまいません）</label>
              <div className="row">
                <input id="stream-date" type="date" value={streamDate} onChange={(e) => setStreamDate(e.target.value)} />
                {streamDate && (
                  <button type="button" className="link-button" onClick={() => setStreamDate('')}>
                    配信日を消す
                  </button>
                )}
              </div>
            </div>
            {showAnalysis && (
              <div className="inset">
                <span className="small label-color">分析済みにするときは、次に同じ人を分析するときのために残しておけます。</span>
                <div className="field">
                  <label htmlFor="analysis-memo">分析メモ</label>
                  <textarea
                    id="analysis-memo"
                    rows={3}
                    placeholder="今回の助言の要点"
                    value={analysisMemo}
                    onChange={(e) => setAnalysisMemo(e.target.value)}
                  />
                </div>
                <div className="field">
                  <label htmlFor="archive-url">配信アーカイブのURL（時刻付きも可）</label>
                  <input
                    id="archive-url"
                    type="url"
                    placeholder="https://"
                    value={archiveUrl}
                    onChange={(e) => setArchiveUrl(e.target.value)}
                  />
                </div>
              </div>
            )}
            <button type="button" className="button primary" onClick={() => void saveStatus()}>
              変更を保存する
            </button>
          </section>

          <section className="card compact">
            <label htmlFor="memo" className="card-title">
              メモ
            </label>
            <textarea id="memo" rows={3} value={memo} onChange={(e) => setMemo(e.target.value)} />
            <button type="button" className="button start" onClick={() => void saveMemo()}>
              メモを保存する
            </button>
          </section>

          {others.length > 0 && (
            <section className="card compact">
              <h2 className="card-title">同じ人の応募</h2>
              <ul className="plain">
                {others.map((item) => (
                  <li key={item.id} className="row">
                    <Link to={`/applications/${item.id}`} className="num">
                      {formatDateTime(item.receivedAt)} 受付
                    </Link>
                    <StatusBadge status={item.status} />
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className="card compact">
            <h2 className="card-title">変更の履歴</h2>
            {newestFirst.length === 0 ? (
              <p className="card-note">まだ変更はありません。</p>
            ) : (
              <ol className="history">
                {newestFirst.map((entry) => (
                  <li key={entry.id}>
                    <span>{describe(entry)}</span>
                    <span className="small sub num">
                      {formatShortDateTime(entry.at)} ・ {entry.actor}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </section>

          <details className="more">
            <summary>ほかの操作（XのIDを直す）</summary>
            <div className="field more-body">
              <label htmlFor="x-id">正しいXのID</label>
              <div className="row">
                <input id="x-id" value={xIdInput} onChange={(e) => setXIdInput(e.target.value)} />
                <button
                  type="button"
                  className="button"
                  disabled={!xIdInput.trim() || xIdInput.trim().replace(/^@/, '') === application.xId}
                  onClick={() => void fixXId()}
                >
                  XのIDを直す
                </button>
              </div>
              <span className="field-help">応募者が打ち間違えたときに使います。直す前のIDは変更の履歴に残ります。</span>
            </div>
            {/* TODO(段階7): 削除依頼への対応（運営だけ。確認画面と控えの削除手順の表示）をここに足す */}
          </details>
        </aside>
      </div>
    </>
  )
}

/** 変更の履歴の1行を文にする（例:「分析予定 から 分析中 に変更（次の人へ）」） */
function describe(entry: HistoryEntry): string {
  const note = entry.note ? `（${entry.note}）` : ''
  if (entry.kind === 'status') {
    const from = STATUS_LABELS[entry.from] ?? entry.from
    const to = STATUS_LABELS[entry.to] ?? entry.to
    return `${from} から ${to} に変更${note}`
  }
  return `XのIDを @${entry.from} から @${entry.to} に修正${note}`
}

/** 別のタブで開くリンクの印 */
function ExternalIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M14 4h6v6" />
      <path d="M20 4l-9 9" />
      <path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
    </svg>
  )
}

export default ApplicationDetailPage
