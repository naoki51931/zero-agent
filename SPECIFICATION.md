# Zero Agent — 仕様書

## 1. 概要

**Zero Agent** は、AIエージェントが初期資金ゼロから、合法かつ利用サービスの規約を守りながら、どこまで自律的な経済活動を行えるかを検証する実験プラットフォームである。

複数のAIエージェントを独立して稼働させ、それぞれに異なる収益化戦略とBitcoinウォレットを割り当てる。売上・経費・資産・活動・意思決定・成果を記録し、戦略ごとの成績を比較できるようにする。

> Zero money. Multiple agents. One experiment: how far can autonomous AI create real economic value?

---

## 2. 目的

- AIエージェントがゼロ資本から価値を生み出せるか検証する
- 複数の収益モデルを同条件で比較する
- AIによる企画・制作・公開・販売・改善のサイクルを自動化する
- Bitcoinを用いてエージェント単位の収益を計測する
- 外部サービスをConnector方式で追加可能にする
- AIの行動を監査可能なログとして残す
- 金銭操作や外部公開など重要操作には安全な権限制御を設ける

---

## 3. 初期エージェント

### 3.1 Creator Agent

AI生成コンテンツを企画・制作・公開し、有料コンテンツへの導線を構築する。

初期フロー例：

```text
市場・投稿実績分析
       ↓
コンテンツ企画
       ↓
SeaArt Connector
       ↓
画像生成
       ↓
品質・ポリシーチェック
       ↓
X Connector
       ↓
無料サンプル投稿
       ↓
投稿へのリプライ
       ↓
有料コンテンツ販売ページへの導線
       ↓
購入
       ↓
Creator Wallet
```

販売対象は、エージェントまたは運営者が販売権限を持つオリジナルコンテンツを原則とする。

### 3.2 Developer Agent

小規模なソフトウェア、Webツール、テンプレート等を企画・開発し、公開・販売する。

```text
需要調査
 ↓
企画
 ↓
実装
 ↓
テスト
 ↓
公開
 ↓
販売・寄付導線
 ↓
改善
```

### 3.3 Research Agent

公開情報を調査・整理し、独自分析を加えたレポート、データ、ガイド等を制作・販売する。

単なる転載ではなく、出典管理、独自分析、要約、比較などの付加価値を必須とする。

---

## 4. システム全体構成

```text
                  ┌─────────────────────┐
                  │     Zero Agent      │
                  │    Orchestrator     │
                  └─────────┬───────────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
          ▼                 ▼                 ▼
   Creator Agent     Developer Agent    Research Agent
          │                 │                 │
          └─────────────────┼─────────────────┘
                            │
                    Tool / Connector Layer
                            │
       ┌────────────┬───────┼────────┬────────────┐
       ▼            ▼       ▼        ▼            ▼
    SeaArt          X     GitHub   Store      Bitcoin
       │            │       │        │            │
       └────────────┴───────┴────────┴────────────┘
                            │
                     Policy Engine
                            │
                     Audit / Metrics
```

---

## 5. Agent Core

各エージェントは以下のサイクルを繰り返す。

```text
OBSERVE
  ↓
PLAN
  ↓
POLICY CHECK
  ↓
ACT
  ↓
MEASURE
  ↓
LEARN
  ↓
OBSERVE...
```

### OBSERVE

- 現在のウォレット残高
- 売上
- 経費
- 投稿実績
- CTR / CVR等の指標
- 過去のDecision Log
- 利用可能なConnector
- 承認待ちタスク

### PLAN

観測結果から次に実行する施策を決定する。

### POLICY CHECK

実行前に権限、予算、サービス規約、コンテンツルール等を確認する。

### ACT

許可されたConnectorを通じて処理を実行する。

### MEASURE

実行結果を数値化して保存する。

### LEARN

過去の施策と結果を比較し、次の計画に利用する。

---

## 6. Connector Architecture

外部サービスをハードコードせず、Connectorとして追加できる構造とする。

```text
connectors/
├── seaart/
│   ├── manifest.json
│   └── connector.*
├── x/
│   ├── manifest.json
│   └── connector.*
├── github/
│   ├── manifest.json
│   └── connector.*
├── bitcoin/
│   ├── manifest.json
│   └── connector.*
└── store/
    ├── manifest.json
    └── connector.*
```

共通概念：

```text
Connector
├── authenticate()
├── capabilities()
├── execute(action, params)
├── healthCheck()
└── revoke()
```

各Connectorは利用可能なCapabilityを宣言する。

SeaArt例：

```text
generate_image
get_generation_status
get_asset
```

X例：

```text
create_post
reply_to_post
read_metrics
read_own_posts
```

GitHub例：

```text
create_repository
read_repository
create_file
update_file
```

Bitcoin例：

```text
get_balance
create_receive_address
create_payment_request
get_transactions
request_send
```

外部サービスの操作は、公式APIまたは当該サービスが許可する自動化方法を優先し、各サービスの最新の利用規約・自動化ポリシーに従う。

---

## 7. 認証情報管理

AIモデルへパスワード、API Secret、秘密鍵等を直接渡さない。

```text
Agent
  │
  │ execute("create_post")
  ▼
Connector
  │
  ▼
Credential Vault
  │
  ▼
External Service
```

AIから見える状態は原則として以下のみとする。

```text
SeaArt   CONNECTED
X        CONNECTED
GitHub   CONNECTED
Bitcoin  CONNECTED
```

認証情報はサーバー側のSecret StoreまたはOS/クラウドの安全なCredential Storeで管理する。

---

## 8. Bitcoin Wallet

各エージェントに独立したウォレットまたは会計上独立したアカウントを割り当てる。

```text
Creator Agent   → Wallet A
Developer Agent → Wallet B
Research Agent  → Wallet C
```

### セキュリティ原則

AIモデルにはBitcoin秘密鍵を渡さない。

```text
Agent
 ↓
Payment Request
 ↓
Policy Engine
 ↓
Wallet Service
 ↓
Transaction Signing
 ↓
Bitcoin Network
```

秘密鍵はWallet Service内部のみで扱う。

### 支出制御

金額上限は設定可能とする。

例：

```text
低額       自動承認可能
中額       Policy Engineによる追加確認
高額       人間の承認必須
新規送金先 人間の承認必須
```

具体的な閾値は管理画面から設定できるようにする。

---

## 9. Policy Engine

利益最大化だけを目的にせず、安全性・合法性・規約順守を強制する。

### 禁止事項

- 詐欺
- スパム
- なりすまし
- 無断転載
- 著作権・商標権等を侵害するコンテンツ販売
- 認証情報の窃取・露出
- サービス制限の不正回避
- 市場操作
- 無許可の金融取引
- 違法な商品・サービスの販売

### 重要操作

以下は設定に応じてHuman Approvalを要求する。

- 一定額以上の支払い
- 新規サービスへの登録
- 新しい外部アカウントの作成
- 契約・有料プラン加入
- 大量投稿
- 大量メッセージ送信
- 公開コンテンツの削除
- Wallet設定変更
- Connector権限変更

---

## 10. Creator Agent — SeaArt / X MVP

Creator Agentの最初の実証機能とする。

### 10.1 接続

管理画面：

```text
Services

SeaArt     [ Connect ]
X          [ Connect ]
Bitcoin    [ Connect ]
Store      [ Connect ]
```

### 10.2 コンテンツ生成

エージェントが以下を作成する。

- テーマ
- プロンプト
- 投稿予定時刻
- 無料公開用コンテンツ
- 有料版コンテンツ
- 投稿文
- リプライ文
- 販売価格

### 10.3 投稿

```text
X Main Post
  ↓
無料サンプル
  ↓
Reply
  ↓
有料コンテンツへのリンク
```

### 10.4 学習指標

- impressions
- likes
- reposts
- replies
- link clicks
- purchases
- revenue
- conversion rate
- revenue per post

指標が取得できないサービスについては、取得可能な範囲のみ保存する。

---

## 11. Decision Log

モデルの非公開な内部推論そのものではなく、監査可能な意思決定サマリーを保存する。

例：

```json
{
  "agent": "creator",
  "observation": "Series A had a higher conversion rate than the recent average.",
  "decision": "Create three additional works in Series A.",
  "action": "seaart.generate_image",
  "expected_result": "Increase conversion rate",
  "result": "pending"
}
```

実行後：

```json
{
  "result": "success",
  "revenue_sats": 12000,
  "conversion_rate": 0.031
}
```

---

## 12. ダッシュボード

トップ画面で3エージェントを比較する。

```text
ZERO AGENT
────────────────────────
Experiment Day: 17

Total Assets       281,300 sats
Total Revenue      330,100 sats
Total Expenses      48,800 sats

Creator
Assets             192,100 sats
Revenue            220,000 sats

Developer
Assets              73,100 sats
Revenue             89,000 sats

Research
Assets              16,100 sats
Revenue             21,100 sats
────────────────────────
```

各Agent詳細画面：

- 現在資産
- BTC / sats表示
- 法定通貨参考額
- 累計売上
- 累計経費
- 純利益
- 現在の作業
- 最近のAction
- Decision Log
- Connector状態
- 承認待ち操作
- 日別売上グラフ
- 投稿別成果

---

## 13. データモデル案

### agents

```text
id
name
type
status
objective
created_at
```

### wallets

```text
id
agent_id
provider
balance_sats
created_at
```

### transactions

```text
id
agent_id
wallet_id
type
amount_sats
fee_sats
status
external_txid
created_at
```

### connectors

```text
id
service
status
capabilities
created_at
updated_at
```

### actions

```text
id
agent_id
connector_id
action
parameters
status
started_at
finished_at
```

### decisions

```text
id
agent_id
observation
decision
expected_result
actual_result
created_at
```

### content

```text
id
agent_id
type
title
asset_location
status
published_at
```

### metrics

```text
id
agent_id
content_id
metric
value
measured_at
```

### approvals

```text
id
agent_id
action_id
reason
status
requested_at
resolved_at
```

---

## 14. API案

```text
GET    /api/agents
GET    /api/agents/{id}
GET    /api/agents/{id}/metrics
GET    /api/agents/{id}/decisions
GET    /api/agents/{id}/actions

GET    /api/connectors
POST   /api/connectors/{service}/connect
POST   /api/connectors/{service}/disconnect

GET    /api/wallets
GET    /api/wallets/{id}/transactions

GET    /api/approvals
POST   /api/approvals/{id}/approve
POST   /api/approvals/{id}/reject

POST   /api/agents/{id}/start
POST   /api/agents/{id}/pause
POST   /api/agents/{id}/run-once
```

---

## 15. 推奨ディレクトリ構成

```text
zero-agent/
├── README.md
├── SPECIFICATION.md
├── backend/
│   ├── agents/
│   │   ├── creator/
│   │   ├── developer/
│   │   └── research/
│   ├── connectors/
│   │   ├── seaart/
│   │   ├── x/
│   │   ├── github/
│   │   ├── bitcoin/
│   │   └── store/
│   ├── policy/
│   ├── wallet/
│   ├── approvals/
│   ├── metrics/
│   ├── api/
│   └── database/
├── frontend/
│   ├── dashboard/
│   ├── agents/
│   ├── connectors/
│   ├── approvals/
│   └── settings/
├── tests/
└── docs/
```

---

## 16. MVP

### Phase 1 — Core

- Zero Agent Web UI
- 3エージェント登録
- Agent start / pause
- Action Log
- Decision Log
- SQLite/PostgreSQL保存
- 基本Policy Engine

### Phase 2 — Creator

- SeaArt Connector
- X Connector
- コンテンツ生成ワークフロー
- 投稿ワークフロー
- 投稿成果記録
- Human Approval

### Phase 3 — Bitcoin

- エージェント別ウォレット
- 受取アドレス
- 入金検知
- sats会計
- Transaction Log
- 支出Policy
- Human Approval付き送金

### Phase 4 — Economy Experiment

- Creator / Developer / Researchを同時稼働
- 日次損益
- エージェント比較
- 30日等の実験期間設定
- KPIランキング
- 実験結果エクスポート

### Phase 5 — Extensibility

- Connector SDK
- Connector manifest
- 新規サービス追加UI
- Agentテンプレート
- 独自Agent作成

---

## 17. 実験ルール

初期設定例：

```text
Initial capital: 0 JPY / 0 BTC
Agents: 3
Experiment period: 30 days
Independent accounting: enabled
Human approval: enabled
Illegal activity: prohibited
Spam: prohibited
Credential exposure: prohibited
Copyright infringement: prohibited
```

比較KPI：

1. 純利益
2. 売上
3. 初売上までの日数
4. 顧客数
5. リピート率
6. Conversion Rate
7. Agent稼働コスト
8. ROI（外部から追加資金を投入した場合）

---

## 18. 非機能要件

### Security

- Secretをソースコードへ保存しない
- `.env` をGit管理しない
- 秘密鍵をLLMへ渡さない
- Connectorごとに最小権限を設定する
- 金銭操作をAudit Logへ保存する
- 重要操作はHuman Approvalに対応する

### Reliability

- Connector失敗時のRetry
- Rate Limit対応
- Idempotency Key対応
- 投稿・決済の二重実行防止
- AgentのEmergency Stop

### Observability

- structured logging
- action tracing
- connector health
- token/API cost tracking
- revenue/expense tracking

---

## 19. 成功条件

MVPの成功条件は、単に利益を出すことではない。

以下を満たすことを最初の成功条件とする。

- 3種類のAgentを独立稼働できる
- Connectorを追加できる
- Creator Agentが生成→公開→販売導線まで実行できる
- 全Actionを追跡できる
- Bitcoin入金をAgent別に集計できる
- AIへ秘密鍵・サービスパスワードを公開しない
- 支払い等をPolicy Engineで制御できる
- 0から最初の売上までの過程を再現・分析できる

---

## 20. 将来構想

Zero Agentを特定サービス専用にせず、経済活動を行うAIエージェントの実験基盤へ発展させる。

```text
Agent
  +
Skills
  +
Connectors
  +
Wallet
  +
Policy
  +
Memory
  +
Metrics
```

新しいAgentとConnectorを組み合わせることで、コンテンツ制作、ソフトウェア開発、リサーチなど異なる経済活動を同じ基盤上で比較できることを目標とする。
