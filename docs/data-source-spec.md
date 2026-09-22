# Hyperliquid Tax Tracker JP — Data Source Specification

## 1. 目的

本書では、外部データソースから取得するデータ、外部API契約、取得制約、Coverage判定に必要な情報を定義する。

内部モデル、DBスキーマ、Normalizerのクラス構成は`implementation-spec.md`で定義する。

対象:

```text
bitbank
Solana
Hyperliquid
JPY / Market Price Provider
```

---

## 2. MVPのデータ範囲

入金ルートは以下に限定する。

```text
bitbank SOL
↓
Solana Wallet
↓
Hyperliquid Solana Deposit
```

Hyperliquid上ではSOL → USDC Spotと、USDCを利用するPerpetualの履歴を取得する。

Perpetualの銘柄は限定しない。BTC-PERP等の銘柄を取得しても、BTC入金をサポートするものではない。

---

## 3. bitbank

### 3.1 取得方式

v1ではCSV Importのみ対応する。API連携はv1対象外とする。

実装開始前に、実際のbitbank CSVサンプルを確認する。

### 3.2 Dataset: BITBANK_TRADES

最低限以下を取得可能とする。

* Timestamp
* Pair
* Side
* SOL Quantity
* Price
* JPY Amount
* Purchase Fee
* CSV上の識別情報

### 3.3 Dataset: BITBANK_WITHDRAWALS

最低限以下を取得可能とする。

* Timestamp
* Asset
* `grossAmount`
* `netAmount`
* `feeAmount`
* Fee Asset
* Destination情報
* CSV上の識別情報

CSVにTransaction Signature、出庫元アドレス、出庫数量の総額・純額が含まれるかは実データ確認後に確定する。

文字コード、カラム名、日時形式、数量の意味を推測しない。

---

## 4. Solana

### 4.1 入力

```text
Solana Wallet Address
```

Private Keyは必要としない。

### 4.2 Dataset: SOLANA_TRANSACTIONS

最低限以下を取得する。

```text
signature
timestamp
status

fromAddress
toAddress

asset
grossAmount
netAmount
feeAmount
networkFee
```

複数Transfer、Inner Instruction、失敗Transaction、Timestamp欠損、Native SOLとToken Transferを区別できる構造とする。

### 4.3 主な用途

#### bitbank → Phantom

登録WalletへのSOL入庫候補を取得する。

#### Phantom → Hyperliquid

登録WalletからHyperliquid Solana Deposit AddressへのSOL送金候補を取得する。

### 4.4 Provider

Providerは以下の候補から、実データ・履歴期間・Rate Limitを確認して決定する。

* Solana RPC
* Blockchain Data Provider API

Provider固有のレスポンスを税務計算へ直接渡さず、Raw Dataとして保存する。

具体的な時間窓、数量許容差、Pagination方式は実データ確認後に確定する。根拠のない数値を固定しない。

---

## 5. Hyperliquid Solana Deposit

Hyperliquid Account AddressとSolana Deposit Addressを別データとして管理する。

```text
Hyperliquid Account Address
Hyperliquid Solana Deposit Address
```

v1のSolana入金AssetはSOLのみとする。他のSolana Assetは対象外とする。

Solana Deposit Addressの取得元、入金ステータス、最低入金額、反映遅延、失敗時の状態は、実装前に最新の公式仕様と実データで確認する。

---

## 6. Hyperliquid Account

### 6.1 入力

```text
Hyperliquid Account Address
```

HyperliquidのRead-only情報取得に使用する。Private Key、Trading Permission、Withdrawal Permissionは使用しない。

### 6.2 Account Mode

v1では`Unified Account`のみサポートする。

以下はMVP対象外とする。

* Standard Account
* Portfolio Margin

Account Modeは将来拡張できるように取得・保持する。実装前に、Unified Accountの判定方法とSpot/Perpetual残高の関係を確認する。

---

## 7. Hyperliquid Dataset

以下を別Dataset・別Normalized Recordとして扱う。

### 7.1 HYPERLIQUID_FILLS

検討するRequest Type:

```text
userFillsByTime
```

対象項目:

* coin
* side
* dir
* size
* price
* time
* order ID
* fill hash / transaction ID
* その他識別情報

### 7.2 HYPERLIQUID_CLOSED_PNL

Fillレスポンス内の`closedPnl`を、Fill本体とは別のNormalized Recordとして保持する。

対象項目:

* Fillへの関連ID
* Coin
* Timestamp
* `closedPnl`
* PnL Asset

### 7.3 HYPERLIQUID_FEES

FillまたはLedgerに含まれるFeeを別Recordとして保持する。

対象項目:

* Related Fill / Ledger ID
* Fee Type
* Fee Asset
* Fee Amount
* Timestamp

### 7.4 HYPERLIQUID_FUNDING

検討するRequest Type:

```text
userFunding
```

対象項目:

* Symbol / Coin
* Timestamp
* Funding Rate
* Funding Amount
* Asset
* 外部識別情報

### 7.5 HYPERLIQUID_LEDGER

検討するRequest Type:

```text
userNonFundingLedgerUpdates
```

対象項目:

* Deposit
* Withdrawal
* Transfer
* Ledger Update Type
* Asset
* Amount
* Timestamp
* 外部識別情報

DepositとWithdrawalはLedger UpdateのSubtypeとして区別する。

### 7.6 Spot Metadata

検討するRequest Type:

```text
spotMeta
```

SpotのAsset ID、Token、Pair、Decimalsを取得する。

SOL/USDCが実際に利用可能なSpot Pairかどうかは、実装時にSpot Metadataで確認する。Pairの存在を仕様だけから推測しない。

Raw API Responseは全Datasetで保存する。`closedPnl`へFeeやFundingが含まれると仮定しない。

---

## 8. Hyperliquid API契約と制約

### 8.1 共通

* API Request Type、Request Parameter、Response SchemaをData Contract確認Stepで確定する。
* Timestampの単位、Timezone、Asset Decimal、文字列数値の扱いを確認する。
* Raw Responseを変更せず保存する。
* Request期間と実取得期間を記録する。

### 8.2 Fillsの履歴制約

`userFillsByTime`について、現行公式仕様で以下を確認対象とする。

* 1レスポンス最大2,000 fills
* 取得可能なのは最新10,000 fillsまで
* `startTime` / `endTime`はミリ秒Timestamp

この制約を超えて対象年度の開始日まで遡れない場合、`HYPERLIQUID_FILLS`を`PARTIAL`とする。

### 8.3 Pagination、Retry、Rate Limit

取得処理では以下を実装可能な構造とする。

* Pagination
* Retry
* Backoff
* 重複排除
* Request期間の再取得

Rate Limitの具体値は最新の公式仕様と実測後にProvider設定へ集約する。コードへ散在させない。

### 8.4 Coverage判定

Datasetごとに以下を記録する。

```text
requestedFrom
requestedTo
actualFrom
actualTo
status
reason
```

必要期間の一部しか取得できない場合は`PARTIAL`、取得に失敗して有効データがない場合は`FAILED`とする。

---

## 9. JPY Rate / Market Price

必要となる価格:

* SOL/JPY
* USDC/JPY
* Perpetual `closedPnl`のJPY評価に必要なRate
* FeeのJPY評価に必要なRate

Providerは実装前に選定する。保持情報:

```text
asset
currency
price
timestamp
source
```

RateのTimestamp、Timezone、欠損時の扱い、丸め精度、同一時刻に複数Rateがある場合の選択方法を確定する。

---

## 10. Raw Data

各外部Sourceから取得したデータは、変更不可のRaw Dataとして保持する。

Raw DataのCanonical Model、Import Batch、Payload Hash、再Normalizationの方法は`implementation-spec.md`で定義する。

Raw Dataから再Normalizationでき、外部APIの再取得なしに同じ計算を再現できることを要件とする。

---

## 11. Data Coverage

CoverageはSourceではなくDataset単位で記録する。

Dataset:

```text
BITBANK_TRADES
BITBANK_WITHDRAWALS
SOLANA_TRANSACTIONS
HYPERLIQUID_FILLS
HYPERLIQUID_FUNDING
HYPERLIQUID_LEDGER
PRICE_DATA
```

Status:

```text
COMPLETE
PARTIAL
FAILED
```

年間Reportは必要DatasetのCoverageを確認してから生成する。`PARTIAL`または`FAILED`がある場合、確定扱いのTax Reportを生成しない。
