// 画面に出す日本語の表示名。
// APIではステータスや項目を英語のコード（例: "pending"）でやりとりし、画面に出すときだけ日本語に直す。
// TODO(段階7): ステータスや項目は設定データから読み込む（仕様 10章）。試作では固定の表にしている

import type { Application, FlagType, SkipReason } from './api/types'

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
 * 今のステータスから「手で」変更できるステータス（仕様 5.1 の表。backend の ApplicationStatus.canChangeTo と同じ）。
 * 画面では変えられないものを選べないようにするだけで、最終的な確認はバックエンドが行う。
 * 「落選」は抽選でだけ付くので、手では付けることも外すこともできない。
 */
export const NEXT_STATUSES: Record<string, string[]> = {
  pending: ['scheduled', 'analyzing', 'skipped'],
  scheduled: ['pending', 'analyzing', 'skipped'],
  analyzing: ['pending', 'scheduled', 'done'],
  done: ['analyzing'],
  skipped: ['pending'],
  lost: [],
}

/** まとめて変更できるステータス（分析中・分析済みは1人ずつ確かめて変えるので、まとめては変えない） */
export const BULK_STATUSES = ['pending', 'scheduled', 'skipped']

/** まとめて変えられる最大の件数（backend の ApplicationService.BULK_LIMIT と同じ） */
export const BULK_LIMIT = 25

/** 見送りの理由（仕様 5.6） */
export const SKIP_REASON_LABELS: Record<SkipReason, string> = {
  resubmitted: '重複（再送のため）',
  ineligible: '条件外',
  withdrawn: '本人の取り下げ',
  other: 'その他（メモに書く）',
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

/** 戦果への取り組みの選択肢（一覧の絞り込みに使う。仕様 4.2 の項目10） */
export const RANKING_EFFORTS = ['戦果やらない', 'クォータリー3群', '継続3群', '継続2群', '継続1群', '継続聯合', 'その他']

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

/** 日時を「09/29 13:15」の形にする（一覧のように狭い場所で使う） */
export function formatShortDateTime(value: string): string {
  return new Date(value).toLocaleString('ja-JP', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 配信日（"2026-10-03" のような日付だけの文字列）を「10/03」の形にする。
 * new Date("2026-10-03") は世界標準時の0時として読まれ、国によっては前の日になるので、文字列のまま切り出す
 */
export function formatShortDate(value: string): string {
  const [, month, day] = value.split('-')
  return `${month}/${day}`
}

/** 日付を「2026年6月14日」の形にする */
export function formatLongDate(value: string): string {
  return new Date(value).toLocaleDateString('ja-JP', { year: 'numeric', month: 'long', day: 'numeric' })
}

/**
 * 確認が必要な印（重複・条件外）があるか。backend の Application.hasBlockingFlag と同じ。
 * この印がある「未着手」の人は、「次の人へ」で飛ばされ、抽選でもはじめは対象から外れる
 */
export function hasBlockingFlag(application: Application): boolean {
  return application.flags.some((flag) => flag.type === 'duplicate' || flag.type === 'ineligible')
}
