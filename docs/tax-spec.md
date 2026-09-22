# Hyperliquid Tax Tracker JP — Tax Specification

## 1. 目的

本書では、Hyperliquid Tax Tracker JPで整理する税務イベント、取得価額、損益、JPY換算の方針を定義する。

本システムは税務判断や税額を保証しない。Perpetual、Funding、Feeなど、個別の日本税務上の扱いを確定できない項目は、推測で確定せず`NEEDS_REVIEW`として扱えるものとする。

税務ルールには対象年度、参照資料、`taxRuleVersion`を紐付ける。

---

## 2. MVPの税務整理対象

```text
JPY → SOL
SOL Transfer
SOL → USDC Spot
Hyperliquid Perpetual
Funding
Fee
```

v1の入金資産はSOLに限定する。Perpetualの取引銘柄は限定しないが、BTCやETHの入金・取得をサポートするものではない。

自己資金移動と売買・交換を明確に分離する。

---

## 3. 税務仕様の確定度

### 3.1 システム上の確定方針

以下はシステム上の処理方針として確定する。

* bitbank、Solana、Hyperliquid間の自己資金移動を売買・交換と分離する。
* `closedPnl`、Funding、Feeを別データ・別Eventとして保持する。
* JPY換算に使用したRateとSourceを保存する。
* 不足データ、未分類、未確定Feeを確定損益へ黙って含めない。

### 3.2 要確認の税務事項

以下は対象年度の法令・国税庁資料および必要に応じて税理士等への確認後に確定する。

* Hyperliquid Perpetualの所得区分と損益計上時期
* Funding Received / Paidの税務上の扱い
* 各Feeの必要経費、取得価額、譲渡損益への反映方法
* USDC取得・使用時の税務上の扱い

未確定の項目は、計算結果に`NEEDS_REVIEW`を付与する。

---

## 4. JPY → SOL

```text
JPY
↓
SOL
```

SOLの取得として記録する。

記録対象:

* 取得日時
* 取得数量
* JPY支払額
* 購入Fee
* 取得価額

購入Feeが取得のため直接要した費用に該当するかは、対象年度の税務ルールに従う。システムは購入Feeを取得価額計算の入力として保持し、後から再計算できるようにする。

---

## 5. SOL取得価額

SOLの譲渡原価では、以下の評価方法をサポートする。

```text
TOTAL_AVERAGE
MOVING_AVERAGE
UNKNOWN
```

個別ロット方式をv1の通常SOL取引へ適用しない。ただし、対象取引が通常の現物取引に該当するかなど、税務上の例外は別途確認する。

### 5.1 開始残高

前年末からSOLを保有している場合、ユーザーが以下を入力する。

* `openingQuantity`: 前年末SOL数量
* `openingBookValueJpy`: 前年末SOL簿価

開始残高は、当年の取得・譲渡の計算に使用する。入力値の根拠はユーザーが確認できるようにする。

### 5.2 総平均法

総平均法では、開始残高と当年取得を含めて平均単価を算出する。

```text
平均単価
= (openingBookValueJpy + 当年取得価額合計)
  / (openingQuantity + 当年取得数量合計)
```

年間の譲渡原価、年末評価額、イベントごとの表示方法は、対象年度の公式計算書に整合する形で実装する。

### 5.3 移動平均法

移動平均法では、SOLを取得するたびに平均単価を更新する。

```text
取得後簿価
= 取得前簿価 + 新規取得価額

取得後数量
= 取得前数量 + 新規取得数量

平均単価
= 取得後簿価 / 取得後数量
```

譲渡時は平均単価に基づいて簿価を減少させ、残高がゼロになった場合は次の取得から新しい残高として計算する。

### 5.4 評価方法設定

ユーザーごと・Assetごとに、設定と適用開始日を保持する。

`UNKNOWN`の場合は、確定扱いのTax Reportを生成しない。届出状況や評価方法変更をシステムが推測したり、自動で補正したりしてはならない。

---

## 6. 自己資金移動

以下は売買・交換とは別の資金移動として管理する。

```text
bitbank → Phantom
Phantom → Hyperliquid Solana Deposit
```

Tax Event:

```text
SELF_TRANSFER
```

Transfer Matchingでは、送金本体とFeeを分離する。

自己送金と断定できない場合は`NEEDS_REVIEW`とし、売買・交換として自動計上しない。

---

## 7. SOL → USDC

Hyperliquid Spotで、

```text
SOL → USDC
```

を取得した場合、SOL側の譲渡イベントとUSDC側の取得イベントを記録する。

記録対象:

* Timestamp
* SOL Amount
* USDC Amount
* 交換時JPY評価額
* SOL譲渡原価
* Spot Fee
* Fee Asset
* 損益

技術上の参考損益は以下の要素から構成する。

```text
SOL譲渡価額
- SOL譲渡原価
± 税務上分類された関連Fee
= SOL譲渡損益
```

Feeの扱いが未確定の場合、関連Feeを自動的に加減算してはならない。

USDCの取得情報は保持するが、v1ではUSDCの将来の売却・交換全般を対象に拡張しない。

---

## 8. Perpetual

Perpetualについて、以下を別データとして保持する。

* Fill
* `closedPnl`
* Fee
* Position表示用情報

`closedPnl`をPerpetualの確定損益の原データとして保持する。ただし、`closedPnl`へFeeやFundingが含まれると仮定しない。

年間集計では、`closedPnl`、Fee、Fundingを別々に確認できるようにする。日本の税務上の最終的な所得区分・計上方法は要確認とする。

---

## 9. Funding

Fundingを独立したTax Eventとして記録する。

```text
FUNDING_RECEIVED
FUNDING_PAID
```

記録対象:

* Symbol / Coin
* Timestamp
* Amount
* Asset
* JPY Rate
* JPY Value
* 元のFundingデータ

FundingをPerpetual PnLへ埋め込まない。Dashboardでは独立項目として表示し、年間集計で参照できるようにする。

Fundingの税務上の扱いが未確定の場合は、Tax Statusを`NEEDS_REVIEW`とする。

---

## 10. Fee

Feeは、以下の種類とFee Assetを区別して記録する。

* bitbank購入Fee
* bitbank出庫Fee
* Solana Network Fee
* Hyperliquid Spot Fee
* Hyperliquid Perpetual Fee
* その他Withdrawal Fee

共通情報:

```text
feeType
feeAsset
feeAmount
jpyValue
relatedRecordId
```

Feeの税務上の扱いを確定できない場合、Fee Eventを`NEEDS_REVIEW`とし、確定損益へ自動反映しない。

---

## 11. JPY換算

すべての表示・集計はJPYを基準とする。

記録対象:

```text
originalAsset
originalAmount
jpyRate
jpyValue
rateTimestamp
rateSource
priceSnapshotId
```

Rate不足、時刻不明、Source不明の場合はCalculation Errorまたは`NEEDS_REVIEW`とする。使用したRateを破棄しない。

---

## 12. Tax Event

Tax Eventは、Transaction Classification後のNormalized Dataから生成する。

主な種類:

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

Tax Eventは、Calculation Run、元のNormalized Record、Raw Dataへ関連付ける。

---

## 13. Calculated PnL

Dashboardの`Calculated PnL`は、以下の内訳を持つ技術的な集計値とする。

```text
SOL Exchange PnL
+ Perpetual closedPnlのJPY換算値
+ Funding ReceivedのJPY換算値
- Funding PaidのJPY換算値
± 税務上分類済みFee
= Calculated PnL
```

上記は、すべての関連Datasetが`COMPLETE`、Cost Basis Methodが`UNKNOWN`ではなく、未解決のTax Event・Calculation Errorがない場合に限り確定扱いとする。

税務上の最終的な所得計算にこの式をそのまま適用できるかは、対象年度の税務確認後に決定する。

---

## 14. NEEDS_REVIEWの分類

以下を混同しない。

### Transaction Classification

Transactionの種類が不明、または手動分類が必要な状態。

### Tax Event Classification

Tax Eventの種類・Tax Status・Feeの扱いが不明な状態。

### Data Coverage Error

必要Datasetが`PARTIAL`または`FAILED`である状態。

### Calculation Error

Missing Price、Cost Basis Methodが`UNKNOWN`、数量不整合、再計算失敗などの状態。

`Missing Price`や`Partial History`をTransaction Typeとして保存しない。

---

## 15. Data Coverage

対象年度のReport生成時には、以下のDataset Coverageを確認する。

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

必要Datasetが`PARTIAL`または`FAILED`の場合、年間損益を確定値として扱わない。

---

## 16. 監査性と再計算

各計算について、以下へ遡れること。

```text
Calculated PnL
↓
Calculation Run
↓
Tax Event / Cost Basis Calculation
↓
Normalized Data
↓
Raw Data
↓
Import Batch
```

Calculation Runでは少なくとも以下を記録する。

* 対象年度
* Cost Basis Method
* Opening Balance
* 使用したPrice / JPY Rate
* Tax Rule Version
* Normalization Version
* Data Coverage
* Calculation Timestamp

---

## 17. 税務仕様更新

税制変更に対応するため、以下をVersion管理する。

* 税務分類
* 取得価額計算
* Fee処理
* JPY換算
* Tax Event生成

本番運用時には対象年度の国税庁資料等を確認し、未確認のルールを確定仕様として扱わない。
