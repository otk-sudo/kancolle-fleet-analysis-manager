import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { errorMessage, type StreamApplicant } from '../api/types'
import AnswerTable from '../components/AnswerTable'

/** 配信用画面を自動で読み直す間隔（ミリ秒）。管理画面で「分析中」にした人が、数秒で切り替わる */
const REFRESH_INTERVAL_MS = 5000

/**
 * 配信用画面（仕様 7.2）。いま「分析中」の1人を大きく表示する。
 * XのIDと課金額はAPIの時点で含まれないので、この画面を配信に映しても漏れない。
 */
function StreamPage() {
  // undefined = 読み込み中、null = 分析中の人がいない
  const [current, setCurrent] = useState<StreamApplicant | null | undefined>(undefined)
  const [drawing, setDrawing] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    const { data, error } = await api.GET('/stream/current')
    if (error || !data) {
      setError(errorMessage(error))
      return
    }
    setError('')
    setCurrent(data.current)
  }, [])

  useEffect(() => {
    // APIからの読み込みは「外部との同期」なので effect で行う。setState は通信が終わった後（非同期）に呼ばれるため、
    // lint が心配する「描画の連鎖」は起きない
    // oxlint-disable-next-line react/set-state-in-effect
    void load()
    // setInterval: 一定の間隔で関数を呼び続ける。画面を閉じたら止める（return の関数が後片付け）
    const timer = setInterval(() => void load(), REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [load])

  /** 配信中の抽選: 「未着手」から1人を選び、その人を「分析中」にする（仕様 6.2） */
  async function drawLive() {
    setDrawing(true)
    const { error } = await api.POST('/lotteries', { body: { mode: 'live' } })
    // 結果がすぐ出ると味気ないので、少しだけ「抽選中…」を見せる
    await new Promise((resolve) => setTimeout(resolve, 1500))
    setDrawing(false)
    if (error) {
      setError(errorMessage(error))
      return
    }
    await load()
  }

  return (
    <div className="stream">
      {drawing ? (
        <p className="stream-drawing">抽選中…</p>
      ) : current === undefined ? (
        <p>読み込み中…</p>
      ) : current === null ? (
        <p className="stream-empty">次の提督を準備中です</p>
      ) : (
        <>
          <p className="stream-label">ただいま分析中</p>
          <h1 className="stream-name">{current.displayName} 提督</h1>
          <AnswerTable answers={current.answers} previous={current.previous} hidden={['simulatorUrl']} />
          {current.previous && <p className="stream-note">黄色の行は前回の応募から変わった項目です</p>}
        </>
      )}
      {error && <p className="error">{error}</p>}
      {/* 配信者さんが操作するボタン。配信ソフトで映す範囲から外せるよう、画面の下に置いている */}
      <div className="stream-controls">
        <button type="button" onClick={() => void drawLive()} disabled={drawing}>
          配信中の抽選（1人）
        </button>
        {current && (
          <a href={current.simulatorUrl} target="_blank" rel="noreferrer">
            艦隊データを開く ↗
          </a>
        )}
      </div>
    </div>
  )
}

export default StreamPage
