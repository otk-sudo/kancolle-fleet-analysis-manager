/**
 * Googleフォームと応募管理ツールをつなぐ Apps Script（仕様 3章）。
 * フォームに「結びついた」スクリプトとして使う（フォームの編集画面の「︙」→「スクリプト エディタ」で開く）。
 *
 * やること:
 *   1. フォームに回答が送られたら（送信時トリガー）、回答を応募受付API（POST /intake/applications）へ送る
 *   2. 送れなかった回答は「未連携」として覚えておく（スクリプト プロパティに1件ずつ保存）
 *   3. フォームの編集画面のメニュー「応募ツール連携」から、未連携の確認と再送ができる
 *
 * スクリプト プロパティ（「プロジェクトの設定」→「スクリプト プロパティ」）に次を設定する:
 *   API_URL       例: https://api.example.com/intake/applications
 *   FORM_KEY      応募受付API用の秘密キー（このリポジトリには絶対に書かない）
 *   FORM_VERSION  例: 2026-10（フォームの質問を変えたら上げる）
 *
 * 同じ回答を何度送っても、ツール側は回答ID（submissionId）で見分けて1件だけ登録する。
 * なので、再送で二重に登録されることはない。
 */

// フォームの質問タイトル → APIの項目コード。
// フォームの質問タイトルを変えたら、ここも同じに直す（一致しない質問は送られない）
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

// 未連携の回答を覚えておく、スクリプト プロパティのキーの頭につける文字
var UNSYNCED_PREFIX = 'UNSYNCED_';

/** フォーム送信時トリガー。トリガーの設定で、この関数と「フォーム送信時」を選ぶ。 */
function onFormSubmit(e) {
  var result = send_(e.response);
  if (!result.ok) {
    console.error('応募の連携に失敗しました: ' + e.response.getId() + ' ' + result.reason);
    markUnsynced_(e.response, result.reason);
  }
}

/** フォームの編集画面を開いたときに自動で呼ばれ、メニューを追加する。 */
function onOpen() {
  FormApp.getUi()
    .createMenu('応募ツール連携')
    .addItem('未連携を確認', 'showUnsynced')
    .addItem('未連携を再送', 'resendUnsynced')
    .addToUi();
}

/** メニュー「未連携を確認」: 送れなかった回答の一覧を表示する。 */
function showUnsynced() {
  var unsynced = loadUnsynced_();
  var ids = Object.keys(unsynced);
  if (ids.length === 0) {
    FormApp.getUi().alert('未連携の回答はありません。');
    return;
  }
  var lines = ids.map(function (id) {
    var item = unsynced[id];
    return '・' + item.submittedAt + '（' + item.reason + '）';
  });
  FormApp.getUi().alert('未連携の回答: ' + ids.length + '件\n\n' + lines.join('\n'));
}

/**
 * メニュー「未連携を再送」: 送れなかった回答を、もう一度送る。
 * 時間主導型トリガー（例: 1時間ごと）に設定すれば、自動で再送させることもできる。
 */
function resendUnsynced() {
  var form = FormApp.getActiveForm();
  var ids = Object.keys(loadUnsynced_());
  var sent = 0;
  var failed = 0;
  ids.forEach(function (id) {
    var response = form.getResponse(id);
    if (!response) {
      // フォーム側で回答が消されていたら、もう送れないので一覧から外す
      removeUnsynced_(id);
      return;
    }
    var result = send_(response);
    if (result.ok) {
      removeUnsynced_(id);
      sent++;
    } else {
      markUnsynced_(response, result.reason);
      failed++;
    }
  });
  var message = '再送しました: 成功 ' + sent + '件、失敗 ' + failed + '件';
  console.log(message);
  // メニューから実行したときだけ画面に出す（時間主導型トリガーから動いたときは画面がないので出さない）
  try {
    FormApp.getUi().alert(message + (failed > 0 ? '\n失敗した回答は「未連携を確認」で理由を見られます。' : ''));
  } catch (error) {
    // 画面がない（トリガーから動いた）ので何もしない
  }
}

/**
 * 回答1件を応募受付APIへ送る。
 * @return {{ok: boolean, reason: string}} 送れたか、と送れなかった理由
 */
function send_(response) {
  var props = PropertiesService.getScriptProperties();
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

  // muteHttpExceptions: true にすると、APIがエラー（400や500など）を返しても例外にならず、結果を確認できる。
  // ただし、通信できない・時間切れ・URL未設定などは例外になるため、try/catch でも受け止める。
  try {
    var result = UrlFetchApp.fetch(props.getProperty('API_URL'), {
      method: 'post',
      contentType: 'application/json',
      headers: { 'X-Form-Key': props.getProperty('FORM_KEY') },
      payload: JSON.stringify(payload),
      muteHttpExceptions: true,
    });
    var code = result.getResponseCode();
    // 201 = 受付完了（同じ回答の再送も、受付済みとして201が返る）
    if (code === 201) {
      return { ok: true, reason: '' };
    }
    return { ok: false, reason: 'HTTP ' + code + ' ' + result.getContentText().slice(0, 200) };
  } catch (error) {
    return { ok: false, reason: '通信エラー: ' + error };
  }
}

// ---- 未連携の一覧（スクリプト プロパティに保存する） ----
// 1件ごとに「UNSYNCED_回答ID」という別々のキーで保存する。
// スクリプト プロパティは1つの値が最大9KB、全体で最大500KBまで（公式: https://developers.google.com/apps-script/guides/services/quotas ）。
// 1つのキーにまとめると数十件で9KBを超えてしまうが、1件ずつなら千件以上覚えておける。
// また、回答が同時に届いても、別々のキーなのでお互いの書き込みを消し合わない。

function loadUnsynced_() {
  var all = PropertiesService.getScriptProperties().getProperties();
  var unsynced = {};
  Object.keys(all).forEach(function (key) {
    if (key.indexOf(UNSYNCED_PREFIX) === 0) {
      unsynced[key.substring(UNSYNCED_PREFIX.length)] = JSON.parse(all[key]);
    }
  });
  return unsynced;
}

function markUnsynced_(response, reason) {
  PropertiesService.getScriptProperties().setProperty(UNSYNCED_PREFIX + response.getId(), JSON.stringify({
    submittedAt: response.getTimestamp().toISOString(),
    reason: reason,
    lastTriedAt: new Date().toISOString(),
  }));
}

function removeUnsynced_(id) {
  PropertiesService.getScriptProperties().deleteProperty(UNSYNCED_PREFIX + id);
}
