import { useCallback, useEffect, useState, type DragEvent, type FormEvent, type KeyboardEvent } from 'react'
import { Link } from 'react-router'
import { fetchAllApplications } from '../api/applications'
import { api } from '../api/client'
import { errorMessage, isConflict, type Application, type FlagType, type SkipReason } from '../api/types'
import { useConfirm } from '../components/useConfirm'
import FlagBadges from '../components/FlagBadges'
import Notice from '../components/Notice'
import {
  BULK_LIMIT,
  FLAG_LABELS,
  NEXT_STATUSES,
  PURPOSES,
  RANKING_EFFORTS,
  STATUS_LABELS,
  formatShortDate,
  formatShortDateTime,
  hasBlockingFlag,
} from '../labels'

/**
 * ステータスで絞り込むタブ。件数を大きなカードで見せる代わりにタブに付け、押すとその件数の応募に絞り込める
 * （design-plan.md の4章）。
 */
type Tab = 'open' | 'review' | 'done' | 'closed' | 'all'

const OPEN_STATUSES = ['analyzing', 'scheduled', 'pending']

const TABS: { key: Tab; label: string; warn?: boolean; match: (item: Application) => boolean }[] = [
  { key: 'open', label: 'これから分析する人', match: (item) => OPEN_STATUSES.includes(item.status) },
  {
    key: 'review',
    label: '確認が必要（重複・条件外）',
    warn: true,
    match: (item) => OPEN_STATUSES.includes(item.status) && hasBlockingFlag(item),
  },
  { key: 'done', label: '分析済み', match: (item) => item.status === 'done' },
  { key: 'closed', label: '見送り・落選', match: (item) => item.status === 'skipped' || item.status === 'lost' },
  { key: 'all', label: 'すべて', match: () => true },
]

/** ステータスごとのまとまりを、この順に並べる（仕様 5.4 の「次に分析する順」のあとに、終わったもの） */
const GROUP_ORDER = ['analyzing', 'scheduled', 'pending', 'done', 'skipped', 'lost']

/** 並べ替え（ドラッグ）ができるステータス */
const MOVABLE = ['scheduled', 'pending']

/**
 * まとめての変更で選べる「変更後」。見送りは理由ごとに分けて、1つ選ぶだけで済むようにする。
 * value は「ステータス:理由」の形。button はボタンの文言（何が起きるかがわかる言葉にする。handoff.md の7）。
 */
const BULK_OPTIONS: { value: string; status: string; reason?: SkipReason; label: string; button: string }[] = [
  { value: 'skipped:ineligible', status: 'skipped', reason: 'ineligible', label: '見送り（条件外）', button: 'を見送りにする' },
  { value: 'skipped:resubmitted', status: 'skipped', reason: 'resubmitted', label: '見送り（重複・再送のため）', button: 'を見送りにする' },
  { value: 'skipped:withdrawn', status: 'skipped', reason: 'withdrawn', label: '見送り（本人の取り下げ）', button: 'を見送りにする' },
  { value: 'skipped:other', status: 'skipped', reason: 'other', label: '見送り（その他）', button: 'を見送りにする' },
  { value: 'scheduled', status: 'scheduled', label: '分析予定', button: 'を分析予定にする' },
  { value: 'pending', status: 'pending', label: '未着手', button: 'を未着手にする' },
]

/** 失敗したときに画面の上に出す内容 */
type Failure = { title: string; message: string; conflict: boolean }

/**
 * 応募一覧（仕様 7.1）。「次に分析する順」に、ステータスごとのまとまり（分析中・分析予定・未着手）で区切って並べる。
 * 通し番号は付けない（「次の人へ」は印のある未着手の人を飛ばすので、番号と実際の順番がずれるため）。
 * 代わりに、次に分析する人に「次に分析」と付ける。
 * 行の左のつまみをドラッグすると、同じステータスの中で並べ替えられる。
 */
function ApplicationListPage() {
  // useState: 画面が覚えておく値（状態）。値を変えると、その値を使っている部分が描き直される
  const [tab, setTab] = useState<Tab>('open')
  const [purpose, setPurpose] = useState('')
  const [rankingEffort, setRankingEffort] = useState('')
  const [flag, setFlag] = useState('')
  // 検索欄に入力中の文字（keywordInput）と、実際に検索に使う文字（keyword）を分ける。
  // 1文字打つたびに検索し直すと重いので、Enter キーで keyword に移す
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [items, setItems] = useState<Application[]>([])
  // 「次に分析」を付ける応募のID（いなければ null）
  const [nextId, setNextId] = useState<string | null>(null)
  const [lotteryEnabled, setLotteryEnabled] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [message, setMessage] = useState('')
  // まとめて変更するために選んだ応募のID。Set は「同じ値を2回入れない入れ物」
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkOption, setBulkOption] = useState(BULK_OPTIONS[0].value)
  // ドラッグ中の応募と、落とす場所（どの行の上か下か）
  const [dragId, setDragId] = useState<string | null>(null)
  const [dropTarget, setDropTarget] = useState<{ id: string; before: boolean } | null>(null)
  const [dialog, confirm] = useConfirm()

  const filtered = Boolean(purpose || rankingEffort || flag || keyword)

  // useCallback: 絞り込み条件が変わったときだけ、新しい load 関数を作り直す（下の useEffect が反応するため）
  const load = useCallback(async () => {
    const query = {
      purpose: purpose || undefined,
      rankingEffort: rankingEffort || undefined,
      flag: (flag || undefined) as FlagType | undefined,
      q: keyword || undefined,
      order: 'queue' as const,
    }
    // Promise.all: 複数の読み込みを同時に始めて、全部が終わるのを待つ（1つずつ待つより速い）。
    // 絞り込んでいるときは、次に分析する人が絞り込みで隠れていることがあるので、絞り込みなしの一覧も読む
    const [list, unfiltered, settings] = await Promise.all([
      fetchAllApplications(query),
      filtered ? fetchAllApplications({ status: OPEN_STATUSES, order: 'queue' }) : null,
      api.GET('/settings/{kind}', { params: { path: { kind: 'lottery' } } }),
    ])
    // 設定が読めなかったときは、抽選を使うかどうかがわからないので null にする（「次に分析」を付けない）
    const lotteryEnabled = settings.data ? settings.data.value.enabled === true : null
    return { list, unfiltered, lotteryEnabled }
  }, [purpose, rankingEffort, flag, keyword, filtered])

  /** 読み込んだ結果を画面に反映する */
  const applyResult = useCallback((result: Awaited<ReturnType<typeof load>>) => {
    setLoaded(true)
    if (result.list.error) {
      setFailure({ title: '応募を読み込めませんでした', message: errorMessage(result.list.error), conflict: false })
      return
    }
    setFailure(null)
    setItems(result.list.items)
    setLotteryEnabled(result.lotteryEnabled === true)
    setNextId(
      result.lotteryEnabled === null ? null : findNext(result.unfiltered?.items ?? result.list.items, result.lotteryEnabled),
    )
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

  async function reload() {
    setMessage('')
    applyResult(await load())
  }

  function search(event: FormEvent) {
    // フォームの送信でページが読み込み直されないようにする（ブラウザの標準の動きを止める）
    event.preventDefault()
    setKeyword(keywordInput.trim())
  }

  /** 失敗を画面の上に出す。ほかの人が先に変更していた（409）ときは「最新の状態を読み込む」ボタンも出す */
  function fail(title: string, error: unknown) {
    setMessage('')
    setFailure({ title, message: errorMessage(error), conflict: isConflict(error) })
  }

  /**
   * target を、同じステータスの中で after の後ろへ動かす（after が null なら先頭へ）。
   * APIは「どの応募の後ろに入れるか」で指定する。
   */
  async function moveAfter(target: Application, after: string | null) {
    const { error } = await api.PUT('/applications/{applicationId}/position', {
      params: { path: { applicationId: target.id } },
      body: { after },
    })
    if (error) {
      fail(`${target.admiralName} 提督の順番を変えられませんでした`, error)
      return
    }
    setMessage('')
    applyResult(await load())
  }

  /** 同じステータスの中の並び（target を除く） */
  function groupWithout(target: Application) {
    return items.filter((item) => item.status === target.status && item.id !== target.id)
  }

  /** キーボードで並べ替える: つまみにキーボードを当てて ↑ / ↓ キー */
  function onHandleKey(event: KeyboardEvent, target: Application) {
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return
    event.preventDefault()
    const group = items.filter((item) => item.status === target.status)
    const index = group.findIndex((item) => item.id === target.id)
    if (event.key === 'ArrowUp') {
      if (index === 0) return
      // 1つ上に行く = 2つ上の人の後ろに入る（2つ上がいなければ先頭）
      void moveAfter(target, index >= 2 ? group[index - 2].id : null)
    } else {
      if (index === group.length - 1) return
      void moveAfter(target, group[index + 1].id)
    }
  }

  // ───── ドラッグで並べ替える（ブラウザに備わっている HTML のドラッグ＆ドロップを使う） ─────

  function onDragStart(event: DragEvent, target: Application) {
    setDragId(target.id)
    // Firefox はドラッグで運ぶデータを何か入れないとドラッグが始まらないため、応募IDを入れておく
    event.dataTransfer.setData('text/plain', target.id)
    event.dataTransfer.effectAllowed = 'move'
  }

  function onDragOver(event: DragEvent, over: Application) {
    const dragged = items.find((item) => item.id === dragId)
    // 同じステータスの中でだけ入れ替えられる
    if (!dragged || dragged.status !== over.status || dragged.id === over.id) {
      setDropTarget(null)
      return
    }
    // preventDefault すると「ここに落とせる」という意味になる
    event.preventDefault()
    // 行の上半分なら「その行の前」、下半分なら「その行の後ろ」に入れる
    const rect = event.currentTarget.getBoundingClientRect()
    const before = event.clientY < rect.top + rect.height / 2
    if (dropTarget?.id !== over.id || dropTarget.before !== before) {
      setDropTarget({ id: over.id, before })
    }
  }

  function onDrop(event: DragEvent) {
    event.preventDefault()
    const dragged = items.find((item) => item.id === dragId)
    const target = dropTarget
    endDrag()
    if (!dragged || !target) return
    const group = groupWithout(dragged)
    const index = group.findIndex((item) => item.id === target.id)
    // 「前に入れる」は「1つ前の人の後ろに入れる」と同じ（1つ前がいなければ先頭）
    const after = target.before ? (index > 0 ? group[index - 1].id : null) : target.id
    // 動かしても順番が変わらないときは、何もしない
    const current = items.filter((item) => item.status === dragged.status)
    const currentIndex = current.findIndex((item) => item.id === dragged.id)
    const currentAfter = currentIndex > 0 ? current[currentIndex - 1].id : null
    if (after === currentAfter) return
    void moveAfter(dragged, after)
  }

  function endDrag() {
    setDragId(null)
    setDropTarget(null)
  }

  // ───── まとめての変更 ─────

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

  const option = BULK_OPTIONS.find((o) => o.value === bulkOption) ?? BULK_OPTIONS[0]
  const targets = items.filter((item) => selected.has(item.id))
  // 今のステータスから変えられない応募（例: 分析済みを未着手に）。最終的な確認はバックエンドが行う
  const blocked = targets.filter(
    (item) => item.status !== option.status && !(NEXT_STATUSES[item.status] ?? []).includes(option.status),
  )
  const bulkButton = `${targets.length}件${option.button}`

  async function changeSelected() {
    const ok = await confirm({
      title: `${targets.length}件を「${option.label}」にしますか？`,
      body: <p className="readable">{targets.map((item) => `${item.admiralName} 提督`).join('、')}</p>,
      confirmLabel: bulkButton,
    })
    if (!ok) return
    const { error } = await api.POST('/applications/bulk-status', {
      body: {
        // 画面で読み込んだときの版も送る。ほかの人が先に変えていたら、どれも変えずにエラーになる
        items: targets.map((item) => ({ id: item.id, version: item.version })),
        status: option.status,
        skipReason: option.reason,
      },
    })
    if (error) {
      fail('選んだ応募を変更できませんでした', error)
      return
    }
    const done = `${targets.length}件を「${option.label}」にしました`
    applyResult(await load())
    setMessage(done)
  }

  // ───── 表示 ─────

  const currentTab = TABS.find((t) => t.key === tab) ?? TABS[0]
  const visible = items.filter(currentTab.match)
  // 並べ替えは、絞り込みなしで「これから分析する人」か「すべて」を見ているときだけ。
  // 絞り込んでいると、見えていない応募をまたいで動かすことになるため
  const canReorder = !filtered && (tab === 'open' || tab === 'all')
  const groups = GROUP_ORDER.map((status) => ({
    status,
    rows: sortGroup(
      status,
      visible.filter((item) => item.status === status),
    ),
  })).filter((group) => group.rows.length > 0)
  const allVisibleSelected = visible.length > 0 && visible.every((item) => selected.has(item.id))

  return (
    <>
      {dialog}
      <div>
        <h1 className="page-title">応募一覧</h1>
        <p className="page-lead">
          次に分析する順に並んでいます。順番を変えるときは、行の左のつまみをドラッグしてください（つまみを選んで ↑ ↓
          キーでも動かせます）。同じステータスの中で入れ替わります。
        </p>
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

      {/*
        ステータスで絞り込むボタンの並び。role="group" と aria-label で「ひとまとまりのボタン」として読み上げさせ、
        aria-pressed で、どれが押されている（選ばれている）かを伝える
      */}
      <div role="group" aria-label="ステータスで絞り込む" className="status-tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            aria-pressed={tab === t.key}
            className={t.warn ? 'status-tab warn' : 'status-tab'}
            onClick={() => {
              setTab(t.key)
              setSelected(new Set())
            }}
          >
            {t.label}
            <span className="count">{items.filter(t.match).length}</span>
          </button>
        ))}
      </div>

      <form className="filters" aria-label="さらに絞り込む" onSubmit={search}>
        <div className="field wide">
          <label htmlFor="q">提督名・XのIDで探す（Enter キーで検索）</label>
          <input
            id="q"
            type="search"
            placeholder="例：朝霧、demo_teitoku01"
            value={keywordInput}
            onChange={(e) => {
              setKeywordInput(e.target.value)
              // 検索欄を空にしたら（× ボタンを含む）、すぐに検索をやめる
              if (e.target.value === '') setKeyword('')
            }}
          />
        </div>
        <div className="field">
          <label htmlFor="purpose">分析してほしい目的</label>
          <select id="purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
            <option value="">すべて</option>
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ranking">戦果への取り組み</label>
          <select id="ranking" value={rankingEffort} onChange={(e) => setRankingEffort(e.target.value)}>
            <option value="">すべて</option>
            {RANKING_EFFORTS.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="flag">印</label>
          <select id="flag" value={flag} onChange={(e) => setFlag(e.target.value)}>
            <option value="">すべて</option>
            {Object.entries(FLAG_LABELS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </div>
      </form>

      {/* まとめての変更（1件以上選んだときだけ出す） */}
      {selected.size > 0 && (
        <div className="bulk-bar">
          <span className="bulk-warning">{selected.size}件を選んでいます</span>
          <label htmlFor="bulk">変更後のステータス</label>
          <select id="bulk" value={bulkOption} onChange={(e) => setBulkOption(e.target.value)}>
            {BULK_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="button"
            disabled={selected.size > BULK_LIMIT || blocked.length > 0}
            onClick={() => void changeSelected()}
          >
            {bulkButton}
          </button>
          <button type="button" className="button ghost" onClick={() => setSelected(new Set())}>
            選ぶのをやめる
          </button>
          {selected.size > BULK_LIMIT && (
            <span className="bulk-warning">一度に変えられるのは{BULK_LIMIT}件までです。選ぶ数を減らしてください。</span>
          )}
          {blocked.length > 0 && (
            <span className="bulk-warning">
              {blocked.map((item) => `${item.admiralName} 提督（${STATUS_LABELS[item.status]}）`).join('、')}は「
              {option.label}」にできません。選ぶのをやめるか、変更後のステータスを変えてください。
            </span>
          )}
        </div>
      )}

      <div className="table-card">
        <table className="applications">
          <thead>
            <tr>
              <th scope="col">
                <label className="check-cell">
                  <input
                    type="checkbox"
                    aria-label="表示しているすべての応募を選ぶ"
                    checked={allVisibleSelected}
                    onChange={() =>
                      setSelected(allVisibleSelected ? new Set() : new Set(visible.map((item) => item.id)))
                    }
                  />
                </label>
              </th>
              <th scope="col">
                <span className="visually-hidden">順番を動かす</span>
              </th>
              <th scope="col">提督名</th>
              <th scope="col">XのID</th>
              <th scope="col">印</th>
              <th scope="col">目的</th>
              <th scope="col">戦果</th>
              <th scope="col">縛り</th>
              <th scope="col">受付</th>
              <th scope="col">配信日</th>
            </tr>
          </thead>
          {groups.map((group) => (
            <tbody key={group.status}>
              <tr className="group-heading">
                <th scope="colgroup" colSpan={10}>
                  <span className="group-title">{STATUS_LABELS[group.status]}</span>
                  <span className="group-count num">{group.rows.length}件</span>
                  <span className="group-note">{groupNote(group.status, lotteryEnabled)}</span>
                </th>
              </tr>
              {group.rows.map((item) => {
                const draggable = canReorder && MOVABLE.includes(item.status)
                const classes = ['item']
                if (selected.has(item.id)) classes.push('selected')
                if (dragId === item.id) classes.push('dragging')
                if (dropTarget?.id === item.id) classes.push(dropTarget.before ? 'drop-before' : 'drop-after')
                return (
                  <tr
                    key={item.id}
                    className={classes.join(' ')}
                    onDragOver={(e) => onDragOver(e, item)}
                    onDrop={onDrop}
                  >
                    <td>
                      {/* チェックボックスは小さいので、まわりの label まで押せる範囲にして44px以上にする */}
                      <label className="check-cell">
                        <input
                          type="checkbox"
                          aria-label={`${item.admiralName} 提督を選ぶ`}
                          checked={selected.has(item.id)}
                          onChange={() => toggle(item.id)}
                        />
                      </label>
                    </td>
                    <td>
                      {draggable && (
                        <button
                          type="button"
                          className="drag-handle"
                          draggable
                          aria-label={`${item.admiralName} 提督の順番を動かす（↑ ↓ キー）`}
                          onDragStart={(e) => onDragStart(e, item)}
                          onDragEnd={endDrag}
                          onKeyDown={(e) => onHandleKey(e, item)}
                        >
                          <GripIcon />
                        </button>
                      )}
                    </td>
                    <td className="nowrap">
                      <Link className="applicant-name" to={`/applications/${item.id}`}>
                        {item.admiralName}
                      </Link>
                      {item.anonymous && <span className="tag">匿名希望</span>}
                      {item.id === nextId && <span className="tag-next">次に分析</span>}
                    </td>
                    <td className="sub">@{item.xId}</td>
                    <td className="nowrap">
                      <FlagBadges flags={item.flags} />
                    </td>
                    <td className="nowrap">{String(item.answers.purpose ?? '')}</td>
                    <td className="nowrap">{String(item.answers.rankingEffort ?? '')}</td>
                    <td>{String(item.answers.hasRestrictions ?? '')}</td>
                    <td className="sub nowrap num">{formatShortDateTime(item.receivedAt)}</td>
                    <td className="sub nowrap num">{item.streamDate ? formatShortDate(item.streamDate) : '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          ))}
        </table>
        {loaded && visible.length === 0 && !failure && (
          <p className="empty-note">
            {filtered ? '条件に合う応募はありません。絞り込みを変えてみてください。' : 'この中に応募はありません。'}
          </p>
        )}
      </div>

      <dl className="legend">
        <div>
          <dt>重複</dt>
          <dd>同じ人の、まだ終わっていない応募がほかにある</dd>
        </div>
        <div>
          <dt>条件外</dt>
          <dd>設定した条件に当たる</dd>
        </div>
        <div>
          <dt className="info">再応募</dt>
          <dd>前にも応募したことがある</dd>
        </div>
      </dl>
    </>
  )
}

/**
 * 次に分析する人（「次の人へ」で分析中になる人）を探す。backend の ApplicationService.nextInQueue と同じ決め方（仕様 7.3）:
 * 1. 「分析予定」の先頭（印があってもそのまま選ぶ）
 * 2. いなければ、抽選を使わない設定のときだけ、「未着手」のうち重複・条件外の印がない先頭
 * items は「次に分析する順」に並んでいること。
 */
function findNext(items: Application[], lotteryEnabled: boolean): string | null {
  const scheduled = items.find((item) => item.status === 'scheduled')
  if (scheduled) return scheduled.id
  if (lotteryEnabled) return null
  return items.find((item) => item.status === 'pending' && !hasBlockingFlag(item))?.id ?? null
}

/** まとまりの中の並び。これから分析する人はAPIの順（次に分析する順）、終わった応募は新しく変わった順 */
function sortGroup(status: string, rows: Application[]) {
  if (OPEN_STATUSES.includes(status)) return rows
  return [...rows].sort((a, b) => Date.parse(b.statusChangedAt) - Date.parse(a.statusChangedAt))
}

/** まとまりの見出しの横に出す説明 */
function groupNote(status: string, lotteryEnabled: boolean) {
  switch (status) {
    case 'analyzing':
      return '配信用画面に映っている人です'
    case 'scheduled':
      return '分析することが決まった人。上から順に分析します'
    case 'pending':
      return lotteryEnabled
        ? '抽選を使う設定なので、この中から抽選で選びます'
        : '重複・条件外の印がない人を、上から順に分析します'
    case 'done':
      return '新しく分析した順'
    default:
      return '新しく変わった順'
  }
}

/** 並べ替えのつまみの絵（点が6つ） */
function GripIcon() {
  return (
    <svg width="14" height="20" viewBox="0 0 14 20" fill="currentColor" aria-hidden="true">
      <circle cx="4" cy="4" r="1.6" />
      <circle cx="10" cy="4" r="1.6" />
      <circle cx="4" cy="10" r="1.6" />
      <circle cx="10" cy="10" r="1.6" />
      <circle cx="4" cy="16" r="1.6" />
      <circle cx="10" cy="16" r="1.6" />
    </svg>
  )
}

export default ApplicationListPage
