# Zero Agent — Android MVP

## 1. 目的

Androidアプリからボタン1つで、OpenRouter経由のAIが「企画 → 投稿案生成 → 拡散用コンテンツ生成 → 公開処理」を実行できるMVPを作る。

最初のMVPでは Creator Agent を中心に実装し、外部サービスはConnector方式で後から追加できるようにする。

## 2. ワンタップフロー

```text
[ AIに稼働させる ]
        ↓
OpenRouter
        ↓
企画生成
        ↓
コンテンツ企画・画像プロンプト生成
        ↓
投稿本文生成
        ↓
リプライ文生成
        ↓
ハッシュタグ・拡散案生成
        ↓
接続済みConnectorへ実行要求
        ↓
投稿・結果記録
```

外部公開は接続済みサービスの権限・利用規約・API仕様の範囲で実行する。

## 3. Android画面

### Home

- Zero Agentロゴ/タイトル
- Agent状態
- OpenRouter接続状態
- 選択モデル
- 接続サービス一覧
- 大きな「企画生成・拡散」ボタン
- 実行進捗
- 最新の企画
- 最新投稿結果
- エラー表示

```text
ZERO AGENT

Creator Agent     READY
OpenRouter         CONNECTED
Model              selected model

Services
X                   CONNECTED / NOT CONNECTED
SeaArt              CONNECTED / NOT CONNECTED

┌────────────────────────┐
│    企画生成・拡散       │
└────────────────────────┘
```

### Services

各サービスについてアカウント一覧と接続状態を表示する。

```text
X
  Creator-X       CONNECTED
  Research-X      NOT CONNECTED

[ログイン]
[人間がブラウザでログイン]
[新規アカウント作成]
```

### Settings

- OpenRouter API Key
- OpenRouter Model
- Agent objective
- Connector設定
- 自動公開 ON/OFF
- 実行前確認 ON/OFF
- 認証方法

APIキー、パスワード、Cookie、Secret等をAIプロンプトや通常ログへ出力しない。

## 4. OpenRouter

OpenRouter Chat Completions互換APIを利用する。AIには構造化JSONを返させ、アプリ側で検証する。

```json
{
  "plan": {
    "title": "企画タイトル",
    "concept": "企画概要",
    "target": "対象ユーザー",
    "reason": "企画理由"
  },
  "content": {
    "image_prompt": "画像生成用プロンプト",
    "free_content_description": "無料公開部分",
    "paid_content_description": "有料部分の企画"
  },
  "distribution": {
    "post_text": "X投稿本文",
    "reply_text": "リプライ本文",
    "hashtags": ["tag1", "tag2"],
    "call_to_action": "CTA"
  }
}
```

## 5. AgentOrchestrator

```text
run()
 ├── planner.plan()
 ├── policy.validatePlan()
 ├── accountManager.ensureRequiredAccounts()
 ├── contentGenerator.generate()
 ├── policy.validateContent()
 ├── connectorManager.executeGeneration()
 ├── publisher.publish()
 ├── distributor.distribute()
 └── actionLog.save()
```

途中で失敗した場合は後続処理を停止し、どの段階で失敗したか表示する。二重タップによる二重投稿を防ぐため、実行中はボタンを無効化し、runIdによる冪等性制御を行う。

## 6. Connector

```kotlin
interface ServiceConnector {
    val id: String
    suspend fun isConnected(accountId: String): Boolean
    suspend fun capabilities(): Set<String>
    suspend fun execute(action: String, payload: String): ConnectorResult
}
```

共通Capability候補：

```text
authenticate
oauth_login
browser_login
manual_login
create_account
check_session
logout
```

X：

```text
create_post
reply_to_post
read_own_metrics
```

Image Generator：

```text
generate_image
get_generation_status
get_asset
```

SeaArtはImage Generator Connectorの実装候補とする。公式APIまたはサービスが許可する自動化手段が利用できる場合のみ自動実行し、利用できない場合は生成プロンプト表示や人間操作へフォールバックする。

## 7. Account Manager

サービスごとに複数アカウントを登録可能にする。

```text
ServiceAccount
├── id
├── serviceId
├── displayName
├── externalAccountId
├── authMethod
├── status
├── capabilities
├── assignedAgentId
└── lastSessionCheck
```

例：

```text
X
├── Creator-X  → Creator Agent
├── Developer-X → Developer Agent
└── Research-X → Research Agent
```

## 8. 認証方式

Connectorごとに利用可能な方式を宣言する。

### OAuth / API認証

公式OAuth/APIが利用可能なら優先する。

### 自動ブラウザログイン

サービスが許可する範囲でブラウザ操作を利用する。認証情報はSecret StoreからConnectorへ渡し、AIモデルには渡さない。

### 手動ブラウザログイン

Androidからログインページを開き、人間が操作する。ログイン完了後にConnectorがセッション状態を確認する。

### Human Handoff

CAPTCHA、SMS/メール確認、本人確認、重要な規約同意など人間による操作が必要になった場合、自動化を停止する。

```text
RUNNING
 ↓
HUMAN_ACTION_REQUIRED
 ↓
「Xでメール認証が必要です」
 ↓
[ブラウザを開く]
 ↓
人間が完了
 ↓
[完了を確認]
 ↓
SESSION CHECK
 ↓
CONNECTED
 ↓
RESUME
```

CAPTCHAやサービス側の制限を回避する機能は実装しない。

## 9. アカウント作成

Connectorが `create_account` をサポートする場合、Zero Agentからアカウント作成フローを開始できる。

```text
Agent
 ↓
「画像生成サービスが必要」
 ↓
Account Manager
 ↓
既存アカウント検索
 ↓
なし
 ↓
アカウント作成開始
 ↓
入力可能な項目を処理
 ↓
人間操作が必要？
 ├─ NO → 続行
 └─ YES → HUMAN_ACTION_REQUIRED
 ↓
接続確認
 ↓
AgentへCapability提供
```

新しい外部アカウントを勝手に大量作成する用途には使わず、作成前の人間承認を設定可能にする。

## 10. Credential / Session管理

```text
AI Agent
   │
   │ account = Creator-X / status = CONNECTED
   ▼
Connector
   │
   ▼
Credential Store
   │
   ▼
External Service
```

AIモデルに見せる情報：

```text
service = X
account = Creator-X
status = CONNECTED
capabilities = [create_post, reply_to_post]
```

見せない情報：

- パスワード
- OAuth client secret
- API secret
- Session Cookie
- refresh token
- Bitcoin秘密鍵

AndroidではKeystoreを利用する。本格運用ではサーバー側Secret Vaultへの移行を想定する。

## 11. 公開モード

### Preview Mode

企画・投稿案・画像生成案まで生成し、人間が確認して公開する。初期値はこちら。

### Auto Publish Mode

接続済みConnectorについてPolicy Engineを通過した処理を自動公開する。

## 12. 拡散

拡散はサービス規約に沿った正規機能で行う。

- 投稿文最適化
- 適切なハッシュタグ生成
- 自分の投稿への補足リプライ
- 投稿時間の提案
- 許可された再投稿/クロスポスト
- 投稿成果の計測

大量リプライ、大量DM、無関係なユーザーへの自動投稿は実装しない。

## 13. Android技術構成

```text
Kotlin
Jetpack Compose
MVVM
Coroutines
Retrofit / OkHttp
Room
Android Keystore
Custom Tabs / OAuth
```

```text
app/
├── ui/
│   ├── home/
│   ├── services/
│   ├── account/
│   └── settings/
├── agent/
│   ├── AgentOrchestrator.kt
│   ├── Planner.kt
│   └── PolicyEngine.kt
├── accounts/
│   ├── AccountManager.kt
│   ├── ServiceAccount.kt
│   └── HumanHandoffManager.kt
├── openrouter/
├── connectors/
│   ├── ServiceConnector.kt
│   ├── ConnectorManager.kt
│   ├── x/
│   └── image/
├── data/
└── security/
    └── SecretStore.kt
```

## 14. 実行状態

```text
IDLE
PLANNING
AUTHENTICATING
HUMAN_ACTION_REQUIRED
GENERATING
POLICY_CHECK
PUBLISHING
DISTRIBUTING
COMPLETED
FAILED
```

UIはStateFlowで状態を監視する。

## 15. MVP完成条件

1. Androidアプリが起動する
2. OpenRouter API Keyを安全に登録できる
3. OpenRouterモデルを指定できる
4. ボタン1つで企画を生成できる
5. 投稿本文・リプライ・ハッシュタグ・画像生成プロンプトを生成できる
6. Connectorを追加可能
7. サービスごとにアカウントを登録できる
8. OAuth/API・自動・手動ログインをConnectorに応じて選択できる
9. 対応サービスではアカウント作成フローを開始できる
10. CAPTCHA/SMS/メール/本人確認等ではHuman Handoffできる
11. 複数アカウントをAgentへ割り当てられる
12. Preview Modeで公開前確認できる
13. Auto Publish Modeへ切り替えられる
14. 実行履歴を保存できる
15. 秘密情報がAIモデルや通常ログへ露出しない

## 16. 次段階

- X公式連携
- SeaArt等の画像生成Connector
- 有料コンテンツ販売Connector
- Bitcoin Wallet
- 投稿Metrics取得
- AIによる成果分析
- Creator / Developer / Research Agent切替
- 定期自律実行
- 収益ダッシュボード

最終的には、AIが必要なCapabilityを判断し、既存アカウントを利用、必要なら新規アカウント作成フローを開始し、人間操作が必要な部分だけ引き継ぎを要求して処理を再開できる構成とする。