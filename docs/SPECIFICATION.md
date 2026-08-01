# ModServerPlayManagerByHaraguro 仕様書

対象バージョン: `0.1.0` / Minecraft `26.2` / Fabric Loader `0.19.3`

このドキュメントは実装の詳細な使い方（コマンド例など）は `README.md` に譲り、
「何が・なぜ・どう繋がっているか」を後から読んでも復元できるようにするための仕様書です。

---

## 1. 目的

自宅で立てた Minecraft (Fabric) サーバーに、友人が

1. 同じ mod / リソースパック構成に自動で揃えて
2. ワンクリックでゲームを起動し、サーバーへ自動接続する

ためのランチャーと、それを支える API サーバー・Fabric mod の一式。友人に配るのは
「実行ファイル化されたランチャー（jpackage 製）」のみで、Java や Gradle のセットアップは
不要にすることをゴールとする。

---

## 2. 全体構成

```
                 ┌───────────────────────┐
                 │   server/ (Fabric)     │  実際の Minecraft サーバー
                 │   mods/, resourcepacks/│
                 └───────────┬────────────┘
                             │ ファイルシステム読み取り
                             ▼
┌────────────┐   HTTP    ┌───────────────────┐
│  launcher   │◄─────────►│    sync-server      │
│ (JavaFX GUI)│  REST     │  (Javalin)         │
└─────┬───────┘           └────────────────────┘
      │ ゲームプロセス起動
      ▼
┌────────────┐
│ Minecraft   │  Quick Play でサーバーへ自動接続
│  Client     │
└────────────┘
```

- `server/`: Gradle ビルド対象外。純粋なランタイム配置ディレクトリ（Fabric サーバー本体、
  world データ、`mods/`, `resourcepacks/`）。
- `sync-server`: `server/mods`, `server/resourcepacks` を読み取り専用で走査し、launcher が
  差分同期に使うマニフェスト（ファイル名・サイズ・SHA-1）を HTTP で配信する。ファイル本体の
  ダウンロードエンドポイントも持つ。
- `launcher`: 友人の PC 上で動く JavaFX GUI アプリ。sync-server とマニフェストを突き合わせて
  差分の mod/リソースパックをダウンロードし、その後 Mojang/Fabric の公式配布物を使って
  Minecraft クライアント本体を（未インストールなら）ダウンロード・起動する。
- `mod`: `server/mods` に配置する Fabric mod 本体。sync-server と通信してプレイヤーデータ等を
  やり取りする（現状は雛形段階）。
- `shared`: 上記3者が共有するプロトコル定義（`Routes`）とデータモデル
  (`SyncManifest`, `SyncFile`, `PlayerProfile`)。

---

## 3. モジュール仕様

### 3.1 shared

`dev.haraguro.modserverplaymanager.shared` パッケージ。他モジュールが依存する共有コードのみを置く。

- `protocol.Routes` — REST エンドポイントのパス定数と `X-Api-Key` ヘッダー名の唯一の
  定義元。sync-server / launcher / mod はここを import して使う（パスのハードコード禁止）。
- `model.SyncManifest` — `minecraftVersion`, `fabricLoaderVersion`, `mods: List<SyncFile>`,
  `resourcePacks: List<SyncFile>`
- `model.SyncFile` — `fileName`, `size`, `sha1`
- `model.PlayerProfile` — `uuid`, `lastKnownName`, `balance`（将来の経済/為替連携用の雛形）

### 3.2 sync-server

エントリポイント: `dev.haraguro.modserverplaymanager.syncserver.SyncServerApp`

起動時に読むシステムプロパティ:

| プロパティ | デフォルト | 用途 |
|---|---|---|
| `mcsync.port` | `7070` | Javalin の待受ポート |
| `mcsync.server.dir` | `../server` | `server/` ディレクトリの場所 |
| `mcsync.api.key` | 未設定（＝認証なし） | `X-Api-Key` 認証を有効化する共有シークレット |
| `mcsync.minecraft.version` | `26.2` | マニフェストに載せる MC バージョン表示値 |
| `mcsync.fabric.loader.version` | `0.19.3` | マニフェストに載せる Fabric Loader バージョン表示値 |

主要クラス:

- `GsonJsonMapper` — Javalin の `JsonMapper` を Gson で実装したもの。Javalin 6.x は
  デフォルトで Jackson を要求するため、依存を増やさず Gson だけで完結させるために自作。
- `SyncKeyAuth.register(app, apiKey)` — `apiKey` が null/空なら何もしない（今まで通り
  無認証、LAN 内利用のデフォルト挙動）。値がある場合は `app.before()` フィルタで
  `/api/health` 以外の全リクエストに `X-Api-Key` ヘッダー一致を要求し、不一致は 401。
- `sync.ServerFilesService` — `server/mods`, `server/resourcepacks` をスキャンし
  `SyncFile` リストを構築。隠しファイル（`.` 始まり、`.gitkeep` 等）は除外。ファイル名から
  実ファイルを解決する際はパストラバーサル対策済み（`resolveModFile` /
  `resolveResourcePackFile` がディレクトリ外参照を拒否）。
- `routes.SyncRoutes` — マニフェスト配信・ファイルダウンロードの2種のエンドポイントを登録。
- `routes.PlayerRoutes`, `routes.ExternalRoutes` — プレイヤーデータ・外部サービス（為替
  レート等）用のルート雛形（現状は最小実装）。

#### REST API

ベース URL は launcher 側の `ServerProfile.apiBaseUrl`（例: `http://host:7070`）。

| メソッド | パス | 認証 | 説明 |
|---|---|---|---|
| GET | `/api/health` | 不要（常に無認証） | 疎通確認。`"ok"` を返す |
| GET | `/api/sync/manifest` | 必要（キー設定時） | `SyncManifest` を JSON で返す |
| GET | `/api/sync/mods/{file}` | 必要（キー設定時） | 指定 mod ファイルのバイナリを返す。存在しなければ 404 |
| GET | `/api/sync/resourcepacks/{file}` | 必要（キー設定時） | 指定リソースパックのバイナリを返す。存在しなければ 404 |
| GET | `/api/players/{uuid}` | 必要（キー設定時） | プレイヤーデータ（雛形） |
| GET | `/api/external/exchange-rates` | 必要（キー設定時） | 外部サービス連携用（雛形） |

認証が必要な場合、リクエストヘッダーに `X-Api-Key: <key>` を付与する。

### 3.3 launcher

#### 3.3.1 エントリポイント

- `dev.haraguro.modserverplaymanager.launcher.Launcher` — コンソール版（`:launcher:run` / CI・ヘッドレス開発向け）。
  GUI と同じ同期→起動フローを、対話なしで順番に実行する。
- `dev.haraguro.modserverplaymanager.launcher.gui.LauncherMain` — GUI 版の実際のエントリポイント（jpackage の
  `--main-class` にはこちらを指定）。`Application` を直接継承したクラスをクラスパス起動の
  main-class にすると "JavaFX runtime components are missing" で落ちるため、間に薄い
  非 `Application` クラスを挟んで `Application.launch()` を呼ぶだけにしている。
- `dev.haraguro.modserverplaymanager.launcher.gui.LauncherApp` — 実体の JavaFX `Application`。GUI のイベント配線と
  同期/更新/起動フローの制御を担当。

#### 3.3.2 起動フロー（GUI）

1. 起動時に `LauncherConfig.loadOrCreateDefault()` で `~/.mcsync/launcher-config.json` を
   読み込み（無ければデフォルト値で新規作成）。
2. `checkForUpdates()`: 現在の `activeServer` に対して `ModSyncService.preview()` を呼び、
   サーバー側マニフェストとローカルの mod/リソースパックを比較。差分があれば「Update」
   ボタン、無ければ「Play」ボタンを表示。
3. 並行して `checkForLauncherUpdate()`: GitHub Releases の最新タグと実行中バージョン
   （jar の `Implementation-Version`）を比較し、新しければ画面上部にバナー表示。
4. ユーザー操作:
   - **Update** 押下 → `ModSyncService.sync()` で差分ファイルをダウンロード（進捗は
     `GuiProgressReporter` 経由で 5% 刻みに表示）。完了後「Play」ボタンへ切り替わる。
   - **Play** 押下 → Microsoft アカウントでサインイン済み（`LauncherConfig.isMsaSignedIn()`）
     なら `MinecraftAuthenticator.signInSilently()` でキャッシュ済みリフレッシュトークンから
     セッションを再取得し、未サインインなら `OfflineAuth.session(playerName)` でオフライン
     セッションを作る。いずれの場合も得られた `AuthSession` を使って `GameLauncher.launch()`
     が Mojang/Fabric のプロファイル解決・必要ファイルの
     ダウンロード（クライアント本体・ライブラリ・アセット、`mcsync.downloadAssets=false`
     で省略可）・起動コマンド構築・プロセス起動までを行う。`serverAddress` が設定されて
     いれば Quick Play でそのまま接続される。
   - **Reset to Defaults** 押下 → 確認ダイアログの後、`LauncherConfig.resetToDefaults()`
     （インストール先・接続先サーバー・起動構成を初期値に）＋
     `ModSyncService.cleanupSyncedFiles()`（ダウンロード済み mod/リソースパック削除）を
     実行し、`checkForUpdates()` を再実行して差分が正しく再検出されることを確認できる
     ようにする。更新/クリーンアップ機能の動作確認用に追加された機能。
   - **Server タブ**: 接続先サーバー情報（名前・API URL・ゲームアドレス・MC バージョン・
     Fabric Loader バージョン・API キー）を自由入力し「Connect」で `activeServer` に反映
     （同期チェックが再実行される）。「Save Preset」で `servers` リストに名前つき保存。
     「Generate」ボタンで API キーをランダム生成しフィールドへ反映（サーバー側の
     `-Dmcsync.api.key=...` に手動でコピーする運用、自動配布はしない）。
   - **言語切り替え**: en / ja / sv。即座に画面文言を再描画（再起動不要）。
   - **インストール先変更**: `DirectoryChooser` で GUI から自由選択。

#### 3.3.3 主要クラス（`launch` パッケージ）

Mojang/Fabric の公開 API のみを用いてクライアントを実際に起動するためのロジック一式。

- `ProfileResolver` — Mojang version manifest (`piston-meta`) と Fabric meta API から
  起動プロファイルを取得・マージ（`inheritsFrom` チェーンをたどる）。
- `MinecraftProfile` / `ResolvedLibrary` — 解決済みプロファイルとライブラリ情報の内部表現。
- `Rules` — ライブラリ/引数の OS 条件・機能フラグ（`is_demo_user` 等）を評価し、現在の
  環境に適用すべきものだけを残す。
- `GameInstaller` — クライアント jar・ライブラリ・アセットのダウンロードとローカル配置
  （`FileDownloader` 経由）。
- `LaunchCommandBuilder` — 解決済みプロファイル + `LauncherConfig`（メモリ設定・
  インストール先等）+ `AuthSession` から実際の `java` 起動コマンド（クラスパス・JVM引数・
  ゲーム引数）を構築。
- `AuthSession` — `playerName`/`uuid`/`accessToken`/`userType`/`xuid` を保持するレコード。
  `OfflineAuth`（オフライン認証。UUID は
  `UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))` で決定的に算出、
  Minecraft バニラのオフラインモードと同じアルゴリズム）と `auth` パッケージの
  `MinecraftAuthenticator`（実 Microsoft アカウント）のどちらからも生成される。

#### 3.3.3.1 Microsoft サインイン（`auth` パッケージ）

`launch` の兄弟パッケージ。デバイスコードフロー -> Xbox Live -> XSTS ->
Minecraft services の一連の呼び出しを実装し、最終的に `AuthSession`
（`userType="msa"`）を組み立てる。

- `MicrosoftDeviceCodeAuth` — `login.microsoftonline.com/consumers` に対する
  デバイスコード取得・ポーリング（`authorization_pending`/`slow_down`/
  `expired_token`/`authorization_declined` を処理）・リフレッシュトークンでの
  サイレント再認証。
- `XboxLiveAuthenticator` — Xbox Live 認証（RPS チケット）と XSTS 認可
  （relying party `rp://api.minecraftservices.com/`）。XSTS の `XErr` コードを
  既知のものだけ `MsaAuthException.Reason`（Xbox プロフィール無し・要ペアレンタル
  許可・地域非対応）にマッピングし、未知のコードは raw のまま UNKNOWN として通知。
- `MinecraftServicesAuthenticator` — `login_with_xbox` で Minecraft アクセス
  トークンを取得し、`/minecraft/profile` から UUID・ユーザー名を取得（404 は
  ゲーム未所持として `GAME_NOT_OWNED` を送出）。
- `MinecraftAuthenticator` — 上記 3 クラスを束ねるオーケストレーター。
  `signInInteractive(clientId, onCodeReady)`（新規サインイン、デバイスコードを
  コールバックで通知してからポーリング）と `signInSilently(clientId, refreshToken)`
  （次回以降の起動用）の 2 経路を提供し、いずれも `AuthResult(AuthSession, 新しい
  refreshToken)` を返す。
- `MsaAuthException` — 上記チェーンの失敗を `Reason` enum 付きで表す例外
  （`CLIENT_ID_MISSING`/`DEVICE_CODE_EXPIRED`/`AUTHORIZATION_DECLINED`/
  `NO_XBOX_ACCOUNT`/`CHILD_ACCOUNT`/`BANNED_REGION`/`GAME_NOT_OWNED`/
  `REFRESH_TOKEN_INVALID`/`NETWORK`/`UNKNOWN`）。`ErrorMessages.describe()` が
  `Reason` ごとに `error.msa.<reason>.hint` キーへ振り分ける。

GUI 側は Play タブの「Account」セクション（`LauncherWindow` の
`accountStatusLabel`/`signInButton`/`signOutButton`/`msaDeviceCodeRow` 等）から
`LauncherApp.runMicrosoftSignIn()`/`onSignOut()` を呼び出す。サインイン結果
（クライアント ID・リフレッシュトークン・キャッシュ済みユーザー名/UUID）は
`LauncherConfig` に平文で保存（`msaClientId`/`msaRefreshToken`/
`msaCachedUsername`/`msaCachedUuid`）され、`isMsaSignedIn()`
（リフレッシュトークンが空でないか）がオフライン/Microsoft のどちらで Play するかを
決める唯一のフラグとして機能する。
- `GameLauncher` — 上記を束ねて実際に `ProcessBuilder` でクライアントを起動する
  エントリポイント。`serverAddress` があれば Quick Play 引数を付与して自動接続。
- `ProgressListener` / `ThrottledProgressReporter` / `ConsoleProgressReporter` /
  （GUI 側の）`GuiProgressReporter` — ダウンロード進捗の通知経路。
  `ThrottledProgressReporter` が5%刻みに間引き、コンソール/GUI それぞれの表示に変換する。
- `ErrorMessages.describe(Throwable)` — 例外を人間が読めるメッセージに変換（既知の
  `ConnectException` 等には具体的なヒントを付与）。

#### 3.3.4 同期ロジック（`sync.ModSyncService`）

- コンストラクタ: `ModSyncService(apiBaseUrl, apiKey, installDir)`
- `preview()` — `GET /api/sync/manifest` を取得し、ローカルの `installDir/mods`,
  `installDir/resourcepacks` と突き合わせて「最新かどうか／差分件数」を返す
  （ダウンロードは行わない、GUI 起動時のチェックに使用）。
- `sync(progressFactory)` — 差分ファイルを実際にダウンロードし配置。
- `cleanupSyncedFiles()` — ローカルにダウンロード済みの mod/リソースパックを削除
  （Reset to Defaults から利用）。
- 全リクエストで `apiKey` が非空なら `X-Api-Key` ヘッダーを付与（`withApiKey()` ヘルパー）。

#### 3.3.5 設定ファイル (`config.LauncherConfig`)

保存場所: `~/.mcsync/launcher-config.json`（Gson で pretty-print、UTF-8）。

```jsonc
{
  "language": "ja",                 // null なら OS ロケールから自動判定
  "installDir": "C:/Users/.../mcsync/install",
  "playerName": "Player",
  "activeServer": {                 // 現在選択中の接続先（自由入力可、servers に無くてもよい）
    "name": "Default",
    "apiBaseUrl": "http://localhost:7070",
    "serverAddress": "localhost:25565",
    "minecraftVersion": "26.2",
    "fabricLoaderVersion": "0.19.3",
    "apiKey": ""
  },
  "servers": [ /* ServerProfile の配列、名前つきプリセット */ ],
  "launchProfiles": [
    { "name": "Standard", "minMemoryMb": 1024, "maxMemoryMb": 4096 }
  ],
  "selectedLaunchProfile": "Standard"
}
```

- `activeServer` と `servers` は独立: `activeServer` は自由編集の「今使っているもの」、
  `servers` は「Save Preset」で明示的に保存した名前つき候補リスト。
- 旧スキーマ（フィールド欠落）からの読み込みは `backfillDefaults()` が救済し、
  欠けたフィールドをデフォルト値で補完してから使う（マイグレーション相当）。
- `resetToDefaults()` は `installDir` / `activeServer` / `selectedLaunchProfile` のみを
  初期値に戻す（`servers`・`language` は保持される）。

`ServerProfile` レコード: `name, apiBaseUrl, serverAddress, minecraftVersion,
fabricLoaderVersion, apiKey`。`apiKey` が非空なら通信時に `X-Api-Key` ヘッダーとして送る。

`LaunchProfile` レコード: `name, minMemoryMb, maxMemoryMb`（`-Xms` / `-Xmx` に対応）。

#### 3.3.6 多言語対応 (`i18n`)

- `Lang` enum: `ENGLISH` / `JAPANESE` / `SWEDISH`。`detectFromOs()` で OS ロケールから
  初期値を推定。
- `Messages` — `Map<Lang, Map<String,String>>` によるインコードの翻訳テーブル
  （`.properties`/`ResourceBundle` は既定で ISO-8859-1 になり日本語/スウェーデン語が
  文字化けするため意図的に不使用）。`get(key, args...)` は `String.format` でパラメータ
  展開し、キー未定義時は英語 → キー名そのものにフォールバック。

#### 3.3.7 自動アップデートチェック (`update`)

- `AppVersion.current()` — jar マニフェストの `Implementation-Version` を読む。
  `:launcher:run`（クラスパス起動）ではこの属性が無いため `"dev"` を返し、
  アップデートチェック自体をスキップする。
- `UpdateChecker` — `GET https://api.github.com/repos/haragurojyakku/
  MinecraftSyncLauncher_Releases/releases/latest` を叩き、タグ名 (`vX.Y.Z`) を
  `AppVersion.current()` と数値比較 (`isNewer()`)。リリースが1件も無ければ 404 を
  正常系として無視。新しい版があれば `UpdateInfo(version, releaseUrl)` を返す。
- GUI 側は新しい版があればバナー表示のみ行い、ボタン押下で
  `Desktop.getDesktop().browse(releaseUrl)` によりブラウザでダウンロードページを開く。
  **自動ダウンロード・自動置き換え（サイレントアップデート）は意図的に未実装** —
  ユーザーの一手を必ず挟む設計。

#### 3.3.8 配布パッケージング

- `:launcher:jpackageAppImage` — `installDist` の出力を入力に `jpackage --type app-image`
  を実行し、バンドル JRE 込みのネイティブアプリを `build/jpackage/ModServerPlayManagerByHaraguro/`
  に生成。`--main-class` は `dev.haraguro.modserverplaymanager.launcher.gui.LauncherMain`。クロスコンパイル不可
  （実行した OS 向けのビルドしか作れない）。
- `:launcher:releaseZip` — 上記出力を
  `build/distributions/ModServerPlayManagerByHaraguro-<version>-<platform>.zip` に圧縮。
  GitHub Release へのアップロード資産として使う。
- `:launcher:generateWhitelist -PwhitelistNames=alice,bob` — オフラインモードの
  決定的 UUID を使い、`server/whitelist.json` に貼り付ける JSON 配列を標準出力に印字する
  CLI ツール（`tools.WhitelistGenerator`）。

### 3.4 mod

Fabric mod 本体。現状は `SyncMod`（エントリポイント）と `api.ApiClient`（sync-server との
通信雛形）のみ。`server/mods/` に配置して稼働させる想定（プレイヤーデータ・経済連携などの
実処理はこれから拡張）。

### 3.5 server/（ランタイム、Gradle 非対象）

Fabric サーバー本体の配置場所。`eula.txt`, `server.properties`, `start.sh`/`start.bat` の
テンプレートを含む。`mods/`, `resourcepacks/` は sync-server が読み取る対象そのもの
（= ここに置いたものがそのまま launcher に配布される）。本リポジトリは非公開のため、
`world/` 等の実データも `.gitignore` で除外していない（バックアップ目的、詳細は
README「Development vs. release repos」参照）。

---

## 4. セキュリティモデル

`auth` パッケージの Microsoft サインイン（3.3.3.1 参照）が実アカウント制度そのもの
——サーバーを `online-mode=true` にすれば Mojang/Xbox 側でユーザー名の本人性を
検証できる。ただし launcher が保存するリフレッシュトークンは
`launcher-config.json` に平文保存（暗号化ストレージ未実装）なので、共有 PC での
利用時は注意が必要（サインアウトで即座に消去可能）。

`OfflineAuth`（オフラインモード）のまま運用する場合、インターネット越しに公開する際は
以下2点が必須という前提で設計されている。

1. **sync-server の API キー認証** — `-Dmcsync.api.key=<secret>` を設定すると
   `/api/health` 以外の全エンドポイントで `X-Api-Key` ヘッダー一致を要求する
   （`SyncKeyAuth`）。未設定時は認証なし＝ LAN 限定利用のデフォルト挙動。
   友人には同じキー文字列を渡し、launcher の Server タブに貼ってもらう。
2. **Minecraft サーバーのホワイトリスト** — `online-mode=false`（オフラインモード）
   では、サーバーはユーザー名を検証しないため誰でも好きな名前を名乗れる。
   `white-list=true` を設定し、`WhitelistGenerator` で生成した決定的 UUID を
   `server/whitelist.json` に登録することでなりすまし接続を防ぐ。

いずれも「設定すれば有効、しなければ今まで通り無認証」という後方互換な opt-in 設計。

---

## 5. ビルド・バージョニング

- ルート `gradle.properties` の `version` が全モジュール共通のプロジェクトバージョン。
  jpackage の `--app-version` にもここから渡る（`-SNAPSHOT` 等の修飾子は除去）。
  リリース時はこの値と GitHub Release のタグ（`v` 接頭辞つき）を一致させる。
- Java/Fabric バージョン群も同ファイルで一元管理
  （`minecraft_version`, `fabric_loader_version`, `fabric_api_version`, `loom_version`,
  `java_version`）。`server/` 側のセットアップ手順・`SyncRoutes` の表示値もこれと
  手動で同期させる必要がある（自動連携はしていない）。

---

## 6. リポジトリ運用

- **開発用（本リポジトリ、非公開）**: `MinecraftSyncLauncher` — ソース一式、起動構成
  (`.vscode/launch.json`)、`server/` のランタイムデータ（バックアップ目的）まで含む。
- **配布用（公開）**: `MinecraftSyncLauncher_Releases` — タグ付きリリースの zip 資産のみを
  置くクリーンなリポジトリ。ソースコードは含まない。`UpdateChecker` はこちらの
  `/releases/latest` API を参照する。
- 新バージョンをリリースする手順は README「Releasing a new launcher version」を参照。

---

## 7. 既知の制約・今後の課題

- Microsoft サインインは単一アカウントのみキャッシュ（アカウント切り替え/複数アカウント
  管理は無し、別アカウントでサインインすると既存のキャッシュを上書き）。リフレッシュ
  トークンは平文保存（資格情報ストア連携は未実装）。オフライン運用時のなりすまし対策は
  引き続きホワイトリスト運用に依存。
- ランチャー自身の自動更新は「確認して案内するだけ」— サイレントな自動ダウンロード・
  自動置き換えは未実装。
- `mod` モジュールは雛形段階（`ApiClient` の実処理は今後拡張予定）。
- `PlayerRoutes` / `ExternalRoutes`（為替レート等）は最小実装で、実データ連携は未着手。
