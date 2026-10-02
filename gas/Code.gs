/**
 * Googleフォームの送信時トリガー。回答を応募受付APIへ送る。
 *
 * スクリプトプロパティに次を設定する:
 *   API_URL   例: https://api.example.com/intake/applications
 *   FORM_KEY  応募受付API用の秘密キー
 *   FORM_VERSION  例: 2026-10
 *
 * 送信に失敗した回答は、回答シートの「連携状態」列に記録し、管理画面から再取り込みする。
 */

// フォームの質問タイトル → APIの項目コード
var ITEM_CODES = {
  'XのID（@から始まるもの）': 'xId',
  '提督名（配信で呼ぶ名前）': 'admiralName',
  '配信での名前の出し方': 'nameDisplay',
  '艦隊データ（制空権シミュレータの共有URL）': 'simulatorUrl',
  '着任時期': 'startedAt',
  'これまでの実働期間': 'activePeriod',
  '月の課金額': 'monthlySpending',
  '1日のプレイ時間': 'dailyPlayTime',
  '戦果への取り組み': 'rankingEffort',
  '戦果への取り組み（その他）': 'rankingEffortOther',
  '縛りの有無': 'hasRestrictions',
  '縛りの内容': 'restrictions',
  '目標': 'goal',
  '分析してほしい目的': 'purpose',
  '相談内容・コメント': 'comment',
};

function onFormSubmit(e) {
  var props = PropertiesService.getScriptProperties();
  var response = e.response;
  var answers = {};
  response.getItemResponses().forEach(function (itemResponse) {
    var code = ITEM_CODES[itemResponse.getItem().getTitle()];
    if (code) {
      answers[code] = itemResponse.getResponse();
    }
  });

  var payload = {
    submissionId: response.getId(),
    submittedAt: response.getTimestamp().toISOString(),
    formVersion: props.getProperty('FORM_VERSION'),
    answers: answers,
  };

  var result = UrlFetchApp.fetch(props.getProperty('API_URL'), {
    method: 'post',
    contentType: 'application/json',
    headers: { 'X-Form-Key': props.getProperty('FORM_KEY') },
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
  });

  var code = result.getResponseCode();
  if (code !== 201) {
    console.error('応募の連携に失敗しました: ' + code + ' ' + result.getContentText());
    markUnsynced_(response.getId(), code);
  }
}

function markUnsynced_(submissionId, statusCode) {
  // 回答シートの「連携状態」列への記録は段階1で実装する
  console.warn('未連携: ' + submissionId + ' (' + statusCode + ')');
}
