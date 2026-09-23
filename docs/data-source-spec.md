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

Hyperliquid上では、業務上はSOL入金からUSDC Spotまでを対象とする。今回確認したData Contract上の表現は`Solana native SOL → Hyperliquid USOL → @156（quote USDC）`である。USOLをMVPの別入金Assetとして追加するものではない。

Perpetualの銘柄は限定しない。BTC-PERP等の銘柄を取得しても、BTC入金をサポートするものではない。

---

## 3. bitbank

### 3.1 取得方式

v1ではCSV Importのみ対応する。API連携はv1対象外とする。

bitbank公式サポートでは、注文・取引履歴および暗号資産・日本円の入出金履歴をCSVでダウンロードできることを確認した。

入出金履歴CSVには公式案内上の取得件数上限がある。対象年度の履歴を複数回に分けて取得する必要がある場合は、取得期間と不足の有無をData Coverageへ反映する。

Step 0で提供された実データでは、取引履歴CSVはUTF-8（BOMなし）で70行、出庫履歴CSVはUTF-8（BOMなし）で4行だった。取引履歴CSVのZIP内コピーは展開済みCSVと同一Payloadであるため、Payload Hashで重複排除する。

公式案内では、約定履歴CSVは24時間前までの履歴が対象とされている。今回の取引履歴のTimestamp範囲は2026-09-10 22:45:07.989から2026-09-21 18:17:46.092であり、当日分が含まれるとは仮定しない。

### 3.2 Dataset: BITBANK_TRADES

実データのHeaderは以下の14列だった。

| CSV列 | Data Contract上の意味 |
| --- | --- |
| `注文id` | Order ID |
| `取引id` | Trade ID |
| `通貨ペア` | Trading Pair。実データでは`sol_jpy`、`xrp_jpy` |
| `現物/信用` | 取引区分。実データでは`現物` |
| `タイプ` | Order Type。実データでは`limit`、`market` |
| `売/買` | Side。実データでは小文字の`buy`、`sell` |
| `数量` | Base Asset Quantity。小数点以下8桁 |
| `価格` | Quote Asset Price。小数点以下8桁 |
| `実現損益` | Realized PnL。今回の70行では空欄 |
| `発生手数料` | Occurred Fee。今回のCSVではFee Asset列なし |
| `実現手数料` | Realized Fee。今回の70行では`発生手数料`と同値 |
| `実現利息` | Interest。今回の70行では空欄 |
| `m/t` | Maker/Taker。今回の70行はすべて`taker` |
| `取引日時` | `YYYY-MM-DD HH:MM:SS.mmm` |

`JPY Amount`は独立したCSV列ではなく、数量と価格から導出する。今回のFeeは全行で数量×価格の0.12%と一致するが、CSV自体にFee Assetはない。公式REST APIはBase Fee、Quote Fee、Quote Occurred Feeを別Fieldで返すため、CSV取込時にFee Assetを`JPY`と推測せず、Raw Dataには原値を保存し、Normalized DataではFee Asset不明を保持できる構造とする。

実データのPair内訳は`sol_jpy`が1行（buy）、`xrp_jpy`が69行（buy/sell）だった。XRP行をRaw Dataから削除・無視せず、MVPのTax対象・Transfer Matching対象への抽出は`sol_jpy`を基準に行う。

### 3.3 Dataset: BITBANK_WITHDRAWALS

実データのHeaderは以下の9列だった。

| CSV列 | Data Contract上の意味 |
| --- | --- |
| `コイン` | Asset。実データでは`sol` |
| `日時` | `YYYY/MM/DD HH:MM:SS`。Timezone列なし |
| `数量` | Source-reported withdrawal amount |
| `手数料` | Withdrawal Fee Amount。Fee Asset列なし |
| `ラベル` | Destination Label。実データでは`Phantom` |
| `ネットワーク` | Network。実データでは`solana` |
| `アドレス` | Destination Address |
| `Txid` | Transaction Signature |
| `ステータス` | Status。実データでは`DONE` |

実データの`数量`は、対象DestinationへのSolana System Transfer数量と4件すべて一致した。ただし同じTransactionに別DestinationへのTransferを含む例があるため、Transaction全体の合計を出庫数量とみなさず、DestinationとTransfer構造を使って照合する。

CSVは`grossAmount`と`netAmount`を別列で持たない。`数量`をそのまま総額・純額の両方へ設定せず、Source-reported amountとして保持し、Fee Assetおよびgross/netの意味が確定するまで不明値を許容する。

提供された`tx-signature.txt`のTransactionは成功したLegacy Transactionで、bitbank出庫CSVのDestinationと同じアドレスが送信元となり、別アドレスへSOLを送る1件のSystem Transferを含んでいた。出庫先アドレスから下流のSolana処理へ進む実例としては利用できるが、PhantomユーザーWalletからDeposit Addressへの直接送金と断定しない。完成アプリではユーザーにSignatureの毎回入力を求めず、Wallet Addressから履歴を取得し、SignatureをRaw Dataの識別子として保持する。

CSV TimestampにTimezoneは明記されていない。今回の出庫時刻とSolana `blockTime`の対応はAsia/Tokyoとして読むと整合するが、これは実データ相関であり、CSVの公式Data Contractとして確定しない。Raw TimestampとTimezone確度を保持する。

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
priorityFee
```

複数Transfer、Inner Instruction、失敗Transaction、Timestamp欠損、Native SOLとToken Transferを区別できる構造とする。

Solana公式RPCの`getTransaction`では、`blockTime`が`null`になり得るほか、`meta`にFee、失敗情報、Inner Instruction、残高情報が含まれる。Provider差分を吸収する前に、実際の対象TransactionでTransferの抽出結果を確認する。

提供されたbitbank出庫CSV由来の4件の実Transactionと、`tx-signature.txt`由来の1件はすべて成功（`meta.err = null`）、`blockTime`あり、Legacy Version、1 Signerだった。CSV由来の4件では、System ProgramのParsed `transfer`からbitbank出庫CSVのDestinationに一致するSOL数量を抽出できた。2件は同一Transaction内に別Destinationへの追加Transferも含んでいた。`tx-signature.txt`由来のTransactionでは、出庫先アドレスが送信元となる後続Transferを確認した。

RPC Responseの`meta.fee`は総Network Feeとして保持する。今回のTransaction群ではCompute Budget Instructionが確認できず、Responseにも独立したPriority Fee Fieldはないため、`priorityFee`を`meta.fee`との差額から推測しない。Priority Feeを確定できない場合は不明値として保持する。

### 4.3 主な用途

#### bitbank → Phantom

登録WalletへのSOL入庫候補を取得する。

#### Phantom → Hyperliquid

登録WalletからHyperliquid Solana Deposit AddressへのSOL送金候補を取得する。

### 4.4 Provider

Providerは以下の候補から、実データ・履歴期間・Rate Limitを確認して決定する。

* Solana RPC
* Blockchain Data Provider API

Step 0では、認証不要のSolana Mainnet JSON-RPC `getTransaction`をMVPの第一候補とする。Provider固有のRate Limitや長期履歴の取得可否は設定へ切り出し、Normalized Dataへ直接埋め込まない。

Provider固有のレスポンスを税務計算へ直接渡さず、Raw Dataとして保存する。

具体的な時間窓、数量許容差、Pagination方式は実データ確認後に確定する。根拠のない数値を固定しない。

---

## 5. Hyperliquid Solana Deposit

Hyperliquid Account AddressとSolana Deposit Addressを別データとして管理する。

```text
Hyperliquid Account Address
Hyperliquid Solana Deposit Address
```

v1のSolana入金AssetはSOLのみとする。他のSolana Assetは対象外とする。Hyperliquid上では、今回の実レスポンスでSolana入金後の表現として`USOL`（Token index `254`、Full Name `Unit Solana`）が確認された。

`spotMeta`の`universe`では、`@156`がToken `[254, 0]`のPairであり、Token `0`は`USDC`だった。したがって、今回確認できたData Contract上のSpot経路は次のとおりである。

```text
Solana native SOL
↓
Hyperliquid Solana Deposit
↓
Hyperliquid Spot Token USOL
↓
Spot Pair @156（tokens [254, 0]、quote USDC）
```

`tx-signature.txt`由来の後続Transactionと、その約23秒後に取得したHyperliquid `spotTransfer`は、時刻・数量から対応候補として確認できた。ただし、Solana Transfer数量とUSOL数量の差分、下流アドレス、Deposit処理Feeの意味は確定しない。`SOL = USOL`を一般的な税務上の同一Assetと推測せず、Deposit反映、手数料、処理遅延、別Transferを確定的に合算しない。

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

Account Modeは`userAbstraction`で取得し、Responseの`unifiedAccount`、`portfolioMargin`、`disabled`、`default`、`dexAbstraction`を区別して保持する。`userDexAbstraction`も別Booleanとして保持する。

提供されたAccountでは`userAbstraction = unifiedAccount`、`userDexAbstraction = false`を確認した。v1の`accountMode = UNIFIED`へ対応づける。Standard、Portfolio Margin、DEX abstractionはMVP対象外とし、別Modeを検出した場合はImportまたはCalculationを確定扱いにしない。

---

## 7. Hyperliquid Dataset

以下を別Dataset・別Normalized Recordとして扱う。

### 7.1 HYPERLIQUID_FILLS

使用するRequest Type:

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
* startPosition
* `closedPnl`
* fee
* feeToken
* crossed
* fill hash / transaction ID
* trade ID
* その他識別情報

提供されたAccountの実Responseでは、同一時刻・同一Order ID・同一Fill Hashに対して複数の部分Fillが返り、`tid`が異なっていた。Fillの一意性には`tid`とRaw Dataを使用し、Hash単独で重複排除しない。

同じ`userFillsByTime` Response内にSpotの`@156` FillとPerpetualの`HYPE` Fillが存在した。入金AssetのSOL/USOLとPerpetualのTrade Symbolを`coin`の値だけで混同せず、Spot MetadataとPerpetual Metadataを参照して分類する。

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

実ResponseのFillでは`fee`と`feeToken`が返り、今回のPerpetual・Spot FillではFee Assetが`USDC`だった。`closedPnl`へFeeを含めず、Fee Recordを別に保持する。

### 7.4 HYPERLIQUID_FUNDING

使用するRequest Type:

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

実Responseは`{ delta, hash, time }`で、`delta`に`type`、`coin`、`fundingRate`、`szi`、`usdc`、`nSamples`が含まれた。今回の`hash`はゼロHashだったため、Fundingの一意性をHashだけに依存しない。

### 7.5 HYPERLIQUID_LEDGER

使用するRequest Type:

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

実Responseは`{ delta, hash, time }`で、今回取得範囲では`delta.type = spotTransfer`のみだった。`delta`には`token`、`amount`、`usdcValue`、`user`、`destination`、`fee`、`nativeTokenFee`、`nonce`、`feeToken`が含まれた。今回のResponseに明示的な`deposit`または`withdrawal`は存在しなかったため、`spotTransfer`を自動的にDeposit・Withdrawalへ読み替えない。

### 7.6 Spot Metadata

使用するRequest Type:

```text
spotMeta
```

SpotのAsset ID、Token、Pair、Decimalsを取得する。

2026-09-23に公開`spotMeta`を照会した結果、`USDC`（Token index `0`）と`USOL`（Token index `254`、`isCanonical = false`、Full Name `Unit Solana`）を確認した。`SOL`というToken名は確認できなかった。

`universe`には`name = @156`、`tokens = [254, 0]`のPairがあり、これが今回確認できたUSOL/USDC相当のPairである。UI表示名を推測せず、Pair IndexとToken MetadataをRaw Data・Snapshotへ保存する。

Raw API Responseは全Datasetで保存する。`closedPnl`へFeeやFundingが含まれると仮定しない。

---

## 8. Hyperliquid API契約と制約

### 8.1 共通

* API Request Type、Request Parameter、Response Schemaは公開公式仕様と実Responseで確認した内容を正本とする。
* Timestampの単位、Timezone、Asset Decimal、文字列数値の扱いを確認する。
* Raw Responseを変更せず保存する。
* Request期間と実取得期間を記録する。
* `userFillsByTime`、`userFunding`、`userNonFundingLedgerUpdates`は対象ユーザーの公開Account Addressを指定して取得する。公式ドキュメント上のRequest形式は確認できるが、実ユーザーのResponse例には公開Account Addressが必要である。
* Account Mode確認には`userAbstraction`、HIP-3 DEX abstraction確認には`userDexAbstraction`を使用する。`userRole`の`user`はAccount Modeを意味しない。

### 8.2 Fillsの履歴制約

`userFillsByTime`について、現行公式仕様で以下を確認対象とする。

* 1レスポンス最大2,000 fills
* 取得可能なのは最新10,000 fillsまで
* `startTime` / `endTime`はミリ秒Timestamp

この制約を超えて対象年度の開始日まで遡れない場合、`HYPERLIQUID_FILLS`を`PARTIAL`とする。

一般の時間範囲型Info APIには、1レスポンスあたりの要素数・distinct block数の上限がある。公式説明では最大500件が示されているが、`userFillsByTime`には上記の個別制約があるため、EndpointごとのPagination契約を分けて保持する。

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

Step 0でMVPの第一候補をCoinGecko Public APIへ選定する。認証なしの公開Endpointで、以下を使用する。

```text
SOL/JPY  : coin id = solana
USDC/JPY : coin id = usd-coin
Endpoint : /api/v3/coins/{id}/market_chart/range
vs_currency = jpy
```

Responseの`prices`は`[timestampMilliseconds, price]`の配列であり、Price SourceとResponse Timestampを保存する。2026-09-23の実照会で、SOL/JPYとUSDC/JPYの双方について対象期間の履歴を取得できた。

公式仕様上、Market ChartのHourly取得は全プランで利用可能となっており、Rangeは1回あたり最大100日が目安である。90日を超える範囲では自動的に日次（00:00 UTC）となるため、対象年度の履歴は期間分割し、取得粒度とCoverageを記録する。公開プランのRate Limitは30 calls/minuteとして扱い、Retry・Backoff・キャッシュをProvider Adapter側で管理する。

Provider固有仕様をDomain Modelへ直接埋め込まず、差し替え可能なPriceProvider Adapterを経由する。

保持情報:

```text
asset
currency
price
timestamp
source
```

対象年度の実取得時に履歴期間と欠損を検証する。Exact Transaction Timestampに対応するPointがない場合のHourly Point選択、丸め精度、Timezone境界、同一時刻に複数Rateがある場合の選択方法は税務・評価ルールとして要確認であり、価格を推測・補間して確定しない。Missing Priceは`Calculation Error`とし、Calculation Runを`BLOCKED`にする。

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

---

## 12. Step 0で確認できなかった事項

以下は、実データを確認した上でもSource Contractまたは意味が確定していない事項である。

* bitbank CSV TimestampのTimezone（CSVに明示なし）
* bitbank取引Feeおよび出庫FeeのFee Asset（CSVに明示なし）
* bitbank出庫`数量`のgross/net意味、およびFee控除前後の関係
* Solana `meta.fee`からPriority Feeを独立算出する方法
* HyperliquidのSolana Depositと`spotTransfer`の対応、反映遅延、差分数量の意味
* CoinGeckoのPointがExact Transaction Timestampにない場合の採用Point
* 対象年度全体のPrice履歴と各Datasetの完全性

これらはRaw Data、Source Timestamp、Response Payloadを保持した上で、確定できない場合はReviewまたはCalculation Errorへ送る。値を推測して確定値へ変換しない。
