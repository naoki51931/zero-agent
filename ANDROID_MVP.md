# Zero Agent — Android MVP

## 1. 目的

Androidアプリからボタン1つで、OpenRouter経由のAIが「企画 → 投稿案生成 → 拡散用コンテンツ生成 → 公開処理」を実行できるMVPを作る。

Creator Agentを中心に実装し、外部サービスはConnector方式で追加可能にする。AI自動操作と人間操作を安全に切り替えられるHuman Takeoverを標準機能とする。

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
投稿本文・リプライ・拡散案生成
        ↓
Policy Check
        ↓
接続済みConnector
        ↓
投稿・結果記録
```

外部公開は各サービスの利用規約・API仕様・許可された自動化範囲に従う。

## 3. Android画面

### Home

- Agent状態
- OpenRouter接続状態
- 選択モデル
- 接続サービス
- 大きな「企画生成・拡散」ボタン
- 「人間が操作」ボタン
- 実行進捗
- 最新企画/投稿結果

```text
ZERO AGENT

Creator Agent      READY
OpenRouter          CONNECTED

X                   CONNECTED
SeaArt              CONNECTED

[      企画生成・拡散      ]
[       人間が操作         ]
```

### Services

```text
X / Creator-X
状態: CONNECTED
操作モード: HYBRID

[AIに任せる]
[人間が操作]
[ログイン]
[新規アカウント作成]
```

## 4. OpenRouter

OpenRouter Chat Completions互換APIを利用し、企画、画像プロンプト、投稿本文、リプライ、ハッシュタグ、CTA等を構造化JSONとして生成する。APIキー等の秘密情報はモデルへ渡さない。

## 5. AgentOrchestrator

```text
run()
 ├── planner.plan()
 ├── policy.validatePlan()
 ├── accountManager.ensureRequiredAccounts()
 ├── takeoverManager.checkMode()
 ├── contentGenerator.generate()
 ├── policy.validateContent()
 ├── connectorManager.executeGeneration()
 ├── publisher.publish()
 ├── distributor.distribute()
 └── actionLog.save()
```

runIdによる冪等性制御を行い、AIと人間が同時に同じアカウントを操作しないようロックする。

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
human_takeover
resume_agent
```

X: `create_post`, `reply_to_post`, `read_own_metrics`

Image Generator: `generate_image`, `get_generation_status`, `get_asset`

公式APIまたはサービスが許可する自動化方法を優先する。

## 7. Account Manager

複数アカウントを登録しAgentへ割り当て可能にする。

```text
ServiceAccount
├── id
├── serviceId
├── displayName
├── externalAccountId
├── authMethod
├── operationMode
├── status
├── capabilities
├── assignedAgentId
└── lastSessionCheck
```

## 8. 操作モード

サービス/アカウント単位で3モードを選べる。

### HUMAN

外部サービスの操作は人間が行う。AIは企画、文章、画像プロンプト等を準備する。

### AGENT

サービスが許可するAPI/自動化範囲でAgentが操作する。人間がTakeoverした時点で即時停止する。

### HYBRID

通常はAgentが処理し、必要な場面またはユーザーの任意操作で人間へ切り替える。初期推奨モード。

## 9. Human Takeover

ユーザーはいつでも「人間が操作」を押せる。

```text
AGENT_RUNNING
      ↓
[人間が操作]
      ↓
PAUSING
      ↓
Connectorの自動操作停止
      ↓
操作ロック取得
      ↓
HUMAN_CONTROL
      ↓
対象サービスをブラウザで開く
      ↓
人間が通常操作
      ↓
[AIに戻す]
      ↓
SESSION CHECK
      ↓
状態再取得
      ↓
AGENT_READY
```

人間操作中はAgentから同じアカウントへの投稿、クリック、ログイン、更新等を禁止する。

### 自動Handoff

以下では自動的に人間へ引き継げる。

- CAPTCHA
- SMS/メール確認
- 本人確認
- 規約への重要な同意
- セキュリティ警告
- サービス側が人間操作を要求した場合
- 自動化が許可されているか判断できない場合
- ログイン失敗が連続した場合

CAPTCHAやサービス制限を自動回避しない。

## 10. 人間操作の目的

Human Takeoverは「人間に見せかけて自動化を隠す」ためには使用しない。サービス規約を守りながら、人間による通常利用が必要な処理、本人確認、品質確認、編集、投稿確認などを実際に人間が担当するための機能とする。

サービスが自動操作を禁止している場合、その操作はHUMANモードに固定できる。

## 11. アカウント作成

Connectorが対応する場合はアカウント作成フローを開始できる。CAPTCHA、メール/SMS認証、本人確認等では `HUMAN_ACTION_REQUIRED` へ遷移する。大量アカウント作成は行わない。

## 12. Credential / Session管理

AIモデルにはアカウント名、接続状態、Capabilityのみ渡す。

パスワード、OAuth Secret、API Secret、Cookie、refresh token等はAndroid Keystore/Secret Storeで管理する。

## 13. 公開モード

### Preview Mode
企画・投稿案・画像生成案を生成し、人間が確認後に公開する。

### Auto Publish Mode
Policy Engineとサービス規約の範囲内で接続済みConnectorから公開する。

## 14. 拡散

- 投稿文最適化
- 適切なハッシュタグ生成
- 自分の投稿への補足リプライ
- 投稿時間提案
- 許可された再投稿/クロスポスト
- 成果計測

大量リプライ、大量DM、無関係なユーザーへの自動投稿は行わない。

## 15. Android技術構成

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
│   └── takeover/
├── agent/
│   ├── AgentOrchestrator.kt
│   └── PolicyEngine.kt
├── accounts/
│   ├── AccountManager.kt
│   ├── HumanHandoffManager.kt
│   └── HumanTakeoverManager.kt
├── openrouter/
├── connectors/
├── data/
└── security/
```

## 16. 実行状態

```text
IDLE
PLANNING
AUTHENTICATING
AGENT_RUNNING
PAUSING_FOR_HUMAN
HUMAN_ACTION_REQUIRED
HUMAN_CONTROL
RESUMING_AGENT
GENERATING
POLICY_CHECK
PUBLISHING
DISTRIBUTING
COMPLETED
FAILED
```

## 17. MVP完成条件

1. Androidアプリ起動
2. OpenRouter接続
3. ワンタップ企画生成
4. 投稿/画像生成案生成
5. Connector追加可能
6. 複数アカウント管理
7. HUMAN / AGENT / HYBRID切替
8. 任意タイミングでHuman Takeover
9. Human Control中のAgent操作停止
10. 「AIに戻す」で状態確認後に再開
11. CAPTCHA/SMS/メール/本人確認でHuman Handoff
12. Preview / Auto Publish切替
13. 実行履歴保存
14. 秘密情報をAIへ露出しない
15. サービスが自動化を禁止する操作はHUMAN固定可能

## 18. 次段階

- X公式連携
- SeaArt等の画像生成Connector
- 有料コンテンツ販売Connector
- Bitcoin Wallet
- Metrics取得
- AIによる成果分析
- 3 Agent切替
- 定期自律実行
- 収益ダッシュボード

最終的には「AIが得意な企画・生成・分析」と「人間が担当すべきログイン・本人確認・品質判断・規約上必要な操作」を途中で自由に切り替えられるHuman-in-the-loop型Zero Agentとする。