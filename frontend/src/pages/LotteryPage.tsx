import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { fetchAllApplications } from '../api/applications'
import { api } from '../api/client'
import { errorMessage, type Application, type Lottery } from '../api/types'
import { useConfirm } from '../components/useConfirm'
import FlagBadges from '../components/FlagBadges'
import Notice from '../components/Notice'
import { formatShortDateTime, hasBlockingFlag } from '../labels'

/** 読み込むもの一式 */
type Loaded = {
  lotteryEnabled: boolean
  lossBonusEnabled: boolean
  lossBonusStrength: number
  lotteries: Lottery[]
  /** 「未着手」の応募（受付順） */
  pending: Application[]
  /** 抽選記録に名前を出すための「応募ID → 応募」の表 */
  byId: Record<string, Application>
}

/**
 * 抽選（仕様 6章）。まとめ抽選の実行と、これまでの抽選の確認ができる。
 * 抽選の設定は「設定」画面にある。配信中に1人ずつ選ぶ抽選は、配信の画面で行う。
 */
function LotteryPage() {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  // 印（重複・条件外）があっても対象に含める応募のID
  const [includeFlagged, setIncludeFlagged] = useState<Set<string>>(new Set())
  const [winners, setWinners] = useState(1)
  const [message, setMessage] = useState('')
  // 読み込みの失敗と、抽選の失敗は、画面に出す見出しが違うので分けて覚えておく
  const [loadError, setLoadError] = useState('')
  const [runError, setRunError] = useState('')
  const [dialog, confirm] = useConfirm()

  const load = useCallback(async (): Promise<{ loaded?: Loaded; error?: unknown }> => {
    const [settingsResponse, lotteriesResponse, applicationsResponse] = await Promise.all([
      api.GET('/settings/{kind}', { params: { path: { kind: 'lottery' } } }),
      api.GET('/lotteries', { params: { query: { limit: 20 } } }),
      fetchAllApplications({ order: 'received' }),
    ])
    if (!settingsResponse.data || !lotteriesResponse.data || applicationsResponse.error) {
      return { error: settingsResponse.error ?? lotteriesResponse.error ?? applicationsResponse.error }
    }
    const value = settingsResponse.data.value
    const byId: Record<string, Application> = {}
    for (const application of applicationsResponse.items) {
      byId[application.id] = application
    }
    return {
      loaded: {
        lotteryEnabled: value.enabled === true,
        lossBonusEnabled: value.lossBonusEnabled === true,
        lossBonusStrength: Number(value.lossBonusStrength ?? 1),
        lotteries: lotteriesResponse.data.items,
        pending: applicationsResponse.items.filter((a) => a.status === 'pending'),
        byId,
      },
    }
  }, [])

  const applyResult = useCallback((result: { loaded?: Loaded; error?: unknown }) => {
    if (!result.loaded) {
      setLoadError(errorMessage(result.error))
      return
    }
    setLoadError('')
    setLoaded(result.loaded)
    setIncludeFlagged(new Set())
  }, [])

  useEffect(() => {
    let ignore = false
    void load().then((result) => {
      if (!ignore) applyResult(result)
    })
    return () => {
      ignore = true
    }
  }, [load, applyResult])

  if (!loaded) {
    return loadError ? (
      <Notice kind="error" title="抽選の情報を読み込めませんでした">
        {loadError}。時間をおいてから、画面を読み込み直してください。
      </Notice>
    ) : (
      <p>読み込み中…</p>
    )
  }

  // 抽選の対象: 「未着手」で、印がないか、印があっても対象に含めると選んだもの。
  // 同じ人（同じXのID）の応募は1人1回分だけ（受付が早いほう）。backend の LotteryService.candidates と同じ決め方
  const seen = new Set<string>()
  const rows = loaded.pending.map((application) => {
    const flagged = hasBlockingFlag(application)
    const included = !flagged || includeFlagged.has(application.id)
    let counted = false
    if (included && !seen.has(application.xId)) {
      seen.add(application.xId)
      counted = true
    }
    return { application, flagged, included, counted }
  })
  const targetCount = rows.filter((row) => row.counted).length
  // 重複が解消されていない人（重複の印がついた未着手の応募がある人）。同じ人は1回だけ知らせる
  const unresolved = new Map<string, { application: Application; earlierPending: boolean }>()
  for (const application of loaded.pending) {
    if (unresolved.has(application.xId) || !application.flags.some((flag) => flag.type === 'duplicate')) continue
    // 重複の印は、先に受け付けた応募がまだ終わっていないときに、あとの応募に付く。
    // 先の応募が「未着手」で印がなければ、そちらが抽選に入る。先の応募が「分析予定」などなら、どちらも抽選に入らない
    const earlierPending = loaded.pending.some(
      (other) =>
        other.xId === application.xId &&
        other.id !== application.id &&
        !hasBlockingFlag(other) &&
        Date.parse(other.receivedAt) <= Date.parse(application.receivedAt),
    )
    unresolved.set(application.xId, { application, earlierPending })
  }
  // 当選人数が対象の人数より多いと、全員が当選するだけになるので抽選できないようにする
  const tooMany = winners > targetCount

  function toggle(id: string) {
    const next = new Set(includeFlagged)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    setIncludeFlagged(next)
  }

  async function runBulk() {
    // 結果は元に戻せないので、実行前に内容を確かめてもらう
    const ok = await confirm({
      title: `${targetCount}人から${winners}人を抽選しますか？`,
      body: (
        <p className="readable">
          当選した{winners}人は「分析予定」、外れた{targetCount - winners}人は「落選」になります。抽選の結果は記録に残ります。
          {loaded?.lossBonusEnabled && ' 落選した回数が多い人ほど当たりやすくなります（落選補正）。'}
        </p>
      ),
      confirmLabel: `${winners}人を抽選する`,
      cancelLabel: '戻って見直す',
      danger: true,
    })
    if (!ok) return
    const { data, error } = await api.POST('/lotteries', {
      body: { mode: 'bulk', winners, includeFlagged: [...includeFlagged] },
    })
    if (error || !data) {
      setMessage('')
      setRunError(errorMessage(error))
      return
    }
    setRunError('')
    applyResult(await load())
    setMessage(`抽選しました。${data.entries.length}人から${data.entries.filter((e) => e.won).length}人が当選しました。`)
  }

  function nameOf(applicationId: string) {
    const application = loaded?.byId[applicationId]
    return application ? `${application.admiralName} 提督` : '（削除済みの応募）'
  }

  return (
    <>
      {dialog}
      <div>
        <h1 className="page-title">抽選</h1>
        <p className="page-lead">
          受付を締め切ったあとに、未着手の応募からまとめて当選者を選びます。配信中に1人ずつ選ぶときは、配信の画面を使います。
        </p>
        <p className="page-lead label-color">
          いまの設定:{' '}
          {loaded.lossBonusEnabled
            ? `落選補正あり（当たりやすさ ＝ 1 ＋ 落選回数 × ${loaded.lossBonusStrength}）`
            : '落選補正なし（全員が同じ当たりやすさ）'}
          <Link to="/settings" className="settings-link">
            設定を変える
          </Link>
        </p>
      </div>

      {runError && (
        <Notice kind="error" title="抽選できませんでした">
          {runError}
        </Notice>
      )}
      {loadError && (
        <Notice kind="error" title="抽選の情報を読み込み直せませんでした">
          {loadError}。画面を読み込み直してください。
        </Notice>
      )}
      {message && <Notice kind="ok" title={message} />}
      {!loaded.lotteryEnabled && (
        <Notice kind="info" title="抽選はオフになっています" action={<Link to="/settings">設定を開く</Link>}>
          抽選するときは、設定で「抽選を使う」をオンにしてください。
        </Notice>
      )}

      <div className="two-columns">
        <section className="card main-column">
          <h2 className="card-title">抽選の対象（{targetCount}人）</h2>

          {[...unresolved.values()].map(({ application, earlierPending }) => (
            <div key={application.id} role="note" className="notice notice-error">
              <span className="notice-body">
                <strong>{application.admiralName} 提督の重複が解消されていません。</strong>
                {earlierPending
                  ? 'このまま抽選すると、受付が早い方の応募だけが抽選に入ります。'
                  : '先に受け付けた応募がまだ終わっていない（分析予定など）ため、この人の応募は抽選に入りません。'}
                どちらの応募を残すか、先に決めておくのがおすすめです。
              </span>
              <Link to={`/applications/${application.id}`}>{application.admiralName} 提督の応募を確認する</Link>
            </div>
          ))}

          {rows.length === 0 ? (
            <p className="card-note">「未着手」の応募はありません。</p>
          ) : (
            <div className="table-scroll">
              <table className="simple">
                <thead>
                  <tr>
                    <th scope="col">対象に含める</th>
                    <th scope="col">提督名</th>
                    <th scope="col">印</th>
                    <th scope="col">受付</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(({ application, flagged, included, counted }) => (
                    <tr key={application.id} className={counted ? undefined : 'excluded'}>
                      <td>
                        {/* 印のない応募は必ず対象になるので、外せない（チェックを変えられない） */}
                        <label className="check-cell">
                          <input
                            type="checkbox"
                            aria-label={`${application.admiralName} 提督を抽選の対象に含める`}
                            checked={included}
                            disabled={!flagged}
                            onChange={() => toggle(application.id)}
                          />
                        </label>
                      </td>
                      <td>
                        <Link className="applicant-name" to={`/applications/${application.id}`}>
                          {application.admiralName}
                        </Link>
                        {included && !counted && <span className="tag">同じ人の先の応募が対象</span>}
                      </td>
                      <td>
                        {application.flags.length > 0 ? <FlagBadges flags={application.flags} /> : '—'}
                      </td>
                      <td className="num sub">{formatShortDateTime(application.receivedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="card-note">
            重複・条件外の印がある応募は、はじめは対象から外しています。チェックを入れると対象に戻せます。
            {/* TODO(段階6): 対象者ごとの落選回数と当たりやすさ、抽選前の確認画面、一番新しい抽選の取り消しを足す */}
          </p>
        </section>

        <aside className="side-column">
          <section className="card compact">
            <h2 className="card-title">まとめ抽選</h2>
            <div className="field">
              <label htmlFor="winners">当選人数</label>
              <div className="row">
                <input
                  id="winners"
                  type="number"
                  min={1}
                  max={Math.max(targetCount, 1)}
                  value={winners}
                  onChange={(e) => setWinners(Number(e.target.value))}
                  className="number-input"
                />
                <span>人 ／ 対象 {targetCount}人</span>
              </div>
              {tooMany && <span className="field-help error">当選人数は、対象の人数（{targetCount}人）以下にしてください。</span>}
            </div>
            <button
              type="button"
              className="button primary"
              disabled={!loaded.lotteryEnabled || targetCount === 0 || winners < 1 || tooMany}
              onClick={() => void runBulk()}
            >
              抽選の内容を確認する
            </button>
            <span className="field-help">次に出る確認で、内容を確かめてから抽選します。</span>
          </section>

          <section className="card compact">
            <h2 className="card-title">これまでの抽選</h2>
            {loaded.lotteries.length === 0 ? (
              <p className="card-note">まだ抽選していません。</p>
            ) : (
              <ol className="history">
                {loaded.lotteries.map((lottery) => {
                  const won = lottery.entries.filter((e) => e.won)
                  return (
                    <li key={lottery.id}>
                      <span className="num">
                        {formatShortDateTime(lottery.executedAt)} {lottery.mode === 'bulk' ? 'まとめ抽選' : '配信中の抽選'}
                      </span>
                      <span className="small sub">
                        {lottery.entries.length}人から{won.length}人が当選 ・ {lottery.executedBy}
                      </span>
                      <details className="more">
                        <summary>対象者ごとの当たりやすさと結果</summary>
                        <div className="table-scroll">
                          <table className="simple compact-table">
                            <thead>
                              <tr>
                                <th scope="col">提督名</th>
                                <th scope="col">当たりやすさ</th>
                                <th scope="col">結果</th>
                              </tr>
                            </thead>
                            <tbody>
                              {lottery.entries.map((entry) => (
                                <tr key={entry.applicationId}>
                                  <td>
                                    <Link to={`/applications/${entry.applicationId}`}>{nameOf(entry.applicationId)}</Link>
                                  </td>
                                  <td className="num">{entry.weight}</td>
                                  <td>{entry.won ? '当選' : '—'}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                        {/* 同じ種と対象者なら同じ結果になる。後から公平性を確かめるために記録している（仕様 6.4） */}
                        <span className="small sub">乱数の種: {lottery.seed}</span>
                      </details>
                    </li>
                  )
                })}
              </ol>
            )}
          </section>
        </aside>
      </div>
    </>
  )
}

export default LotteryPage
