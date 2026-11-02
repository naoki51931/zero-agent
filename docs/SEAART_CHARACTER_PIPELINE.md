# X調査 → AIキャラ選択 → SeaArt画像 → 投稿待ち

## 実装済み
- Xスクリーンショットから抽出した、いいね数とテキストのペアを読み込み。データが空ならエラーを明示。
- 関連性・観測いいね数で候補を最大25件に絞り、OpenRouterのAIがオリジナルのアニメキャラ案・SeaArt用プロンプト・ネガティブプロンプト・投稿文下書きを提案。
- 提案をアプリ専用ストレージのJSONに保存。
- SeaArtをブラウザで開き、プロンプトをクリップボードにコピー。
- SeaArtで生成した画像をユーザーが選択し、アプリ専用ストレージへコピー。状態を ready_for_post_review に変更。
- 投稿操作は未実装。公開投稿は実行しない。

## 未実装・制約
- SeaArt上の画像生成開始・完了確認・画像自動取得は未実装。公式SeaArt CLI/MCPはデスクトップ向けであり、Androidアプリからの安全な認証付きAPI接続仕様は未確定。
- Xいいね数は未検証の推定値。伸びる保証はない。
- 外部AIへのデータ送信にはOpenRouter API料金が発生する場合がある。
- 画像はユーザーが選択して初めてアプリへ取り込まれる。
- 次工程はユーザーによる投稿レビュー・承認を挟む設計にする。
- 実機ビルドと操作検証が必要。

## SeaArt Bridge (experimental)

公式 SeaArt CLI: https://github.com/seaartpublic/cli

1. 信頼できるLinux/macOS/Windowsホストに公式CLIをインストールし、seaart login で認証する。
2. seaart models list で画像モデルIDとバージョンIDを確認する。
3. Python 3.10+で tools/seaart_bridge.py を起動する。環境変数 ZERO_AGENT_BRIDGE_TOKEN（24文字以上のランダム値）, SEAART_MODEL_ID, SEAART_MODEL_VER_ID を設定する。
4. HTTPSリバースプロキシ経由でBridgeを公開し、アクセス制御を設定する。CLI認証済みホストをインターネットに無制限公開しないこと。
5. Zero Agentの「SeaArt画像生成・投稿準備」画面にHTTPS Bridge URLとトークンを入力し保存。
6. AI生成案の「SeaArtで自動画像生成・保存」を押すと1画像を生成して端末内の投稿待ちキューへ保存する。

SeaArt CLIの出力形式はリリースで変化する可能性がある。BridgeはHTTPS画像URLを取得できなければエラーを返す。SeaArtのモデルによって費用がかかる場合がある。Bridge自体はSeaArtへのログイン処理をしない。投稿の自動化は未実装。
