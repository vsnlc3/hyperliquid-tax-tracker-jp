# Hyperliquid Tax Tracker JP — Implementation Specification

## 1. 目的

本書では、Requirements、Tax Spec、Data Source Specを満たす内部構造を定義する。

対象:

* Import Batch / Raw Data
* Normalization
* Transfer Matching
* Transaction / Tax Event Classification
* Cost Basis
* JPY Valuation
* Data Coverage
* Calculation Run
* Report / API

外部APIのRequest TypeとResponseの正本は`data-source-spec.md`とする。

---

## 2. Architecture

```text
Data Contract
     │
     ▼
Fetch / Upload
     │
     ▼
ImportBatch
     │
     ▼
RawData Storage
     │
     ├───────────────┐
     ▼               ▼
Normalization   Data Coverage
     │
     ├───────────────┐
     ▼               ▼
UnifiedTransaction  Hyperliquid Specialized Records
     │
     ▼
TransferMatcher
     │
     ▼
TransactionClassifier
     │
     ▼
TaxEventClassifier
     │
     ├───────────────┐
     ▼               ▼
JpyRateResolver  CostBasisCalculator
     │               │
     └───────┬───────┘
             ▼
       CalculationRun
             │
             ▼
       TaxCalculator
             │
        ┌────┴────┐
        ▼         ▼
     Timeline   Report
```

---

## 3. Backend / Frontend / Database

MVPで採用するBackend:

```text
Java 21+
Spring Boot 3.5.x
Maven
```

MVPで採用するFrontend:

```text
Next.js 15.x
TypeScript
```

MVPで採用するDatabase:

```text
PostgreSQL 16
Docker Compose
```

Step 1で上記の技術スタックを確定する。外部Data Contractや税務ルールを各FrameworkのDomain Modelへ直接埋め込まない。

Backendの責務:

* Data Import
* External API Integration
* Raw Data保存
* Normalization
* Matching
* Tax Calculation
* Persistence
* REST API

Frontendの責務:

* Setup
* CSV Upload
* Wallet / Account設定
* Import状況
* Timeline
* Dashboard
* Needs Review
* Tax Method設定
* Export

---

## 4. Domain Models

### 4.1 Wallet

```text
Wallet

id
userId
network
address
label
walletType
createdAt
```

MVPでは`network = SOLANA`、`walletType = USER_WALLET`を使用する。

### 4.2 HyperliquidAccount

```text
HyperliquidAccount

id
userId
accountAddress
solanaDepositAddress
accountMode
createdAt
```

`accountMode = UNIFIED`のみMVP対象とする。`Standard Account`と`Portfolio Margin`は対象外だが、値を区別して保存できる構造とする。

EVM Account AddressとSolana Deposit Addressを混同しない。

### 4.3 ImportBatch

```text
ImportBatch

id
userId
source
dataset
requestedFrom
requestedTo
createdAt
```

CSV Upload、Solana取得、Hyperliquid取得、Price取得の単位をImport Batchとして追跡する。

### 4.4 RawData

```text
RawData

id
source
dataset
externalId
occurredAt
payload
payloadHash
importBatchId
importedAt
```

`RawData`をRawImport、RawTransactionなどに分割しない。Raw Payloadは書き換えず、同一データの再Importは`payloadHash`等で冪等に扱う。

### 4.5 UnifiedTransaction

```text
UnifiedTransaction

id
userId
source
dataset
sourceRecordId
occurredAt
transactionType

assetFrom
amountFrom
assetTo
amountTo

asset
grossAmount
netAmount
feeAsset
feeAmount
feeType

fromAddress
toAddress
transactionHash
rawDataId
createdAt
```

`grossAmount`、`netAmount`、`feeAmount`はTransferで使用する。Spot、Purchase、Perpetualなどでは`assetFrom`、`assetTo`、`amountFrom`、`amountTo`を使用する。

Source TimestampにTimezoneが明示されない場合は、Raw Dataの原文とTimezone確度を保持し、`occurredAt`へ未確認のTimezoneを黙って適用しない。Fee Asset不明、gross/net不明、Priority Fee不明もNormalized Dataで表現できるようにする。

### 4.5.1 SolanaTransaction

```text
SolanaTransaction

signature
slot
blockTime
status
version
signerAddresses
systemTransfers
tokenTransfers
networkFeeLamports
priorityFeeLamports
rawDataId
```

`networkFeeLamports`はRPC `meta.fee`の総額を保持する。`priorityFeeLamports`はResponseから安全に分離できない場合はnullとし、総額との差額を推測値として保存しない。System TransferはTransaction全体の合計ではなく、Instruction単位でSource、Destination、Amountを保持する。

`status`は`SUCCESS`、`FAILED`、`NOT_FOUND`、`UNKNOWN`を区別する。Native SOLのSystem TransferとSPL Token Transferは別構造として保存し、MVPのUnifiedTransactionへ正規化するのは登録Walletに関係するNative SOL TransferとNetwork Feeのみとする。Token TransferはRaw DataおよびSolana固有Recordとして保持し、入金Assetを追加しない。

### 4.6 Transaction Type

```text
BUY
SELL
SWAP
TRANSFER_IN
TRANSFER_OUT
DEPOSIT
WITHDRAWAL
PERP_FILL
FUNDING
FEE
```

Transaction TypeとTax Event Typeを同一視しない。

### 4.7 Hyperliquid Specialized Records

Hyperliquidの以下のデータを一つの汎用Transactionへ押し込めず、DatasetごとのNormalized Recordとして保持する。

```text
HyperliquidFill
HyperliquidClosedPnl
HyperliquidFee
HyperliquidFunding
HyperliquidLedgerUpdate
```

各Recordは少なくとも以下を保持する。

* `rawDataId`
* 外部識別情報
* occurredAt
* Asset / Coin
* Amount

追加項目:

* Fill: coin、side、dir、size、price、startPosition、crossed、order ID、`tid`、hash、`twapId`
* Closed PnL: Fill関連ID、`closedPnl`、PnL Asset
* Fee: Fee Type、Fee Asset、Fee Amount、関連Fill/Ledger ID。Fillの`fee`と`feeToken`を別Fee Recordへ正規化する
* Funding: `type`、Funding Rate、Funding Amount、`szi`、Asset、Hash。Hashがゼロ値でも一意性をHashだけに依存しない
* Ledger: Deposit、Withdrawal、Transfer、Ledger Update Type、`token`、`amount`、`usdcValue`、`user`、`destination`、`fee`、`nativeTokenFee`、`nonce`、`feeToken`

`closedPnl`、Fee、Fundingを相互に合算済みと仮定しない。

### 4.7.1 Spot Metadataの扱い

Spot Pair、Token ID、Decimalsは外部MetadataのSnapshotとRaw Dataから解決し、`SOL/USDC`のPairやToken IDをコードへ固定しない。Metadataで確認できないAsset同一性を、名称の類似だけで補完してはならない。

2026-09-23のData Contract確認では、Token `USOL`（index `254`）とPair `@156`（tokens `[254, 0]`、Token `0`はUSDC）を確認した。Pair Index・Token MetadataはSnapshotで解決し、コードへ固定しない。`USOL`を税務上SOLと同一Assetと扱うかは`tax-spec.md`の`要確認`事項であり、Data Contractの同一経路確認だけで税務分類を確定しない。

### 4.8 TransferLink

```text
TransferLink

id
outgoingTransactionId
incomingTransactionId（`UNMATCHED`ではnull）
matchStatus
matchScore
matchedBy
createdAt
```

Match Status:

```text
MATCHED
POSSIBLE
UNMATCHED
REJECTED
```

一つのOutgoing Transactionに、複数のIncoming Transactionを`MATCHED`として紐付けてはならない。

`UNMATCHED`はIncoming Transactionを確定できない状態として保持する。`grossAmount`と`netAmount`は候補値として区別して扱い、Fee AssetやFeeの意味が未確定のまま手数料差額を自動補正してはならない。具体的な時間窓・数量許容差は未確定のため、確定値として実装へ埋め込まない。

### 4.9 TaxEvent

```text
TaxEvent

id
calculationRunId
sourceRecordId
eventType
taxStatus

asset
quantity
jpyValue
costBasisJpy
profitLossJpy

taxRuleVersion
createdAt
updatedAt
```

Event Type:

```text
ASSET_ACQUISITION
ASSET_DISPOSAL
SELF_TRANSFER
PERP_REALIZED_PNL
FUNDING_RECEIVED
FUNDING_PAID
FEE
```

Tax Status:

```text
TAXABLE
NON_TAXABLE
NEEDS_REVIEW
```

### 4.10 PriceSnapshot

```text
PriceSnapshot

id
asset
currency
price
priceTimestamp
source
providerAssetId
requestFrom
requestTo
interval
rawDataId
createdAt
```

Tax EventまたはCalculation Runから使用したPriceSnapshotを追跡できるようにする。

MVPの初期ProviderはCoinGecko Demo / Keyless APIとする。認証が必要な利用プランにも対応できるようAPI Keyは設定値として扱い、`JpyRateResolver`はProvider Adapter経由で利用する。Provider固有のEndpointやCoin IDをTax Calculatorへ持ち込まない。

### 4.11 CostBasisSetting

```text
CostBasisSetting

id
userId
asset
method
effectiveFrom
createdAt
```

Method:

```text
TOTAL_AVERAGE
MOVING_AVERAGE
UNKNOWN
```

`UNKNOWN`では確定扱いのTax Reportを生成しない。評価方法変更はユーザーによる明示設定がない限り行わない。

### 4.12 CostBasisOpeningBalance

```text
CostBasisOpeningBalance

id
userId
asset
asOfDate
quantity
bookValueJpy
inputSource
createdAt
updatedAt
```

MVPでは前年末SOL数量と前年末SOL簿価をユーザー入力で登録する。

### 4.13 UserClassification

```text
UserClassification

id
userId
targetType
targetId
beforeClassification
afterClassification
reason
createdAt
```

Transaction ClassificationとTax Event Classificationを区別し、修正履歴を保持する。

### 4.14 DataImportStatus

```text
DataImportStatus

id
userId
dataset
requestedFrom
requestedTo
actualFrom
actualTo
status
reason
importBatchId
checkedAt
```

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

### 4.15 CalculationRun

```text
CalculationRun

id
userId
targetYear
costBasisMethod
openingBalanceId
taxRuleVersion
normalizationVersion
dataCoverageSnapshot
priceSnapshotIds
status
blockedReasons
calculatedAt
```

Calculation Runは、1回の税務計算に使用した入力・Version・Coverage・Timestampを追跡する。

Status:

```text
DRAFT
BLOCKED
FINAL
```

* `DRAFT`: 計算途中、またはユーザー確認前
* `BLOCKED`: 確定結果を生成できない問題が存在する
* `FINAL`: MVPで要求する確定条件をすべて満たしている

`blockedReasons`はStatusではなく、Statusが`BLOCKED`となった理由を追跡する情報とする。

少なくとも以下の場合は`FINAL`にしてはならない。

* Cost Basis Method = `UNKNOWN`
* 必須Dataset Coverage = `PARTIAL`
* 必須Dataset Coverage = `FAILED`
* 必須Priceが不足している
* 税務計算に影響する未解決Tax Event Reviewが存在する
* 計算処理に未解決Errorが存在する

Transaction Review、Data Coverage、Calculation Error、Import ErrorはCalculation Run Statusとは別概念として保持する。税務計算へ影響しないTransaction Reviewが存在するだけでは、`BLOCKED`にしない。

`blockedReasons`の例:

```text
COST_BASIS_METHOD_UNKNOWN
REQUIRED_DATASET_PARTIAL
REQUIRED_DATASET_FAILED
REQUIRED_PRICE_MISSING
UNRESOLVED_TAX_EVENT_REVIEW
UNRESOLVED_CALCULATION_ERROR
```

---

## 5. Import Pipeline

```text
Data Contract確認
↓
Fetch / Upload
↓
ImportBatch作成
↓
RawData保存
↓
Duplicate Check
↓
Normalize
↓
DataImportStatus更新
↓
Persist Normalized Records
```

Importは冪等であること。同一データを再ImportしてもRawData、Normalized Record、UnifiedTransactionを重複生成しない。

---

## 6. Normalization

Provider固有DTOをTaxCalculatorへ直接渡さない。

```text
BitbankNormalizer
SolanaNormalizer
HyperliquidNormalizer
PriceNormalizer
```

Normalizationでは、Raw Dataの内容を変更せずに、UnifiedTransactionおよびDataset固有のNormalized Recordを生成する。

Normalization Versionを付与し、Version変更時にRaw Dataから再Normalizationできるようにする。

---

## 7. Transfer Matching

対象:

```text
bitbank → Phantom
Phantom → Hyperliquid Solana Deposit
```

候補情報:

```text
asset
grossAmount
netAmount
feeAmount
timestamp
fromAddress
toAddress
transactionHash
signature
```

具体的なTimestamp許容幅・数量許容差は、bitbank CSVとSolana実データ確認後に設定する。

優先度:

### Level 1

Signature、Transaction Hash等で明確に一致する。

### Level 2

Address、Amount、Timestampで高確率一致する。

### Level 3

AmountとTimestampのみで候補となる。

Level 3は自動確定せず`POSSIBLE`とする。候補がない場合は`UNMATCHED`、明確に異なる場合は`REJECTED`とする。

一つのOutgoing Transactionを複数のIncoming Transactionへ確定Matchしない。

---

## 8. Transaction / Tax Event Classification

処理を分ける。

```text
UnifiedTransaction / Specialized Record
↓
TransactionClassifier
↓
TaxEventClassifier
↓
TaxEvent
```

### Transaction Classification

```text
SWAP
TRANSFER
DEPOSIT
WITHDRAWAL
FEE
IGNORE
OTHER
```

既知の`UnifiedTransaction.transactionType`は、`BUY`/`SELL`/`SWAP`を`SWAP`、`TRANSFER_IN`/`TRANSFER_OUT`を`TRANSFER`、`DEPOSIT`を`DEPOSIT`、`WITHDRAWAL`を`WITHDRAWAL`、`FEE`を`FEE`へ分類する。`PERP_FILL`と`FUNDING`は`OTHER`として保持し、元のTransaction Typeを失わない。未知または必要項目不足の場合はTransaction Classification Reviewを作成する。

### Tax Event Classification

```text
ASSET_ACQUISITION
ASSET_DISPOSAL
SELF_TRANSFER
PERP_REALIZED_PNL
FUNDING_RECEIVED
FUNDING_PAID
FEE
```

`Missing Price`、`Partial History`、`Calculation Error`はTransaction Typeとして保存しない。

Tax Event ClassificationはTransaction Classificationの結果とは別に実行する。`BUY`は`ASSET_ACQUISITION`、`SELL`は`ASSET_DISPOSAL`、確定したTransferは`SELF_TRANSFER`、Spot交換は`ASSET_DISPOSAL`と`ASSET_ACQUISITION`へ分ける。Perpetualの`closedPnl`、Funding、Feeはそれぞれ独立Eventとして生成する。税務上の扱いが未確定なEventは`TaxStatus = NEEDS_REVIEW`とし、ErrorやCoverage状態をTax Event Typeへ設定しない。

---

## 9. JPY Valuation

```text
JpyRateResolver
```

責務:

```text
Asset
Amount
Timestamp
↓
JPY Value + PriceSnapshot
```

Price Source、Rate Timestamp、PriceSnapshot IDを保存する。

`PriceProvider` Adapterを介してProviderを差し替え可能にする。MVPの初期ProviderはCoinGeckoとし、100日を超える履歴はProvider契約に従って分割取得する。ProviderのRaw Responseを`RawData`へ保存し、Pointごとに`PriceSnapshot`を作成する。

Price PointのTimestampはProviderが返したUTC時刻をそのまま保持する。Transaction Timestampに一致するPointがない場合、Hourly Pointの選択や補間を自動で行わず、`Missing Price`をCalculation Errorとして扱う。Rate不足、Timestamp不明、Source不明の場合は確定扱いのCalculation Runを作成しない。

---

## 10. Cost Basis

Interface:

```text
CostBasisCalculator
```

Implementations:

```text
TotalAverageCalculator
MovingAverageCalculator
```

開始入力:

```text
前年末SOL数量
前年末SOL簿価
```

対応:

* SOL購入
* SOL譲渡
* 年初残高
* 年末残高
* Purchase Fee
* 明示設定されたCost Basis Method

Calculatorへの入力では、SOLの取得数量、税務ルール適用後の取得価額、購入Fee、譲渡数量、Timestamp、入力順を区別して保持する。購入Feeの取得価額算入可否は税務ルールで決定し、CalculatorがFee Assetや税務上の扱いを推測して自動加算してはならない。

`TOTAL_AVERAGE`は前年末開始残高と対象年の全取得を合算して平均単価を算出し、対象年の譲渡へ適用する。`MOVING_AVERAGE`はTimestampと明示された入力順に従い、取得ごとに簿価・数量・平均単価を更新し、譲渡ごとに簿価を減少させる。数量不足、負数、時系列を確定できない入力はCalculation Errorとして扱う。

`UNKNOWN`の場合、Cost Basis Calculationは参考値として保持できるが、Calculation Runを`FINAL`にしない。

TaxCalculatorは具体的なCostBasis実装へ直接依存しない。

---

## 11. Calculation Run / 再計算

1回の計算をCalculation Runとして管理する。

少なくとも以下を記録する。

* 対象年度
* Cost Basis Method
* Opening Balance
* 使用したPrice / JPY Rate
* Tax Rule Version
* Normalization Version
* Dataset別Data Coverage
* Calculation Timestamp

以下が変更された場合は、新しいCalculation Runを作成する。

* JPY Rate
* Tax Classification
* Cost Basis Method
* Opening Balance
* User Correction
* Tax Rule Version
* Normalization Version

旧Calculation Runを上書きせず、旧結果と新結果を区別できるようにする。

---

## 12. Needs Review / Error Handling

以下を別状態として管理する。

* Transaction Classification Review
* Tax Event Classification Review
* Data Coverage Error
* Calculation Error
* Import Error

これらはCalculation Run Statusとして使用しない。Calculation Runを`BLOCKED`へ遷移させる必要がある場合は、該当状態を`blockedReasons`へ記録する。

対象例:

```text
Unknown Transaction
Unknown Asset
Possible Transfer Match
Unmatched Transfer
Unknown Fee
Missing Price
Partial History
Cost Basis Method UNKNOWN
```

外部API失敗時にRaw Dataを破棄しない。

---

## 13. Timeline / Dashboard / Export

TimelineはUnifiedTransaction、Hyperliquid Specialized Records、TaxEventを基に生成する。

Dashboardでは以下を分けて表示する。

```text
SOL Exchange PnL
Perpetual closedPnl
Funding Received
Funding Paid
Fees
Calculated PnL
Needs Review Count
Dataset Coverage
Cost Basis Method
Calculation Run
```

Export:

```text
annual-summary.csv
transactions.csv
coverage.csv
needs-review.csv
```

---

## 14. Security

禁止:

```text
Private Key保存
Seed Phrase保存
Trading Permission取得
Withdrawal Permission取得
```

Public AddressとRead-only Dataを基本とする。

---

## 15. Testing

### Unit

* Raw Data Idempotency
* Normalizer
* Transfer Matcher
* Total Average
* Moving Average
* Unknown Cost Basis Method
* Transaction Classification
* Tax Event Classification
* JPY Calculation
* Calculation Run

### Integration

* CSV Import
* Hyperliquid Import
* Solana Import
* Price Import
* Database
* Data Coverage

### Scenario

```text
JPY
→ bitbank SOL
→ Phantom / Solana
→ Hyperliquid Solana Deposit
→ SOL/USDC Spot
→ Perpetual
→ Funding / Fee
→ JPY Summary
```

以下のケースも検証する。

* 前年末残高あり
* Cost Basis Method = `UNKNOWN`
* `POSSIBLE` / `UNMATCHED` Transfer
* Fee Asset不明
* Price不足
* Dataset `PARTIAL` / `FAILED`
* Calculation Run再作成
