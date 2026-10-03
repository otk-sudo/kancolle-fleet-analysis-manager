import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { errorMessage, isConflict, type Settings, type StreamOperator } from '../api/types'
import { useAuth } from '../auth/useAuth'
import Notice from '../components/Notice'

/**
 * 設定（仕様 7.1 の4）。いまは抽選の設定と、関係者への配信の操作の許可だけ。
 * 設定を変えられない人（関係者）には、抽選の設定を見せるだけにする。
 * TODO(段階5): 配信用画面のURL（閲覧専用URLの発行・再発行）を足す
 * TODO(段階7): 条件ルール・選択肢・ステータス・ユーザー管理を足す（左のメニューに項目が増える）
 */
function SettingsPage() {
  const { can } = useAuth()
  const canEdit = can('editSettings')
  const canManageOperators = can('manageStreamOperators')
  const [settings, setSettings] = useState<Settings | null>(null)
  const [message, setMessage] = useState('')
  // 失敗したときに出す見出しと内容。読み込みの失敗と保存の失敗で見出しを変える
  const [failure, setFailure] = useState<{ title: string; message: string; conflict: boolean } | null>(null)

  const load = useCallback(async () => {
    const { data, error } = await api.GET('/settings/{kind}', { params: { path: { kind: 'lottery' } } })
    return { data, error }
  }, [])

  const applyResult = useCallback((result: Awaited<ReturnType<typeof load>>) => {
    if (!result.data) {
      setFailure({
        title: '設定を読み込めませんでした',
        message: `${errorMessage(result.error)}。時間をおいてから、画面を読み込み直してください。`,
        conflict: false,
      })
      return
    }
    setSettings(result.data)
    setFailure(null)
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

  /** 設定の値を1つ変える（画面の上だけ。保存ボタンでAPIへ送る） */
  function change(key: string, value: boolean | number) {
    if (!settings) return
    setMessage('')
    setSettings({ ...settings, value: { ...settings.value, [key]: value } })
  }

  async function save() {
    if (!settings) return
    // 読み込んだときの版（settings.version）も一緒に送る。ほかの人が先に変えていたら保存されない
    const { error } = await api.PUT('/settings/{kind}', {
      params: { path: { kind: 'lottery' } },
      body: settings,
    })
    if (error) {
      setMessage('')
      setFailure({ title: '設定を保存できませんでした', message: errorMessage(error), conflict: isConflict(error) })
      return
    }
    applyResult(await load())
    setMessage('抽選の設定を保存しました。次の抽選から使われます。')
  }

  async function reload() {
    setMessage('')
    applyResult(await load())
  }

  const value = settings?.value ?? {}
  const strength = Number(value.lossBonusStrength ?? 1)

  return (
    <>
      <h1 className="page-title">設定</h1>

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
        <nav aria-label="設定の項目" className="settings-menu">
          <a href="#lottery">抽選</a>
          {canManageOperators && <a href="#stream-operators">配信の操作の許可</a>}
        </nav>

        <div className="settings-body">
          {settings && (
            <section id="lottery" className="card">
              <div>
                <h2 className="card-heading">抽選</h2>
                <p className="page-lead">応募が多いときに、分析する人を抽選で選ぶための設定です。</p>
              </div>

              {/* fieldset: 中の入力欄をまとめて扱う枠。disabled にすると、中の入力欄をすべて変えられなくなる */}
              <fieldset className="plain-fieldset" disabled={!canEdit}>
                <label className="check-option">
                  <input type="checkbox" checked={value.enabled === true} onChange={(e) => change('enabled', e.target.checked)} />
                  <span>
                    <strong>抽選を使う</strong>
                    <br />
                    <span className="small sub">使わないときは、受付順（と手で並べ替えた順）で分析します。</span>
                  </span>
                </label>

                <label className="check-option">
                  <input
                    type="checkbox"
                    checked={value.lossBonusEnabled === true}
                    onChange={(e) => change('lossBonusEnabled', e.target.checked)}
                  />
                  <span>
                    <strong>落選した回数が多い人を当たりやすくする（落選補正）</strong>
                    <br />
                    <span className="small sub">前に当選してから外れた回数が多いほど、当たりやすくなります。</span>
                  </span>
                </label>

                {value.lossBonusEnabled === true && (
                  <div className="field indented">
                    <label htmlFor="strength">補正の強さ</label>
                    <div className="row">
                      <span>当たりやすさ ＝ 1 ＋ 落選回数 ×</span>
                      <input
                        id="strength"
                        type="number"
                        step={0.5}
                        min={0}
                        value={strength}
                        onChange={(e) => change('lossBonusStrength', Number(e.target.value))}
                        className="number-input"
                      />
                    </div>
                    <span className="small sub">
                      例: 2回落選した人は、はじめて応募した人の{1 + 2 * strength}倍当たりやすくなります。
                    </span>
                  </div>
                )}
              </fieldset>

              {canEdit ? (
                <div className="row card-footer">
                  <button type="button" className="button primary" onClick={() => void save()}>
                    抽選の設定を保存する
                  </button>
                  <span className="small sub">保存した内容は、次の抽選から使われます。</span>
                </div>
              ) : (
                <p className="small sub">設定を変えられるのは、配信者と運営だけです。</p>
              )}
            </section>
          )}

          {canManageOperators && <StreamOperatorsSection />}
        </div>
      </div>
    </>
  )
}

/**
 * 関係者への「配信の操作」の許可（仕様 2章）。配信者と運営だけが使える。
 * 許可した関係者は、配信用画面で「次の人へ」や「配信中の抽選」を押せるようになる。初めは誰も許可していない。
 */
function StreamOperatorsSection() {
  const [operators, setOperators] = useState<StreamOperator[] | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  // 通信中の関係者のID。押している間に同じ人のスイッチをもう一度押せないようにする
  const [busyId, setBusyId] = useState<string | null>(null)

  useEffect(() => {
    let ignore = false
    void api.GET('/stream/operators').then(({ data, error }) => {
      if (ignore) return
      if (data) setOperators(data)
      else setError(errorMessage(error))
    })
    return () => {
      ignore = true
    }
  }, [])

  async function change(operator: StreamOperator, allowed: boolean) {
    setBusyId(operator.userId)
    setMessage('')
    const params = { params: { path: { userId: operator.userId } } }
    // 許可するときは PUT（登録）、取り消すときは DELETE（削除）を送る
    const { error } = allowed
      ? await api.PUT('/stream/operators/{userId}', params)
      : await api.DELETE('/stream/operators/{userId}', params)
    setBusyId(null)
    if (error) {
      setError(errorMessage(error))
      return
    }
    setError('')
    setOperators((current) =>
      (current ?? []).map((item) => (item.userId === operator.userId ? { ...item, allowed } : item)),
    )
    setMessage(
      allowed
        ? `${operator.displayName}さんに配信の操作を許可しました。`
        : `${operator.displayName}さんの配信の操作の許可を取り消しました。`,
    )
  }

  return (
    <section id="stream-operators" className="card">
      <div>
        <h2 className="card-heading">配信の操作の許可</h2>
        <p className="page-lead">
          関係者に、配信中の操作（「次の人へ」「配信中の抽選」）を手伝ってもらうときに許可します。まとめ抽選や設定の変更は、許可してもできません。
        </p>
      </div>
      {error && (
        <Notice kind="error" title="配信の操作の許可を変えられませんでした">
          {error}
        </Notice>
      )}
      {message && <Notice kind="ok" title={message} />}
      {operators &&
        (operators.length === 0 ? (
          <p className="card-note">関係者はまだいません。</p>
        ) : (
          <ul className="plain">
            {operators.map((operator) => (
              <li key={operator.userId}>
                <label className="check-option">
                  <input
                    type="checkbox"
                    checked={operator.allowed}
                    disabled={busyId === operator.userId}
                    onChange={(e) => void change(operator, e.target.checked)}
                  />
                  <span>
                    <strong>{operator.displayName}</strong>
                    <br />
                    <span className="small sub">{operator.allowed ? '配信の操作ができます' : '見るだけ'}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        ))}
    </section>
  )
}

export default SettingsPage
