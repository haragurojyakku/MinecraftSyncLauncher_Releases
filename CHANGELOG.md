# 更新履歴 (Changelog)

このファイルは開発リポジトリ (`MinecraftSyncLauncher`, private) に記録する更新履歴です。
配布用リリースそのもの（zip 資産）は別リポジトリ `MinecraftSyncLauncher_Releases` の
[Releases ページ](https://github.com/haragurojyakku/MinecraftSyncLauncher_Releases/releases)
を参照してください。バージョン番号は `gradle.properties` の `version` と一致します。

フォーマットは [Keep a Changelog](https://keepachangelog.com/) に緩く準拠しています。

## [0.6.3] — バンク(エメラルド通貨)機能とワールドセーブ切り替え機能 — 2026-08-01

### 追加 (Added)

**mod モジュール**
- `dev.haraguro.modserverplaymanager.mod.bank`パッケージを追加。村人取引のエメラルド
  受け渡しをsync-server上のプレイヤー残高(1エメラルド=1通貨単位)にリダイレクトする
  `BankTradeService`を実装(`MerchantMenuMixin`が購入時の不足分を残高から補填、
  `AbstractVillagerMixin`が売却時に受け取ったエメラルドを残高へ回収)。
- `/bank balance`・`/bank pay <player> <amount>`コマンド(`BankCommands`)を追加。
  残高照会とプレイヤー間送金に対応(残高不足・自己送金はエラーメッセージで拒否)。
- 自分のエンダーチェスト画面に「Bank」ボタンを追加(`EnderChestBankMixin`)。
  `BankPanelScreen`で残高照会・入出金ができ、クライアント<->サーバー通信は
  `bank/network/`のカスタムペイロード経由(`BankNetworking`に登録)。

**server-manager / shared / launcher モジュール**
- ワールドセーブの切り替え機能を追加。`server/<name>/`ディレクトリ単位でワールドを
  保持し、`server.properties`の`level-name`切り替えで有効化する
  (`WorldProfileService`、`MANAGER_WORLDS*`エンドポイント)。
- launcherに「Worlds」タブを追加。ワールド一覧の表示、新規作成(既存ワールドの
  複製 or 空のワールド)、有効化(サーバーの停止/切替/再起動を自動で行う)、
  名前変更、削除に対応。

**sync-server モジュール**
- プレイヤーデータをファイル永続化(`<serverDir>/.mcsync-players.json`、
  `PlayerAccountStore`)に切り替え。インメモリのプレースホルダー実装から移行。
- 残高の入金/出金/送金API(`PLAYER_BALANCE_DEPOSIT`/`WITHDRAW`/`TRANSFER`)を追加。

### 変更 (Changed)

**開発環境**
- サーバー配布物として`Haraguro_unlimitedtrades-1.0.0.jar`(検証用MOD)と
  `mod-0.6.2.jar`を`server/mods/`に追加。

## [0.6.2] — 起動構成プロファイルと接続設定ファイルの共有機能 — 2026-07-28

### 追加 (Added)

**launcher モジュール**
- 起動構成(`LaunchProfile`)にプレイヤー名を持たせるようにした。同じPCを複数人で使う場合、
  起動構成を切り替えるだけでメモリ設定と一緒にプレイヤー名も切り替わる（旧`launcher-config.json`の
  トップレベル`playerName`は選択中の起動構成へ一度だけ自動移行される）。
- 起動構成の新規作成・名前変更・削除・読み込み・書き出しに対応（サーバープロファイル側の
  新規作成/名前変更/読み込み/書き出し/削除と同じ操作感）。
- サーバープロファイルの名前変更・削除に対応（`LauncherConfig.renameServerPreset`/
  `deleteServerPreset`）。
- 接続設定ファイルのエクスポート/インポートを追加。「エクスポート」でサーバーURL・ゲーム
  アドレス・APIキーをまとめたファイルを書き出し、友達に渡せば「インポート」するだけで
  そのサーバーに接続できるようになる。
- 上記の暗号化版（`ConnectionFileCrypto`、AES-256-GCM + PBKDF2）を追加。パスワードを
  別経路（チャット等）で伝える運用を想定し、インポートしたプロファインの接続先URL/
  ゲームアドレス/APIキーはサーバー接続設定タブ上でマスク表示（非表示）のまま扱われる。
- ゲームサーバー（Minecraft本体のポート）への到達性を直接チェックする
  `GameServerConnectivityChecker`を追加。server-managerの管理APIとは別に、
  「実際に今このサーバーに参加できるか」を判定する。
- 有効なリソースパック一覧をクリップボードにコピーするボタンを追加（Modの一覧コピー
  ボタンと同様）。
- 接続先アドレスのマスク表示/表示切り替えボタンを追加。

### 修正 (Fixed)

**launcher モジュール**
- 同期プレイタブで、コマンド操作系のUIとMod/リソースパック一覧のUIの横幅が均等に
  分割されるよう修正。リスト側だけに伸長優先度(`Priority.ALWAYS`)が設定されており、
  余った横幅が全てリスト側に入ってしまい右側だけ極端に大きくなっていた。

## [0.6.1] — 自己更新のダウンロード失敗を修正 — 2026-07-28

### 修正 (Fixed)

**launcher モジュール**
- `SelfUpdater` が0.6.0のリリース資産をダウンロードできず `HTTP 302` で失敗する不具合を修正。
  GitHubの `/releases/download/...` アセットURLは実体(objects.githubusercontent.com)への
  302リダイレクトだが、`HttpClient` のリダイレクト追従がデフォルト(`Redirect.NEVER`)の
  ままだったため、リダイレクト応答自体をエラーとして扱っていた。
  `.followRedirects(HttpClient.Redirect.NORMAL)` を設定して解消。

## [0.6.0] — 公開用ゲームサーバーアドレスの配布 — 2026-07-28

### 追加 (Added)

**shared / sync-server / launcher モジュール**
- `SyncServerSettings` に `publicGameServerAddress` を追加。ホストがポート開放後の
  外部接続先(`WAN側IP:ポート`)を一度入力しておくと、既存のAPIキー保護済み
  `/api/sync/settings` エンドポイント経由でフレンドの launcher から取得できるように
  なった。従来はゲームサーバーアドレスを別経路(チャット等)で個別に伝える必要があった。
- launcher の Mod タブに公開用アドレスの入力欄+「公開する」ボタンを追加(ホスト側)。
  「サーバー追加」ダイアログのゲームサーバーアドレス欄には「取得」ボタンを追加し、
  同期サーバーURL+APIキーを入力した状態でクリックすると自動入力される(フレンド側)。
  未公開/通信失敗時はアラートで案内する。
- 上書き保護トグル(`preventOverwriteOnUpload`)の更新が、同じ `SyncServerSettings`
  オブジェクトのもう一方のフィールド(公開アドレス)を意図せず消してしまわないよう、
  `LauncherApp` に最新設定のキャッシュ(`lastSyncSettings`)を持たせて両フィールドを
  保持し合うようにした。

**launcher モジュール**
- UPnPによる自動ポート開放が失敗した際のエラーを、ステータス欄だけでなく
  Server Managerタブのログにも一度だけ出力するようにした(`manager.log.portForwardingError`)。
  5秒間隔のバックグラウンドポーリングで同じエラーが繰り返しログへ流れないよう、
  直前に記録した内容と比較してから出力する。

## [0.5.0] — Modのサーバー/クライアント分類と同期の安全性向上 — 2026-07-28

### 追加 (Added)

**shared / sync-server / launcher モジュール**
- Mod を「サーバー・クライアント両方に必要」「サーバーのみ必要」「クライアントのみ必要」の
  3種類に分類できるようにした（`model.ModCategory`）。従来は `server/mods` に置いて有効化した
  mod がそのまま無条件で全プレイヤーへ配布される「サーバーのフォルダをそのまま配る」方式
  だったが、サーバー専用の最適化/管理系 mod まで全員がダウンロードさせられる問題があった。
  - `sync.ModCategoryStore`（sync-server）が `server/mods/.mcsync-categories.json` に
    mod ごとの分類を永続化（デフォルトの BOTH は未記録、無効化しても分類は保持される）。
  - `ServerFilesService.buildManifest()` は `SERVER_ONLY` に分類された mod を
    `SyncManifest`（enabled/disabled とも）から除外するようになった — サーバー自身は
    そのまま読み込むが、launcher には一切広告されない。
  - REST: `PUT /api/sync/mods/{file}/category`（body: `{"category": "SERVER_ONLY"}`）を追加。
  - `CLIENT_ONLY` に分類した mod は `server/mods/client-only/` サブフォルダへ実ファイルごと
    移動するようにした（`setModCategory`）。当初は分類してもファイルの有効/無効状態には
    一切触れない仕様だったが、それだと `server/mods` 直下に置かれたままの `CLIENT_ONLY` mod
    を実際のFabricサーバー本体がそのまま読み込んでしまい、クライアント専用modが
    サーバー側でも起動時にロードされる状態だった。Fabric Loaderのディレクトリ探索は
    mods フォルダのトップレベルのみを見てサブフォルダを再帰しない仕様を利用し、
    `client-only/` に退避させることで実サーバープロセスには一切見えなくしつつ、
    同期マニフェスト（`buildManifest`）側は両方のフォルダを合成して見せることで
    プレイヤーへの配布（enabled/disabled 状態込み）は変わらないようにしている。
    `resolveModFile`/`deleteModFile`/`toggleModFile`/`addModFile`（再アップロード時）も
    両方のフォルダを見るように追随。旧バージョンで `CLIENT_ONLY` に分類済みだが
    まだ `server/mods` 直下に残っているファイルは、次回起動時に自動で
    `client-only/` へ移行される（`migrateClientOnlyMods`）。
  - launcher の「Mod」タブと「リソースパック」タブを分離した（従来は1つのタブに2カラムで
    同居していた）。Mod タブはさらに「サーバー+クライアント両方」「サーバーのみ」
    「クライアントのみ」の3リストに分割表示され、選択中の mod をどのリストへでも
    「移動」ボタンで再分類できる（`withSelectedMod`/`exclusiveSelection` — 3つの
    `ListView` にまたがる単一選択として扱う）。リソースパックタブは分類の概念自体が
    無い（サーバー側で実行されないため）ぶん、従来通りの単一リストのまま。
    それぞれ専用のログ欄を持つ。

**launcher モジュール**
- クライアント側の同期処理（`ModSyncService`）が、プレイヤーが個人的に追加した
  client 専用 mod/リソースパックを同期のたびに削除してしまっていた問題を修正。
  従来は「サーバーの enabled/disabled リストのどちらにも載っていないローカルファイルは
  無条件で削除」という単純なミラーリングだったため、同期対象外の個人 mod を
  `installDir/mods` に置くと次回同期で消えてしまっていた。
  - `sync.SyncedFileStore` を新設し、`installDir/mods/.mcsync-managed.json`（および
    `resourcepacks` 側）に「サーバーが過去に配布したことのあるファイル名」を記録する
    ようにした。`reconcile()` はマニフェストに載っているファイルを見るたびにこの
    記録へ追加し、「サーバーのどちらのリストにもない余剰ファイル」は
    この記録に載っているものだけを削除する（＝過去にサーバーが配布して今回外れた
    もの）。記録に一度も載ったことのないファイル（＝最初からユーザーが個人で
    置いていた mod）は同期対象外として永久に触らない。
  - 「デフォルトに戻す」機能（`cleanupSyncedFiles`）も同様に、過去にサーバーが
    配布したファイルだけを削除するようになった（従来は mods/resourcepacks
    フォルダの中身を無条件全削除しており、個人 mod も巻き添えで消えていた）。

### 修正 (Fixed)

**server-manager モジュール**
- ホワイトリスト追加/削除で、サーバー稼働中に「追加したのに接続を拒否され続ける」
  不具合を修正。原因は `ServerProcessSupervisor.addToWhitelistViaRcon`/
  `removeFromWhitelistViaRcon` がバニラの `whitelist add`/`whitelist remove`
  コマンドをそのまま実行しており、このコマンドがプレイヤー名の名前解決に
  サーバー自身のプロファイルキャッシュ(`usercache.json`)を使うこと。
  online-mode=true から offline-mode に切り替えた後などにこのキャッシュへ古い
  (本物のMojang)UUIDが残っていると、`whitelist.json`にその古いUUIDで
  書き込まれてしまい、クライアントが実際に提示するオフラインUUID
  (`OfflineAuth`と同じ決定論的導出)と一致せず弾かれ続けていた。
  `ServerProcessSupervisor.reloadWhitelistIfRunning()`を新設し、
  `ManagerRoutes`側は常に`WhitelistService`(決定論的オフラインUUIDで
  `whitelist.json`を直接編集)でファイルを更新したうえで、稼働中のサーバーには
  `whitelist reload`のみを投げるように変更した。

## [0.4.3] — タイトルバーのステータス表示

### 追加 (Added)

**launcher モジュール**
- ウィンドウ上部のタイトル横に3つのステータスを常時表示するようにした（`LauncherWindow`）。
  - 起動構成(Mod/リソースパック)が最新かどうか（Play タブの Update/Play ボタンの
    表示状態と連動、`refreshSyncIndicator()`）。
  - 接続先サーバーが現在接続可能な状態か（Server Managerの`ManagerStatus.state`が
    `RUNNING`かどうかで判定、5秒間隔の自動ポーリングで追従）。
  - 「次回起動時にこのPCをサーバーホストとして使うか」のチェックボックス
    （Server Managerタブの既存`hostModeCheckBox`と双方向に同期する複製）。

## [0.4.2] — クラッシュ詳細ログ + Server Managerステータスの自動更新

### 追加 (Added)

**server-manager モジュール**
- `ServerProcessSupervisor` がサーバー子プロセスの直近コンソール出力(最大40行)を
  常時ローリングバッファに保持し、意図しないクラッシュを検知した瞬間のスナップショットを
  `lastCrashReason` として保持するようにした。`ManagerStatus`(shared)経由で
  `/api/manager/status` から取得できる。

**launcher モジュール**
- Server Managerタブのステータス(状態/稼働時間/オンラインプレイヤー/ホワイトリスト/
  接続試行/バックアップ一覧)を、約5秒間隔でバックグラウンド自動更新するようにした
  （`LauncherApp`の`managerStatusPoller`）。手動の「更新」ボタンとは別導線で、
  自動更新側は操作不能状態への切り替えやログスパムを起こさないよう静かに動作する
  （`fetchAndShowManagerStatus`を共通コアとして分離）。
- クラッシュ回数が増加したことを自動検知すると、`lastCrashReason`の内容を
  Server Managerタブのログへ自動的に出力するようになった。「何回落ちたか」だけでなく
  「なぜ落ちたか」がログを開かなくてもGUI上で分かるようにするための変更。

## [0.4.1] — オフラインモードへの切り替え + Microsoft サインインUIの非表示化

### 変更 (Changed)

**server 設定**
- `server/server.properties` の `online-mode` を `false` に変更。Microsoft/Mojangアカウントでの
  本人確認を必須にする運用から、ホワイトリストで参加者を絞るオフライン運用へ切り替えた
  （Azure ADアプリ登録が個人アカウントでは非推奨になり、実用上M365開発者プログラムの
  90日更新制サンドボックステナントが必要になるなど、小規模フレンドサーバー向けとしては
  不釣り合いな複雑さだったため）。

**launcher モジュール**
- Playタブの「アカウント」欄からMicrosoftサインイン関連のUI（Client ID入力欄、
  Sign in/out ボタン、デバイスコード案内バナー）を非表示化。オフラインのプレイヤー名欄
  のみを常時表示するレイアウトに変更（`LauncherWindow.java`）。認証まわりのバックエンド
  コード自体は変更しておらず、UIから到達できなくしただけ — 将来online-mode=trueに戻す
  場合はレイアウトに追加し直すだけで復活できる。

## [0.4.0] — ホワイトリスト編集 + 接続試行の確認

### 追加 (Added)

**server-manager モジュール**
- ホワイトリスト追加/削除は、サーバー稼働中はRCON経由でバニラ本体の`whitelist add`/
  `whitelist remove`コマンドをそのまま実行する（`ServerProcessSupervisor.addToWhitelistViaRcon`/
  `removeFromWhitelistViaRcon`）。online-mode=trueのサーバーでは実際のMojang UUIDを
  バニラ自身が解決してくれるため、こちら側でUUIDを推定する必要がない。サーバー停止中の
  場合のみ`config/WhitelistService`がwhitelist.jsonを直接編集するフォールバックへ回る
  （この場合はオフラインUUIDの推定値になるため、online-modeサーバーでは起動後に
  追加し直す必要がある旨をランチャー側ログで案内する）。
- `process/ConnectionLogParser`: サーバーコンソールの出力行から接続試行を検出する
  純粋なパーサー。`UUID of player <name> is <uuid>`行でuuidを一時保持し、続く
  `<name> joined the game`（許可）または`Disconnecting <name>: <reason>`（ホワイトリスト
  未登録・BAN等での拒否）でイベント化する。直近50件を`ServerProcessSupervisor`がメモリ上に
  保持（再起動でリセットされる、常時ログではない）。
- REST: `GET/POST /api/manager/whitelist`、`DELETE /api/manager/whitelist/{name}`、
  `GET /api/manager/connection-attempts`。

**shared モジュール**
- `model.WhitelistEntry`（uuid/name）、`model.ConnectionAttempt`（name/uuid/accepted/reason/
  timestampMillis）、`model.WhitelistUpdateResult`（entries/appliedLive — RCON経由で
  実際に反映されたか、ファイル直接編集のフォールバックだったかを呼び出し側に伝える）を追加。

**launcher モジュール**
- `ManagerClient`に対応するHTTPメソッドを追加。
- Server Managerタブに「ホワイトリスト」セクション（一覧・名前入力・追加/削除ボタン）と
  「最近の接続試行」一覧（許可/拒否と理由を表示、選択した接続試行をワンクリックで
  ホワイトリストに追加するボタン）を追加。参加を拒否された(ホワイトリスト未登録の)
  フレンドの正確な名前をコンソールログを見に行かずに把握し、そのままホワイトリストに
  追加できるようにするための機能。

## [0.3.0] — servers.dat への自動登録

### 追加 (Added)

**launcher モジュール**
- Play 起動時、接続先サーバーがゲーム本体の servers.dat（マルチプレイのサーバー
  リスト）に自動で追加/更新されるようになった。手動で「マルチプレイ -> サーバーを
  追加」する必要がなくなり、quick play で一度参加するだけでなく次回以降ゲーム内の
  一覧からも選べる。
  - `launch.ServersDatWriter`: servers.dat（非圧縮NBT）の最小限の読み書き実装。
    既存のエントリ（icon/hidden/acceptTextures 等）はそのまま保持し、アクティブ
    サーバーと同名のエントリの ip のみ更新、なければ新規追加する。
  - `GameLauncher.launch()` から `config.isAutoConnectEnabled()` の場合に呼び出す。

## [0.2.0] — Microsoft サインイン対応

### 追加 (Added)

**launcher モジュール**
- Microsoft アカウントでのサインイン (`auth` パッケージ): デバイスコードフロー ->
  Xbox Live 認証 -> XSTS 認可 -> Minecraft services トークン取得 -> プロフィール取得、
  という一連のチェーンを実装し、`online-mode=true` のサーバーに実アカウントとして参加
  できるようになった。
  - `MicrosoftDeviceCodeAuth` / `XboxLiveAuthenticator` / `MinecraftServicesAuthenticator` /
    `MinecraftAuthenticator`（オーケストレーター）/ `MsaAuthException`。
  - 次回以降の起動時はキャッシュ済みリフレッシュトークンでサイレント再認証（デバイス
    コードの再入力不要）。
  - Play タブに Account セクションを追加: オフライン名前欄と Microsoft サインイン/
    サインアウトボタン、サインイン中のユーザーコード表示バナー（ブラウザを開く/
    キャンセル可能）。
  - `AuthSession` に `xuid` フィールドを追加（`LaunchCommandBuilder` の `auth_xuid` が
    実値を使うようになった。オフライン認証は従来通り `"0"`）。
  - 日本語・英語・スウェーデン語のサインイン関連メッセージを追加。

### 既知の制約 (Known limitations)

- Microsoft サインインは単一アカウントのみキャッシュ（アカウント切り替え/複数アカウント
  管理は無し）。リフレッシュトークンは `launcher-config.json` に平文保存（資格情報ストア
  連携は未実装、Sign out で即座に消去可能）。
- インストーラー形式（`--type msi`/`exe`）のビルドには WiX Toolset が必要で未対応。
  引き続き app-image（zip 配布）のみ。

## [0.1.0] — 初回構成

最小構成から実際に「友人と自宅サーバーで一緒に遊べる」ところまでを一気に作った、最初のまとまり。

### 追加 (Added)

**プロジェクト構成**
- Gradle マルチモジュール構成（Kotlin DSL）: `shared` / `mod` / `launcher` / `api-server`
  の4モジュール。`server/` はビルド対象外のランタイム配置ディレクトリとして分離。
- Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.155.2+26.2 / Fabric Loom 1.17.17 /
  Java 25 toolchain で固定。

**mod モジュール**
- Fabric mod の雛形（`SyncMod`, `ApiClient`）。API サーバーと通信する土台。

**api-server モジュール**
- Javalin ベースの REST API サーバー。`server/mods`, `server/resourcepacks` を走査して
  同期マニフェスト（ファイル名・サイズ・SHA-1）を生成し配信。
- ファイルダウンロードエンドポイント（mods/resourcepacks）。
- プレイヤーデータ・為替レート（外部サービス連携の将来枠）のルート雛形。
- Gson ベースの独自 `JsonMapper`（Javalin 6.x のデフォルト Jackson 依存を回避）。
- API キー認証 (`X-Api-Key` ヘッダー)。`/api/health` のみ常に無認証で開放。キー未設定時は
  今まで通り無認証（LAN 内利用を想定したデフォルト）。

**launcher モジュール（本体）**
- 実際に Minecraft クライアントを起動できるロジック一式:
  - Mojang version manifest / Fabric meta API からの起動プロファイル解決
    (`ProfileResolver`, `MinecraftProfile`, `Rules` による OS/機能フラグ判定)
  - クライアント本体・ライブラリ・アセットのダウンロード (`GameInstaller`, `FileDownloader`)
  - 起動コマンド構築 (`LaunchCommandBuilder`) とプロセス起動 (`GameLauncher`)
  - オフライン認証 (`OfflineAuth` — `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`)
  - クイックプレイによるサーバー自動接続
- Mod/リソースパックの同期サービス (`ModSyncService`): サーバー側マニフェストとの差分検出・
  ダウンロード・不要ファイル削除 (`cleanupSyncedFiles`)。
- ダウンロード進捗表示 (`ThrottledProgressReporter` / `ConsoleProgressReporter` /
  `GuiProgressReporter` — 5% 刻みで間引き通知)。
- JavaFX 製 GUI (`LauncherWindow`, `LauncherApp`, `LauncherMain`):
  - Play / Server の2タブ構成
  - 日本語・英語・スウェーデン語の多言語対応（OS ロケール自動検出 + 手動切り替え）
  - インストール先ディレクトリを GUI から自由に参照・変更
  - 接続先サーバー情報（API URL・ゲームアドレス・バージョン・API キー）を GUI から自由入力・
    保存（複数サーバーのプリセット管理）
  - API キーの「自動生成」ボタン（ランダムな長い文字列を生成しフィールドに反映)
  - 起動構成（メモリ割り当て等）の選択
  - 「デフォルトに戻す」機能: インストール先・接続先サーバー・起動構成をデフォルト値に戻し、
    ダウンロード済み mod/リソースパックを削除してクリーンな状態に戻す
    （更新フローが正しく差分検出・再ダウンロードすることを確認するための機能）
- 実行ファイル化 (`jpackageAppImage` Gradle タスク): バンドル JRE 込みの Windows 用スタンド
  アロンアプリを生成。友人に配布可能な形。`releaseZip` タスクで配布用 zip も自動生成。
- ランチャー自身の自動アップデートチェック (`UpdateChecker`, `AppVersion`):
  GitHub Releases (`MinecraftSyncLauncher_Releases` リポジトリ) の最新タグと実行中バージョン
  を比較し、新しい版があれば GUI にバナー表示 → ボタン押下でダウンロードページをブラウザで
  開く（自動ダウンロード・自動置き換えは未実装、意図的にユーザーの一手を挟む設計）。
- ホワイトリスト生成ツール (`WhitelistGenerator`): オフラインモードのユーザー名からサーバー
  の `whitelist.json` エントリを生成する CLI。

**server/（ランタイム配置ディレクトリ）**
- Fabric サーバーの初期セットアップ手順・`eula.txt` / `server.properties` / 起動スクリプト
  のテンプレート一式。

**セキュリティ**
- api-server の API キー認証、Minecraft サーバーのホワイトリスト運用手順を整備
  （友人がインターネット経由で自宅サーバーに接続する前提のため）。

**開発体制**
- 開発用リポジトリ（本リポジトリ、非公開）と、リリース資産のみを置く配布用リポジトリ
  (`MinecraftSyncLauncher_Releases`, 公開) の二本立て運用を確立。
- VS Code 用デバッグ構成 (`launch.json`): api-server / launcher（コンソール） /
  launcher（GUI）/ 両方同時起動の compound 構成。

### 既知の制約 / 今後の課題

- Microsoft アカウント認証は未実装。現状はオフラインモード起動のみのため、接続先の
  Minecraft サーバー側は `online-mode=false` である必要がある（なりすまし対策としては
  ホワイトリスト運用が必須）。
- ランチャーの自動アップデートは「確認してブラウザを開く」までで、自動ダウンロード・
  自動置き換え（サイレントアップデート）は未実装。
