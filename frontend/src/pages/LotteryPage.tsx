import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import { errorMessage, type Application, type Lottery, type Settings } from '../api/types'
import { formatDateTime } from '../labels'

/**
 * 抽選（仕様 6章）。抽選設定の変更、まとめ抽選の実行、抽選記録の確認ができる。
 * 配信中の抽選（1人だけ）は配信用画面から行う。
 */
function LotteryPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [lotteries, setLotteries] = useState<Lottery[]>([])
  // 抽選記録には応募IDしかないので、名前を出すために応募の一覧を「ID → 応募」の表にしておく
  const [applications, setApplications] = useState<Record<string, Application>>({})
  const [pendingCount, setPendingCount] = useState(0)
  const [winners, setWinners] = useState(3)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const [settingsResponse, lotteriesResponse, applicationsResponse] = await Promise.all([
      api.GET('/settings/{kind}', { params: { path: { kind: 'lottery' } } }),
      api.GET('/lotteries', { params: { query: { limit: 20 } } }),
      api.GET('/applications', { params: { query: { limit: 200, order: 'received' } } }),
    ])
    if (!settingsResponse.data || !lotteriesResponse.data || !applicationsResponse.data) {
      setError(errorMessage(settingsResponse.error ?? lotteriesResponse.error ?? applicationsResponse.error))
      return
    }
    setSettings(settingsResponse.data)
    setLotteries(lotteriesResponse.data.items)
    const byId: Record<string, Application> = {}
    for (const application of applicationsResponse.data.items) {
      byId[application.id] = application
    }
    setApplications(byId)
    setPendingCount(applicationsResponse.data.items.filter((a) => a.status === 'pending').length)
  }, [])

  useEffect(() => {
    // APIからの読み込みは「外部との同期」なので effect で行う。setState は通信が終わった後（非同期）に呼ばれるため、
    // lint が心配する「描画の連鎖」は起きない
    // oxlint-disable-next-line react/set-state-in-effect
    void load()
  }, [load])

  /** 設定の値を1つ変える（画面の上だけ。保存ボタンでAPIへ送る） */
  function changeSetting(key: string, value: boolean | number) {
    if (!settings) return
    setSettings({ ...settings, value: { ...settings.value, [key]: value } })
  }

  async function saveSettings() {
    if (!settings) return
    const { error } = await api.PUT('/settings/{kind}', {
      params: { path: { kind: 'lottery' } },
      body: settings,
    })
    if (error) {
      setError(errorMessage(error))
      return
    }
    setError('')
    setMessage('抽選設定を保存しました')
    await load()
  }

  async function runBulk() {
    // 結果は元に戻せないので、実行前に確認する
    if (!window.confirm(`「未着手」の応募から ${winners} 人を抽選します。外れた人は「落選」になります。よろしいですか？`)) {
      return
    }
    const { data, error } = await api.POST('/lotteries', { body: { mode: 'bulk', winners } })
    if (error || !data) {
      setError(errorMessage(error))
      return
    }
    setError('')
    setMessage(`抽選しました（対象 ${data.entries.length} 人、当選 ${data.entries.filter((e) => e.won).length} 人）`)
    await load()
  }

  function nameOf(applicationId: string) {
    return applications[applicationId]?.admiralName ?? '（削除済み）'
  }

  const value = settings?.value ?? {}

  return (
    <section>
      <h1>抽選</h1>
      {message && <p className="ok">{message}</p>}
      {error && <p className="error">{error}</p>}

      <div className="columns">
        <div>
          <h2>抽選設定</h2>
          {settings && (
            <div className="form">
              <label className="inline">
                <input
                  type="checkbox"
                  checked={value.enabled === true}
                  onChange={(e) => changeSetting('enabled', e.target.checked)}
                />
                抽選を使う
              </label>
              <label className="inline">
                <input
                  type="checkbox"
                  checked={value.lossBonusEnabled === true}
                  onChange={(e) => changeSetting('lossBonusEnabled', e.target.checked)}
                />
                落選した回数に応じて当たりやすくする（落選補正）
              </label>
              <label>
                補正の強さ（当たりやすさ = 1 + 落選回数 × 強さ）
                <input
                  type="number"
                  min={0}
                  step={0.5}
                  value={Number(value.lossBonusStrength ?? 1)}
                  onChange={(e) => changeSetting('lossBonusStrength', Number(e.target.value))}
                />
              </label>
              <button type="button" onClick={() => void saveSettings()}>
                設定を保存
              </button>
            </div>
          )}
        </div>

        <div>
          <h2>まとめ抽選</h2>
          <p>
            「未着手」で印のない応募（いま {pendingCount} 件の未着手のうち、印のないもの）から抽選します。
            当選は「分析予定」、外れは「落選」になります。
          </p>
          <div className="form">
            <label>
              当選人数
              <input type="number" min={1} value={winners} onChange={(e) => setWinners(Number(e.target.value))} />
            </label>
            <button type="button" className="primary" onClick={() => void runBulk()} disabled={value.enabled !== true}>
              抽選する
            </button>
            {value.enabled !== true && <p>抽選はオフになっています。</p>}
          </div>
        </div>
      </div>

      <h2>抽選記録</h2>
      {lotteries.length === 0 && <p>まだ抽選していません。</p>}
      {lotteries.map((lottery) => (
        <details key={lottery.id} className="lottery">
          <summary>
            {formatDateTime(lottery.executedAt)} {lottery.mode === 'bulk' ? 'まとめ抽選' : '配信中の抽選'}（実行:{' '}
            {lottery.executedBy}）当選: {lottery.entries.filter((e) => e.won).map((e) => nameOf(e.applicationId)).join('、')}
          </summary>
          <table className="list">
            <thead>
              <tr>
                <th>提督名</th>
                <th>当たりやすさ</th>
                <th>結果</th>
              </tr>
            </thead>
            <tbody>
              {lottery.entries.map((entry) => (
                <tr key={entry.applicationId}>
                  <td>
                    <Link to={`/applications/${entry.applicationId}`}>{nameOf(entry.applicationId)}</Link>
                  </td>
                  <td>{entry.weight}</td>
                  <td>{entry.won ? '当選' : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {/* 同じ種と対象者なら同じ結果になる。後から公平性を確かめるために記録している（仕様 6.4） */}
          <p className="meta">乱数の種: {lottery.seed}</p>
        </details>
      ))}
    </section>
  )
}

export default LotteryPage
