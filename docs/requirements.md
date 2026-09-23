# Hyperliquid Tax Tracker JP — Requirements

## 1. 概要

### 1.1 システム名

**Hyperliquid Tax Tracker JP**

Repository:

`hyperliquid-tax-tracker-jp`

### 1.2 目的

Hyperliquidを利用する日本在住ユーザー向けに、国内取引所からHyperliquidまでの資金移動とHyperliquid上の取引履歴を整理し、日本の税務処理に必要な取引履歴・取得価額・損益情報を確認できるようにする。

本システムは税務申告を代行するものではなく、**取引履歴・取得価額・損益情報の整理支援ツール**とする。

---

## 2. MVP対象ユーザー

v1では以下のユーザーを対象とする。

* 日本在住の個人
* bitbankを利用している
* bitbankでSOLを購入する
* Phantomを利用する
* Solana経由でSOLをHyperliquidへ入金する
* Hyperliquid上でSOLをUSDCへ交換する
* USDCを利用してPerpetual取引を行う
* 日本円ベースで年間損益を確認したい

---

## 3. MVP対象資金フロー

v1で対応する入金ルートは以下のみとする。

```text
JPY
 ↓
bitbank
 ↓ SOL購入・出庫
SOL
 ↓
Phantom / Solana
 ↓
Hyperliquid Solana Deposit
```

Hyperliquid上の取引対象は以下とする。

```text
SOL → USDC Spot
USDC → Perpetual
```

Perpetualの取引銘柄はv1では限定しない。例えば`BTC-PERP`や`ETH-PERP`を取引していても、これはBTCまたはETHを入金資産としてサポートすることを意味しない。

v1の入金資産・入金NetworkはSOL/Solanaに限定する。

---

## 4. 機能要件

### 4.1 bitbank履歴取込

ユーザーはbitbankの取引履歴をCSV Importできる。

取得対象:

* SOL購入
* SOL出庫
* bitbank購入Fee
* bitbank出庫Fee
* 日時
* 数量
* 日本円金額

実際のCSVカラム名、数量の意味、Feeの控除方法は実データ確認後に確定する。

---

### 4.2 SOL Wallet登録

ユーザーはPhantomで使用しているSolana Wallet Addressを登録できる。

Private Key、Seed Phrase、署名秘密情報は取得・保存しない。

---

### 4.3 Hyperliquid Account登録

ユーザーはHyperliquid Account Addressを登録できる。

v1では`Unified Account`をサポート対象とする。

`Standard Account`および`Portfolio Margin`はMVP対象外とするが、Account Modeを拡張可能な構造で保持する。

取引権限、出金権限、Private Keyは必要としない。

---

### 4.4 Solana Transaction取得

登録されたSolana Wallet Addressについて、必要なTransactionを取得する。

取得対象:

* Transaction Signature
* Timestamp
* From
* To
* Asset
* `grossAmount`
* `netAmount`
* `feeAmount`
* Network Fee
* Status

複数Transfer、失敗Transaction、Timestamp欠損などの原データも保持する。

---

### 4.5 Hyperliquid履歴取得

以下を相互に混同せず、別データとして取得・保持する。

* Fill
* `closedPnl`
* Fee
* Funding
* Ledger Update
* Deposit
* Withdrawal
* Spot Metadata

APIから取得した`closedPnl`にFeeやFundingが含まれると仮定してはならない。

---

### 4.6 Transfer Matching

以下の資金移動を照合する。

```text
bitbank → Phantom
Phantom → Hyperliquid Solana Deposit
```

Transferでは、少なくとも以下を区別できること。

* `grossAmount`
* `netAmount`
* `feeAmount`

Match Status:

```text
MATCHED
POSSIBLE
UNMATCHED
REJECTED
```

一つのOutgoing Transactionを複数のIncoming Transactionへ確定Matchしてはならない。

具体的な時間窓・数量許容差は、bitbank CSVおよびSolana実データを確認した後に確定する。根拠のない固定値を仕様として置かない。

自己資金移動と判断できる場合は、売買・交換とは分離して扱う。判断できない場合はTransactionまたはTax Eventを`NEEDS_REVIEW`とする。

---

### 4.7 SOL取得価額設定

前年末からSOLを保有しているユーザーに対応するため、以下をユーザー入力可能とする。

* 前年末SOL数量
* 前年末SOL簿価

Cost Basis Method:

```text
TOTAL_AVERAGE
MOVING_AVERAGE
UNKNOWN
```

`UNKNOWN`の場合、確定扱いのTax Reportを生成してはならない。評価方法の届出状況や変更をシステムが推測してはならない。

---

### 4.8 税務損益整理

以下を日本円ベースで整理する。

* SOL取得価額
* SOL → USDCによるSOL譲渡損益
* Perpetual `closedPnl`
* Funding Received / Paid
* bitbank購入Fee
* bitbank出庫Fee
* Solana Network Fee
* Hyperliquid Spot Fee
* Hyperliquid Perpetual Fee

Feeの税務上の扱いを確定できない場合は、自動計算せず`NEEDS_REVIEW`とできる。

具体的な税務ルールは`tax-spec.md`で定義し、未確定の税務解釈は断定しない。

---

### 4.9 Timeline

資金移動・Spot・Perpetual・Funding・Feeを時系列で表示する。

例:

```text
09/01 bitbank
100,000 JPY → SOL

09/01 Transfer
bitbank → Phantom

09/02 Transfer
Phantom → Hyperliquid

09/02 Hyperliquid Spot
SOL → USDC

09/03 BTC-PERP
closedPnl +25 USDC

09/03 Funding
-0.8 USDC
```

上記の`BTC-PERP`はPerpetual取引銘柄の例であり、BTC入金を意味しない。

---

### 4.10 Dashboard

対象年について以下を表示する。

* SOL Exchange PnL
* Perpetual Realized PnL
* Funding Received
* Funding Paid
* Fees
* Calculated PnL
* Needs Review件数
* Dataset別Data Coverage
* Cost Basis Method

Cost Basis Methodが`UNKNOWN`、必要Datasetが`PARTIAL`または`FAILED`、または未解決のCalculation Errorがある場合、確定値として表示してはならない。

---

### 4.11 要確認取引・状態

以下を別概念として管理する。

* Transaction Classification
* Tax Event Classification
* Data Coverage Error
* Calculation Error

Transaction Classificationの手動選択肢:

* Swap
* Transfer
* Deposit
* Withdrawal
* Fee
* Ignore
* Other

手動修正履歴を保持し、修正後は必要な範囲を再計算する。

`Missing Price`や`Partial History`はTransaction Typeとして扱わない。

---

### 4.12 CSV Export

以下をCSV出力できる。

* 年間集計
* 取引明細
* Data Coverage
* NEEDS_REVIEW項目

---

## 5. Data Coverage

対象年度について、Dataset単位で必要期間のデータ取得状況を確認する。

Dataset:

```text
BITBANK_TRADES
BITBANK_WITHDRAWALS
SOLANA_TRANSACTIONS
HYPERLIQUID_FILLS
HYPERLIQUID_FUNDING
HYPERLIQUID_LEDGER
HYPERLIQUID_SPOT_METADATA
PRICE_DATA
```

Status:

```text
COMPLETE
PARTIAL
FAILED
```

必要Datasetに`PARTIAL`または`FAILED`がある場合、完全な年間損益であるかのように表示してはならない。

---

## 6. 非機能要件

### 6.1 セキュリティ

以下は保存しない。

* Private Key
* Seed Phrase
* Wallet署名秘密情報
* bitbankログインパスワード
* Hyperliquid取引用秘密情報

### 6.2 監査可能性

Calculation Runから以下へ遡れること。

```text
Tax Summary
↓
Calculation Run
↓
Tax Event / Cost Basis Calculation
↓
Normalized Transaction
↓
Raw Data / Import Batch
```

### 6.3 再計算可能性

以下が変更された場合に、同じRaw Dataから再計算できること。

* JPY Rate
* Tax Classification
* Cost Basis Method
* Opening Balance
* User Correction
* Tax Rule Version
* Normalization Version

---

## 7. MVP対象外

v1では以下を対象外とする。

* BTC / ETH等の入金
* bitbank以外の国内取引所
* Ethereum / Arbitrum経由USDC入金
* Standard Account
* Portfolio Margin
* Hyperliquid上の注文作成
* Wallet署名
* 自動売買
* Grid Trading
* NFT
* Airdrop
* HYPE Staking
* Vault
* HIP-3固有対応
* 複雑なDeFi
* e-Tax連携
* 税務申告代行
* 法人税
* 日本国外税制

Perpetualの取引銘柄自体は、BTC、ETH等を含めてv1で限定しない。ただし、それらを入金資産として扱わない。

---

## 8. 将来拡張

### 入金資産・Network

* BTC / Bitcoin
* ETH / Ethereum
* USDC / Arbitrum

### Hyperliquid Account Mode

* Standard Account
* Portfolio Margin

### Hyperliquid

* Spot全般
* HYPE
* Staking
* Vault
* HIP-3

### Report

* PDF Report
* 税理士共有用Report
* 確定申告用集計資料

---

## 9. 関連仕様

* `tax-spec.md`: 税務イベント、取得価額、損益整理のルール
* `data-source-spec.md`: 外部データソースとAPI契約
* `implementation-spec.md`: 内部モデル、Coverage、Calculation Run、処理設計
* `tasks.md`: 実装StepとDone条件

---

## 10. MVP完了条件

以下をすべて満たした状態をv1完成とする。

* bitbank CSVをImportできる
* SOL購入・出庫・Feeを取得できる
* 前年末SOL数量・簿価を入力できる
* Cost Basis Methodを設定できる
* `UNKNOWN`時に確定扱いのTax Reportを抑止できる
* Solana Walletを登録できる
* Solana Transactionを取得できる
* Hyperliquid Unified Accountを登録できる
* HyperliquidのFill、`closedPnl`、Fee、Funding、Ledgerを別データとして取得できる
* bitbank → Phantom → Hyperliquidを追跡できる
* Transfer Match Statusを表示できる
* SOL → USDCを取得できる
* SOL譲渡損益をJPYで整理できる
* Perpetual関連損益、Funding、Feeを別々に確認できる
* Dataset別Data Coverageを検出できる
* Calculation RunからRate・Version・Coverageを追跡できる
* Timelineを表示できる
* Dashboardを表示できる
* NEEDS_REVIEWを表示できる
* ユーザーが分類を修正できる
* Raw Dataまで追跡できる
* CSV Exportできる
