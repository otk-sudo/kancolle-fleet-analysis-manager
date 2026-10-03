import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { errorMessage, isConflict, type Settings } from '../api/types'
import Notice from '../components/Notice'

/**
 * 設定（仕様 7.1 の4）。いまは抽選の設定だけ。
 * TODO(段階5): フォームとのつなぎ方（Apps Script のウェブアプリのURLと秘密キー）を足す
 * TODO(段階6): バックアップの設定を足す
 * TODO(段階9): 条件ルール・選択肢・ステータスを足す（左のメニューに項目が増える）
 */
function SettingsPage() {
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
          <a href="#lottery" aria-current="true">
            抽選
          </a>
        </nav>

        <div className="settings-body">
          {settings && (
            <section id="lottery" className="card">
              <div>
                <h2 className="card-heading">抽選</h2>
                <p className="page-lead">応募が多いときに、分析する人を抽選で選ぶための設定です。</p>
              </div>

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

              <div className="row card-footer">
                <button type="button" className="button primary" onClick={() => void save()}>
                  抽選の設定を保存する
                </button>
                <span className="small sub">保存した内容は、次の抽選から使われます。</span>
              </div>
            </section>
          )}
        </div>
      </div>
    </>
  )
}

export default SettingsPage
