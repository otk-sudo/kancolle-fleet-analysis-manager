// ログインのトークン（APIを呼ぶための「通行証」）を、ブラウザに保存しておく場所。
//
// localStorage はブラウザに文字を保存しておける場所で、タブを閉じても消えない。
// 同じサイトのほかのタブからも読めるので、別のタブで開く配信用画面でも、ログインし直さずに使える。
// トークンには期限があり（仮ログインは12時間）、期限が切れると API が 401 を返すので、ログインし直してもらう。
//
// TODO(段階4): Cognito のログインに変えるときに、保存のしかたを見直す（Cognito の更新用トークンを使うかなど）

const STORAGE_KEY = 'fleet-analysis.token'

/** 保存しているトークンを返す。ログインしていなければ null */
export function loadToken(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    // ブラウザの設定で保存が禁止されているときなどは、ログインしていない扱いにする
    return null
  }
}

export function saveToken(token: string): void {
  localStorage.setItem(STORAGE_KEY, token)
}

export function clearToken(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 消せなくても、次に 401 が返ったときにログイン画面へ行くので、ここでは何もしない
  }
}
