# Hyperliquid Tax Tracker JP — Tasks

## 1. このファイルの役割

本ファイルは実装仕様を定義する文書ではない。

以下を管理する。

* 現在の進捗
* 次に実装するStep
* 各Stepの完了条件

仕様は以下を参照する。

```text
requirements.md
tax-spec.md
data-source-spec.md
implementation-spec.md
```

仕様変更が必要になった場合は、先に該当仕様書を更新してから実装する。

---

# Step 0 — Data Contract確認

## Tasks

実装開始前に、以下を確認・記録する。

* bitbank CSV実サンプル
* bitbank CSVの文字コード、日時、数量、Feeの意味
* Hyperliquid API Response実例
* `userFillsByTime`
* `userFunding`
* `userNonFundingLedgerUpdates`
* Hyperliquid Spot Metadata
* SOL/USDC Spot Pairの存在
* Solana Transaction実例
* Solana Provider候補
* Price Provider候補
* Hyperliquid Account Mode

## Done

* Data Contract調査結果が`data-source-spec.md`に反映されている
* Data Contractによって必要になった内部設計上の変更点が`implementation-spec.md`に反映されている
* 税務上の未確定事項が`tax-spec.md`に`要確認`として記録されている
* 未確定項目が`要確認`として列挙されている
* このStepではコード実装、Migration作成、Domain Modelの確定を開始していない
* BTC/ETH入金、Standard Account、Portfolio Margin、Vault、Staking、HIP-3をMVPへ追加していない
* Domain ModelとMigrationを確定できる入力が揃っている

---

# Step 1 — Project Setup

## Tasks

* Repository準備
* Frontend作成
* Backend作成
* PostgreSQL準備
* Docker Compose作成
* `docs/`追加
* 技術スタック確定

## Done

* Frontendが起動する
* Backendが起動する
* PostgreSQLへ接続できる
* ローカルで一括起動できる

---

# Step 2 — Core Domain Model

## Tasks

実装:

* Wallet
* HyperliquidAccount
* ImportBatch
* RawData
* UnifiedTransaction
* Hyperliquid Specialized Records
  * Fill
  * Funding
  * Ledger Update
  * Deposit（Ledger UpdateのSubtype）
  * Withdrawal（取得された場合のみ保持する履歴Subtype）
* TransferLink
* TaxEvent
* PriceSnapshot
* CostBasisSetting
* CostBasisOpeningBalance
* UserClassification
* DataImportStatus
* CalculationRun

## Done

* Migrationが実行できる
* Dataset別Data Coverageを保存できる
* `grossAmount`、`netAmount`、`feeAmount`、Fee Assetを保存できる
* `UNKNOWN` Cost Basis Methodを保存できる
* Fill、Funding、Ledger Updateを別Recordとして管理できる
* DepositをLedger UpdateのSubtypeとして管理できる
* Withdrawalを取得した場合にRawData / Ledger情報として保持できる
* Domain ModelのUnit Testが通る

---

# Step 3 — bitbank CSV Import

## Tasks

* 実際のbitbank CSV仕様確認結果を反映
* CSV Parser
* SOL購入取込
* SOL出庫取込
* 購入Fee取込
* 出庫Fee取込
* RawData保存
* ImportBatch保存
* 重複Import防止

## Done

以下がImportできる。

```text
JPY → SOL
SOL Withdrawal
Purchase Fee
Withdrawal Fee
```

再Importしても重複しない。

---

# Step 4 — bitbank Normalization

## Tasks

bitbank RawDataをNormalized RecordとUnifiedTransactionへ変換する。

対象:

```text
BUY
TRANSFER_OUT
FEE
```

数量について、`grossAmount`、`netAmount`、`feeAmount`を区別する。

## Done

CSVの各対象Rowから、RawDataを追跡可能なNormalized RecordとUnifiedTransactionが生成される。

---

# Step 5 — Wallet / Hyperliquid Account Setup

## Tasks

* Solana Wallet登録API
* Solana Address validation
* Hyperliquid Account登録
* Solana Deposit Address管理
* Hyperliquid Account Address validation
* Account Mode確認

## Done

* Phantomで使用しているSolana Addressを登録できる
* Hyperliquid Account AddressとSolana Deposit Addressを別情報として管理できる
* `Unified Account`以外はMVP対象外として扱われる

---

# Step 6 — Solana Transaction Import

## Tasks

* Provider選定
* Transaction取得
* SOL Transfer解析
* Native SOL / Token Transfer区別
* `grossAmount`、`netAmount`、`feeAmount`解析
* Network Fee取得
* Failed Transaction保持
* RawData保存
* ImportBatch保存
* Pagination
* Retry
* Dataset Coverage

## Done

登録Walletについて、以下を取得できる。

```text
incoming SOL
outgoing SOL
network fee
signature
status
```

`SOLANA_TRANSACTIONS`の`COMPLETE`、`PARTIAL`、`FAILED`を判断できる。

---

# Step 7 — Hyperliquid Data Import

## Tasks

Datasetごとに取得する。

```text
HYPERLIQUID_FILLS
HYPERLIQUID_FUNDING
HYPERLIQUID_LEDGER
```

取得対象:

* Fill
* `closedPnl`
* Fee
* Funding
* Deposit
* Transfer
* Spot Metadata

WithdrawalはHyperliquid APIから取得可能な履歴として保持する。ただし、v1の主経路、税務計算、MVP完了条件の必須対象とはしない。Withdrawal固有の高度な税務処理はMVP対象外とする。

対応:

* `userFillsByTime`
* `userFunding`
* `userNonFundingLedgerUpdates`
* Pagination
* Rate Limit
* RawData保存
* ImportBatch保存
* Duplicate防止
* Dataset Coverage

## Done

* Fill、`closedPnl`、Fee、Funding、Ledger Updateが別Recordとして保存される
* `closedPnl`へFeeやFundingが含まれると仮定していない
* Datasetごとに`COMPLETE`、`PARTIAL`、`FAILED`を判断できる
* Withdrawalを取得した場合はRawData / Ledger情報として保存できる
* Withdrawalが取得できない、または存在しない場合でもStep完了を妨げない

---

# Step 8 — Hyperliquid Normalization

## Tasks

Hyperliquid RawDataから以下を生成する。

```text
SWAP
PERP_FILL
FUNDING
DEPOSIT
FEE
```

Specialized RecordとUnifiedTransactionの関連付けを行う。

Withdrawalが取得された場合は`WITHDRAWAL`へNormalizeできる。ただし、WithdrawalのNormalizeは主経路の税務計算およびMVP完了条件の必須処理ではない。

## Done

SOL → USDC、Perp Fill、`closedPnl`、Funding、Fee、DepositがRawDataまで追跡可能な状態で保存される。Withdrawalは取得された場合にRawData / Ledgerまで追跡可能であればよく、取得されない場合もDoneとする。

---

# Step 9 — Transfer Matching

## Tasks

照合:

```text
bitbank → Phantom
Phantom → Hyperliquid Solana Deposit
```

判定材料:

* Asset
* `grossAmount`
* `netAmount`
* `feeAmount`
* Timestamp
* Address
* Transaction Signature
* Transaction Hash

Match Status:

```text
MATCHED
POSSIBLE
UNMATCHED
REJECTED
```

具体的な時間窓・数量許容差は実データ確認後に設定する。

## Done

* 明確なTransferを`MATCHED`にできる
* 不確実なものを`POSSIBLE`にできる
* 候補がないものを`UNMATCHED`にできる
* 一つのOutgoing Transactionを複数Incomingへ確定Matchしない

---

# Step 10 — JPY Rate

## Tasks

* Price Provider選定
* SOL/JPY取得
* USDC/JPY取得
* Perpetual `closedPnl`用Rate取得
* Fee用Rate取得
* Rate保存
* Historical Rate取得
* Missing Rate処理
* Timestamp / Timezone / 丸めルール確定

## Done

* 指定Timestampの取引をJPY換算できる
* 使用Rate、Timestamp、Source、PriceSnapshotを再確認できる
* Missing PriceをCalculation Errorとして扱える

---

# Step 11 — Cost Basis

## Tasks

実装:

```text
TotalAverageCalculator
MovingAverageCalculator
```

対応:

* 前年末SOL数量
* 前年末SOL簿価
* SOL購入
* SOL譲渡
* 年末残高
* Purchase Fee
* `UNKNOWN`

## Done

* 前年末残高を入力できる
* 総平均法・移動平均法の計算結果がテスト期待値と一致する
* `UNKNOWN`の場合に確定扱いのTax Reportを生成しない
* 評価方法を推測・自動変更しない

---

# Step 12 — Tax Event Classification

## Tasks

Transaction ClassificationとTax Event Classificationを分けて実装する。

Tax Event:

```text
ASSET_ACQUISITION
ASSET_DISPOSAL
SELF_TRANSFER
PERP_REALIZED_PNL
FUNDING_RECEIVED
FUNDING_PAID
FEE
```

## Done

* 主要MVP TransactionがTax Eventへ分類される
* 分類不能TransactionはTransaction Reviewになる
* 税務上未確定のEventはTax Event Reviewになる
* `Missing Price`や`Partial History`をTransaction Typeへ設定しない

---

# Step 13 — Needs Review / Error Handling

## Tasks

以下を別状態として実装する。

* Transaction Classification Review
* Tax Event Classification Review
* Data Coverage Error
* Calculation Error
* Import Error
* 手動分類
* 修正履歴
* 再計算要求

## Done

* ユーザーが分類を修正できる
* 修正前のClassificationを保持できる
* 修正結果が新しいCalculation Runへ反映される
* Data Coverage Error、Calculation Error、Import ErrorをTransaction Typeと混同しない

---

# Step 14 — Tax Calculation

## Tasks

### SOL → USDC

* SOL譲渡価額
* SOL譲渡原価
* Spot Fee
* JPY PnL

### Perpetual

* `closedPnl`整理
* Trading Fee整理
* JPY換算

### Funding

* Funding Received整理
* Funding Paid整理
* JPY換算
* 年間集計に含める

FundingをPerpetual PnLへ埋め込まない。

### Calculation Run

* 対象年度
* Cost Basis Method
* Opening Balance
* Price / JPY Rate
* Tax Rule Version
* Normalization Version
* Dataset Coverage
* Calculation Timestamp
* `blockedReasons`

Calculation RunのMVP判定Status:

```text
DRAFT
BLOCKED
FINAL
```

* `DRAFT`: 計算途中、またはユーザー確認前
* `BLOCKED`: 確定結果を生成できない問題が存在する
* `FINAL`: MVPで要求する確定条件を満たしている

少なくとも以下の場合は`FINAL`にできない。

* Cost Basis Method = `UNKNOWN`
* 必須Dataset Coverage = `PARTIAL`
* 必須Dataset Coverage = `FAILED`
* 必須Priceが不足している
* 税務計算へ影響する未解決Tax Event Reviewが存在する
* 計算処理に未解決Errorが存在する

Transaction Reviewは、内容によってTax Calculationへ影響しない可能性がある。Transaction Reviewが1件でも存在することだけを理由に、必ず`BLOCKED`としてはならない。

`PREVIEW`、`NEEDS_REVIEW`、`FAILED`はCalculation Run Statusとして使用しない。Review状態、Data Coverage状態、Calculation Error、Import Errorとして別々に保持し、確定結果を妨げる場合だけ`blockedReasons`へ記録してCalculation Runを`BLOCKED`にする。

## Done

* SOL Exchange PnL、Perpetual `closedPnl`、Funding、Feeを別々に確認できる
* Dataset Coverage、Cost Basis Method、未解決ReviewをCalculation Runへ反映できる
* 計算途中または確認中のRunを`DRAFT`として保持できる
* 確定できないRunを`BLOCKED`として保持できる
* MVPの確定条件を満たしたRunだけを`FINAL`にできる
* `blockedReasons`で`BLOCKED`の理由を追跡できる

---

# Step 15 — Backend API

## Tasks

API:

* Setup
* Import
* Wallet / Account
* Timeline
* Dashboard
* Needs Review
* Calculation Run
* Export

## Done

* Backend APIからMVP機能を操作できる
* Dataset別Coverageを取得できる
* Calculation Runの入力・状態・結果を取得できる

---

# Step 16 — Frontend

## Tasks

画面:

* Setup
* CSV Import
* Wallet / Account
* Import Status
* Timeline
* Dashboard
* Transaction Detail
* Needs Review
* Tax Method Setting
* Calculation Run

## Done

MVPの主要機能をブラウザから操作できる。

---

# Step 17 — CSV Export

## Tasks

出力:

```text
annual-summary.csv
transactions.csv
coverage.csv
needs-review.csv
```

## Done

対象年度の集計、明細、Coverage、Review項目をExportできる。

---

# Step 18 — End-to-End Scenario

以下を通しで検証する。

```text
bitbank
100,000 JPY
↓
SOL
↓ Transfer
Phantom / Solana
↓ Transfer
Hyperliquid Solana Deposit
↓ Spot
SOL → USDC
↓ Perpetual
closedPnl / Funding / Fee
↓
JPY Summary
```

## Done

以下を含むデータをImportしてからReport生成まで、人手によるDB修正なしで完了できる。

正常系:

* 必須Datasetが`COMPLETE`
* Cost Basis Methodが`TOTAL_AVERAGE`または`MOVING_AVERAGE`
* 必須Priceが揃っている
* 税務計算へ影響する未解決Tax Event Reviewがない
* Calculation Runが`FINAL`になる

Blocked系:

* 前年末残高あり
* BTC-PERP等のPerpetual銘柄
* 税務計算へ影響する`POSSIBLE` / `UNMATCHED` Transfer
* 税務計算へ影響するFee Asset不明
* Price不足
* 必須Dataset Coverage = `PARTIAL`
* 必須Dataset Coverage = `FAILED`
* Cost Basis Method = `UNKNOWN`
* 未解決Tax Event Review
* 計算処理に未解決Errorが存在する

Blocked系ではCalculation Runが`BLOCKED`になり、確定扱いのTax Reportを生成しない。

問題解消後:

* 修正後に新しいCalculation Runを作成する
* 必須条件を満たした新しいRunが`FINAL`へ移行する

Withdrawalは主経路およびE2Eの必須データに含めない。取得された場合はRawData / Ledger情報として保持できることのみ確認する。

---

# Step 19 — MVP Review

確認:

* Requirementsを満たす
* Tax Specと実装が一致する
* Data Source Contractと実装が一致する
* Dataset別Data Coverageを確認できる
* RawDataから再Normalizationできる
* Calculation RunからRate・Version・Coverageを追跡できる
* Private Keyを扱っていない
* Transaction Classification ReviewとTax Event Classification Reviewが区別して機能する
* Data Coverage Error、Calculation Error、Import ErrorがReview状態と混同されていない
* CSV Exportできる
* BTC/ETH入金、bitbank以外の取引所、Vault、Staking、HIP-3を追加していない

## Done

`requirements.md`のMVP完了条件をすべて満たす。

この状態を、

**Hyperliquid Tax Tracker JP v1**

完成とする。
