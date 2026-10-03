// 画面に出す日本語の表示名。
// APIではステータスや項目を英語のコード（例: "pending"）でやりとりし、画面に出すときだけ日本語に直す。
// TODO(段階7): ステータスや項目は設定データから読み込む（仕様 10章）。試作では固定の表にしている

import type { FlagType } from './api/types'

/** ステータスのコード → 表示名 */
export const STATUS_LABELS: Record<string, string> = {
  pending: '未着手',
  scheduled: '分析予定',
  analyzing: '分析中',
  done: '分析済み',
  skipped: '見送り',
  lost: '落選',
}

/**
 * 今のステータスから変更できるステータス（backend の ApplicationStatus.canChangeTo と同じ表）。
 * 画面では変えられないものを選べないようにするだけで、最終的な確認はバックエンドが行う。
 */
export const NEXT_STATUSES: Record<string, string[]> = {
  pending: ['scheduled', 'analyzing', 'skipped', 'lost'],
  scheduled: ['pending', 'analyzing', 'skipped'],
  analyzing: ['pending', 'scheduled', 'done'],
  done: ['analyzing'],
  skipped: ['pending'],
  lost: ['pending'],
}

export const FLAG_LABELS: Record<FlagType, string> = {
  duplicate: '重複',
  reapply: '再応募',
  ineligible: '条件外',
}

/** フォームの項目コード → 質問の表示名（gas/Code.gs の ITEM_CODES と同じ並び） */
export const ANSWER_LABELS: Record<string, string> = {
  xId: 'XのID',
  admiralName: '提督名',
  nameDisplay: '名前の出し方',
  simulatorUrl: '艦隊データ',
  startedAt: '着任時期',
  activePeriod: 'これまでの実働期間',
  monthlySpending: '月の課金額',
  dailyPlayTime: '1日のプレイ時間',
  rankingEffort: '戦果への取り組み',
  rankingEffortOther: '戦果への取り組み（その他）',
  hasRestrictions: '縛りの有無',
  restrictions: '縛りの内容',
  goal: '目標',
  purpose: '分析してほしい目的',
  comment: '相談内容・コメント',
}

/** 分析してほしい目的の選択肢（一覧の絞り込みに使う。仕様 4.2 の項目14） */
export const PURPOSES = ['イベント', '通常海域', '演習', '全体的な育成方針', 'その他']

/** 日時を「2026/10/02 12:34」の形にする */
export function formatDateTime(value: string): string {
  return new Date(value).toLocaleString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
