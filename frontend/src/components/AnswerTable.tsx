import type { Answers } from '../api/types'
import { ANSWER_LABELS } from '../labels'

type Props = {
  answers: Answers
  /** 前回の回答。渡すと「前回」の列が増え、変わった項目に金の点がつく（仕様 5.5 の比較） */
  previous?: Answers | null
  /** 表に出さない項目（URLのように別の場所で出すもの） */
  hidden?: string[]
}

/** フォームの回答を「項目 | 今回 | 前回」の表で表示する（共通の部品。詳細画面と配信用画面で使う） */
function AnswerTable({ answers, previous, hidden = [] }: Props) {
  // 設定にある順で並べ、そのあとに設定にない項目（フォームに後から足した項目など）を並べる
  const keys = [
    ...Object.keys(ANSWER_LABELS).filter((key) => key in answers),
    ...Object.keys(answers).filter((key) => !(key in ANSWER_LABELS)),
  ].filter((key) => !hidden.includes(key))

  return (
    <div className="table-scroll">
      <table className="answers">
        <thead>
          <tr>
            <th scope="col">項目</th>
            <th scope="col">{previous ? '今回' : '回答'}</th>
            {previous && <th scope="col">前回</th>}
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => {
            const current = String(answers[key] ?? '')
            const before = previous ? String(previous[key] ?? '') : ''
            const changed = previous != null && current !== before
            return (
              <tr key={key}>
                <th scope="row">{ANSWER_LABELS[key] ?? key}</th>
                <td className="current">
                  <span className="answer-value">
                    {/* 色だけに頼らないよう、読み上げ用の名前（aria-label）も付ける */}
                    {changed && <span className="changed-dot" role="img" aria-label="前回から変更" />}
                    <span className="pre">{current}</span>
                  </span>
                </td>
                {previous && <td className="previous pre">{before}</td>}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default AnswerTable
