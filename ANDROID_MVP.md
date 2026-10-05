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

例：

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

Progress
✓ 企画生成
✓ 投稿文生成
○ コンテンツ生成
○ 公開
○ 拡散
```

### Settings

- OpenRouter API Key
- OpenRouter Model
- Agent objective
- X Connector設定
- SeaArt Connector設定
- 自動公開 ON/OFF
- 実行前確認 ON/OFF

APIキー等の秘密情報はログやAIプロンプトへ出力しない。

## 4. OpenRouter

OpenRouter Chat Completions互換APIを利用する。

AIには構造化JSONを返させる。

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

JSON Schemaまたはアプリ側バリデーションを使い、不正な応答は公開しない。

## 5. AgentOrchestrator

Android側にワンタップ処理を統括する `AgentOrchestrator` を置く。

```text
run()
 ├── planner.plan()
 ├── policy.validatePlan()
 ├── contentGenerator.generate()
 ├── policy.validateContent()
 ├── connectorManager.executeGeneration()
 ├── publisher.publish()
 ├── distributor.distribute()
 └── actionLog.save()
```

途中で失敗した場合は後続処理を停止し、どの段階で失敗したか表示する。

二重タップによる二重投稿を防ぐため、実行中はボタンを無効化し、runIdによる冪等性制御を行う。

## 6. Connector

Androidアプリ本体にSeaArtやX固有ロジックを密結合させない。

```kotlin
interface ServiceConnector {
    val id: String
    suspend fun isConnected(): Boolean
    suspend fun capabilities(): Set<String>
    suspend fun execute(action: String, payload: String): ConnectorResult
}
```

想定Capability：

### X

```text
create_post
reply_to_post
read_own_metrics
```

### Image Generator

```text
generate_image
get_generation_status
get_asset
```

SeaArtはImage Generator Connectorの実装候補とする。公式APIまたはサービスが許可する自動化手段が利用できる場合のみ自動実行し、利用できない場合は生成プロンプトを表示して手動処理へフォールバックする。

## 7. 公開モード

事故防止のため2モードを用意する。

### Preview Mode

ボタン1つで企画・投稿案・画像生成案まで作り、ユーザーが確認後に公開する。

### Auto Publish Mode

接続済みConnectorについて、Policy Engineを通過した処理を自動公開する。

初期値はPreview Modeとする。

## 8. 拡散

「拡散」はスパム送信ではなく、以下の正規機能で行う。

- 投稿文最適化
- 適切なハッシュタグ生成
- 自分の投稿への補足リプライ
- 投稿時間の提案
- 接続サービスで許可された再投稿/クロスポスト
- 投稿成果の計測と次回企画への反映

大量リプライ、大量DM、無関係なユーザーへの自動投稿は実装しない。

## 9. Android技術構成

```text
Kotlin
Jetpack Compose
MVVM
Coroutines
Retrofit / OkHttp
Room
Android Keystore
```

推奨パッケージ：

```text
app/
├── ui/
│   ├── home/
│   └── settings/
├── agent/
│   ├── AgentOrchestrator.kt
│   ├── Planner.kt
│   └── PolicyEngine.kt
├── openrouter/
│   ├── OpenRouterApi.kt
│   ├── OpenRouterClient.kt
│   └── models/
├── connectors/
│   ├── ServiceConnector.kt
│   ├── ConnectorManager.kt
│   ├── x/
│   └── image/
├── data/
│   ├── db/
│   └── repository/
└── security/
    └── SecretStore.kt
```

## 10. OpenRouter Key

MVPではユーザー自身のOpenRouter API Keyを設定画面から登録できるようにする。

保存時はAndroid Keystoreを利用した暗号化ストレージを使用する。API KeyをGitHubへコミットしない。

本番サービス化する場合は、APIキーをAPK内に埋め込まず、Zero Agent Backendを介してOpenRouterを利用する構成を推奨する。

## 11. 実行状態

```text
IDLE
PLANNING
GENERATING
POLICY_CHECK
PUBLISHING
DISTRIBUTING
COMPLETED
FAILED
```

UIはStateFlowで状態を監視する。

## 12. MVP完成条件

1. Androidアプリが起動する
2. OpenRouter API Keyを登録できる
3. OpenRouterモデルを指定できる
4. ボタン1つで企画を生成できる
5. 投稿本文・リプライ・ハッシュタグ・画像生成プロンプトを生成できる
6. 結果をAndroid画面で確認できる
7. 実行履歴をRoomへ保存できる
8. Connectorを追加可能な構造になっている
9. Preview Modeで公開前確認ができる
10. 対応Connector接続時は公開処理へ進める

## 13. 次段階

MVP完成後に以下を追加する。

- X公式連携
- SeaArt等の画像生成Connector
- 有料コンテンツ販売Connector
- Bitcoin Wallet
- 投稿Metrics取得
- AIによる成果分析
- Creator / Developer / Research Agent切替
- 定期自律実行
- 収益ダッシュボード

最終的には「企画生成・拡散」ボタンを、Zero Agentの1サイクル `OBSERVE → PLAN → ACT → MEASURE → LEARN` を開始するトリガーとして扱う。