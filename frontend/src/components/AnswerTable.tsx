import type { Answers } from '../api/types'
import { ANSWER_LABELS } from '../labels'

type Props = {
  answers: Answers
  /** 前回の回答。渡すと「前回」の列が増え、変わった項目に印がつく（仕様 5.5 の比較） */
  previous?: Answers | null
  /** 表に出さない項目（URLのように別の場所で出すもの） */
  hidden?: string[]
}

/** フォームの回答を「質問 | 回答」の表で表示する */
function AnswerTable({ answers, previous, hidden = [] }: Props) {
  // 設定にある順で並べ、そのあとに設定にない項目（フォームに後から足した項目など）を並べる
  const keys = [
    ...Object.keys(ANSWER_LABELS).filter((key) => key in answers),
    ...Object.keys(answers).filter((key) => !(key in ANSWER_LABELS)),
  ].filter((key) => !hidden.includes(key))

  return (
    <table className="answers">
      <thead>
        <tr>
          <th>項目</th>
          <th>回答</th>
          {previous && <th>前回</th>}
        </tr>
      </thead>
      <tbody>
        {keys.map((key) => {
          const current = String(answers[key] ?? '')
          const before = previous ? String(previous[key] ?? '') : ''
          const changed = previous != null && current !== before
          return (
            <tr key={key} className={changed ? 'changed' : undefined}>
              <th>{ANSWER_LABELS[key] ?? key}</th>
              <td>{current}</td>
              {previous && <td>{before}</td>}
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

export default AnswerTable
