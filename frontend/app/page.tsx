"use client";

import { ChangeEvent, FormEvent, useCallback, useEffect, useMemo, useState } from "react";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const REQUIRED_DATASETS = [
  "BITBANK_TRADES", "BITBANK_WITHDRAWALS", "SOLANA_TRANSACTIONS",
  "HYPERLIQUID_FILLS", "HYPERLIQUID_FUNDING", "HYPERLIQUID_LEDGER",
  "HYPERLIQUID_SPOT_METADATA", "PRICE_DATA",
];

type RecordValue = Record<string, unknown>;
type Dashboard = { timeline: RecordValue[]; coverage: RecordValue[]; reviews: RecordValue[]; errors: RecordValue[] };

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, options);
  const body = await response.text();
  if (!response.ok) throw new Error(body || `HTTP ${response.status}`);
  return body ? (JSON.parse(body) as T) : ({} as T);
}

function isoFromLocal(value: string): string { return new Date(value).toISOString(); }
function display(value: unknown): string { return value === null || value === undefined || value === "" ? "—" : String(value); }

export default function Home() {
  const [userId, setUserId] = useState("");
  const [walletId, setWalletId] = useState("");
  const [accountId, setAccountId] = useState("");
  const [dashboard, setDashboard] = useState<Dashboard>({ timeline: [], coverage: [], reviews: [], errors: [] });
  const [message, setMessage] = useState("User IDを入力してDashboardを読み込んでください。");
  const [busy, setBusy] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [range, setRange] = useState({ from: "2026-01-01T00:00", to: "2026-12-31T23:59" });
  const [costBasis, setCostBasis] = useState("MOVING_AVERAGE");
  const [opening, setOpening] = useState({ quantity: "0", bookValueJpy: "0", asOfDate: "2025-12-31" });
  const canUse = userId.trim().length > 0;

  const loadDashboard = useCallback(async () => {
    if (!canUse) return;
    setBusy(true);
    try {
      const id = encodeURIComponent(userId.trim());
      const [timeline, coverage, reviews, errors] = await Promise.all([
        request<RecordValue[]>(`/api/users/${id}/timeline`), request<RecordValue[]>(`/api/users/${id}/coverage`),
        request<RecordValue[]>(`/api/users/${id}/reviews`), request<RecordValue[]>(`/api/users/${id}/errors`),
      ]);
      setDashboard({ timeline, coverage, reviews, errors });
      setMessage("Dashboardを更新しました。");
    } catch (error) { setMessage(error instanceof Error ? error.message : "Dashboardの読み込みに失敗しました。"); }
    finally { setBusy(false); }
  }, [canUse, userId]);

  useEffect(() => { if (canUse) void loadDashboard(); }, [canUse, loadDashboard]);

  async function runAction(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try { await action(); setMessage(success); await loadDashboard(); }
    catch (error) { setMessage(error instanceof Error ? error.message : "処理に失敗しました。"); }
    finally { setBusy(false); }
  }

  async function registerWallet(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    await runAction(async () => { const wallet = await request<RecordValue>("/api/setup/wallets", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId, address: form.get("address"), label: form.get("label") }),
    }); setWalletId(display(wallet.id)); }, "Solana Walletを登録しました。");
  }

  async function registerAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    await runAction(async () => { const account = await request<RecordValue>("/api/setup/hyperliquid-accounts", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId, accountAddress: form.get("accountAddress"), solanaDepositAddress: form.get("solanaDepositAddress"), accountMode: "UNIFIED" }),
    }); setAccountId(display(account.id)); }, "Hyperliquid Unified Accountを登録しました。");
  }

  async function importBitbank(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) { setMessage("bitbank CSVを選択してください。"); return; }
    const body = new FormData(); body.append("userId", userId); body.append("file", file);
    await runAction(() => request("/api/import/bitbank", { method: "POST", body }), "bitbank CSVをImportしました。");
  }

  async function importRange(kind: "solana" | "hyperliquid" | "prices") {
    const from = isoFromLocal(range.from); const to = isoFromLocal(range.to);
    if (kind === "solana" && !walletId) { setMessage("先にWallet IDを登録または入力してください。"); return; }
    if (kind === "hyperliquid" && !accountId) { setMessage("先にAccount IDを登録または入力してください。"); return; }
    const path = kind === "solana" ? "/api/import/solana" : kind === "hyperliquid" ? "/api/import/hyperliquid" : "/api/import/prices";
    const body = kind === "solana" ? { userId, walletId, from, to } : kind === "hyperliquid" ? { userId, accountId, from, to } : { userId, from, to };
    await runAction(() => request(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }), `${kind}のImportを実行しました。`);
  }

  async function normalize(path: string, success: string) {
    await runAction(() => request(`/api/import/${path}/${userId}/normalize`, { method: "POST" }), success);
  }

  async function saveCostBasis(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await runAction(() => request(`/api/users/${userId}/cost-basis/settings`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ asset: "SOL", method: costBasis, effectiveFrom: opening.asOfDate }) }), "Cost Basis Methodを保存しました。");
  }

  async function saveOpeningBalance(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await runAction(() => request(`/api/users/${userId}/cost-basis/opening-balance`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ asset: "SOL", asOfDate: opening.asOfDate, quantity: opening.quantity, bookValueJpy: opening.bookValueJpy, inputSource: "USER_INPUT" }) }), "前年末SOL残高を保存しました。");
  }

  async function createCalculationRun(finalizeRequested: boolean) {
    const coverage = Object.fromEntries(dashboard.coverage.map((item) => [item.dataset, item.status]));
    await runAction(() => request("/api/calculation-runs", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({
      userId, targetYear: Number(opening.asOfDate.slice(0, 4)) + 1, costBasisMethod: costBasis,
      taxRuleVersion: "tax-v1", normalizationVersion: "bitbank-v1|solana-v1|hyperliquid-v1",
      requiredDatasets: REQUIRED_DATASETS, coverage, priceSnapshotIds: [], finalizeRequested,
    }) }), finalizeRequested ? "Calculation Runを確定要求しました。" : "Calculation RunをDRAFTで作成しました。");
  }

  const coverageSummary = useMemo(() => {
    const counts = { COMPLETE: 0, PARTIAL: 0, FAILED: 0 };
    dashboard.coverage.forEach((item) => { const status = item.status as keyof typeof counts; if (status in counts) counts[status] += 1; });
    return counts;
  }, [dashboard.coverage]);

  return <main className="shell">
    <header className="hero"><div><p className="eyebrow">MVP / JAPAN TAX WORKSPACE</p><h1>Hyperliquid Tax Tracker JP</h1><p className="lead">bitbank → SOL → Phantom/Solana → Hyperliquid → USOL/USDC → Perpetual を、Raw DataからJPY集計まで追跡します。</p></div><div className="user-panel"><label htmlFor="user-id">User ID</label><div className="inline-form"><input id="user-id" value={userId} onChange={(event) => setUserId(event.target.value)} placeholder="UUID" /><button onClick={() => void loadDashboard()} disabled={!canUse || busy}>更新</button></div><small>{message}</small></div></header>
    <section className="metrics"><Metric label="Timeline" value={dashboard.timeline.length} /><Metric label="Coverage Complete" value={coverageSummary.COMPLETE} tone="good" /><Metric label="Partial / Failed" value={`${coverageSummary.PARTIAL} / ${coverageSummary.FAILED}`} tone={coverageSummary.PARTIAL + coverageSummary.FAILED > 0 ? "warn" : "good"} /><Metric label="Open Review / Error" value={`${dashboard.reviews.length} / ${dashboard.errors.length}`} tone={dashboard.reviews.length + dashboard.errors.length > 0 ? "warn" : "good"} /></section>

    <section className="grid two"><Card title="1. Setup" subtitle="秘密鍵は扱いません。Unified Accountのみ対象です。"><form onSubmit={registerWallet} className="stack"><h3>Solana Wallet</h3><input name="address" required placeholder="Phantom / Solana Wallet Address" /><input name="label" placeholder="Label（任意）" /><button disabled={!canUse || busy}>Walletを登録</button></form><p className="hint">Wallet ID: {walletId || "未登録"}</p><form onSubmit={registerAccount} className="stack separated"><h3>Hyperliquid Unified Account</h3><input name="accountAddress" required placeholder="Account Address（0x...）" /><input name="solanaDepositAddress" required placeholder="Solana Deposit Address" /><button disabled={!canUse || busy}>Accountを登録</button></form><p className="hint">Account ID: {accountId || "未登録"}</p></Card><Card title="2. Cost Basis" subtitle="SOLの前年末残高と評価方法を明示入力します。"><form onSubmit={saveCostBasis} className="stack"><label>Method<select value={costBasis} onChange={(event) => setCostBasis(event.target.value)}><option value="TOTAL_AVERAGE">TOTAL_AVERAGE</option><option value="MOVING_AVERAGE">MOVING_AVERAGE</option><option value="UNKNOWN">UNKNOWN</option></select></label><label>前年末日<input type="date" value={opening.asOfDate} onChange={(event) => setOpening({ ...opening, asOfDate: event.target.value })} /></label><button disabled={!canUse || busy}>Methodを保存</button></form><form onSubmit={saveOpeningBalance} className="stack separated"><h3>前年末SOL残高</h3><input type="number" min="0" step="any" value={opening.quantity} onChange={(event) => setOpening({ ...opening, quantity: event.target.value })} placeholder="SOL数量" /><input type="number" min="0" step="any" value={opening.bookValueJpy} onChange={(event) => setOpening({ ...opening, bookValueJpy: event.target.value })} placeholder="前年末簿価（JPY）" /><button disabled={!canUse || busy}>開始残高を保存</button></form></Card></section>

    <section className="card"><div className="card-heading"><div><h2>3. Import & Normalize</h2><p>Bitbank CSV、Wallet履歴、Hyperliquid公開履歴、価格履歴を分離して取得します。</p></div></div><div className="grid three"><form onSubmit={importBitbank} className="stack"><h3>bitbank CSV</h3><input type="file" accept=".csv,.zip" onChange={(event: ChangeEvent<HTMLInputElement>) => setFile(event.target.files?.[0] ?? null)} /><button disabled={!canUse || busy || !file}>CSVをImport</button><button type="button" className="secondary" disabled={!canUse || busy} onClick={() => void normalize("bitbank", "bitbankをNormalizeしました。")}>bitbankをNormalize</button></form><div className="stack"><h3>公開API履歴</h3><input value={walletId} onChange={(event) => setWalletId(event.target.value)} placeholder="Wallet ID" /><input value={accountId} onChange={(event) => setAccountId(event.target.value)} placeholder="Account ID" /><label>From<input type="datetime-local" value={range.from} onChange={(event) => setRange({ ...range, from: event.target.value })} /></label><label>To<input type="datetime-local" value={range.to} onChange={(event) => setRange({ ...range, to: event.target.value })} /></label><div className="button-row"><button disabled={!canUse || busy} onClick={() => void importRange("solana")}>Solana Import</button><button disabled={!canUse || busy} onClick={() => void importRange("hyperliquid")}>Hyperliquid Import</button></div><button className="secondary" disabled={!canUse || busy} onClick={() => void normalize("hyperliquid", "HyperliquidをNormalizeしました。")}>HyperliquidをNormalize</button></div><div className="stack"><h3>JPY Price</h3><p className="hint">CoinGeckoのSOL/JPY・USDC/JPY。Missing PriceはBLOCKEDです。</p><button disabled={!canUse || busy} onClick={() => void importRange("prices")}>Price Import</button><button className="secondary" disabled={!canUse || busy} onClick={() => void runAction(() => request(`/api/import/matching/${userId}`, { method: "POST" }), "Transfer Matchingを実行しました。")}>Transfer Matching</button></div></div></section>

    <section className="grid two"><Card title="4. Calculation Run" subtitle="StatusはDRAFT / BLOCKED / FINALのみです。"><div className="button-row"><button disabled={!canUse || busy} onClick={() => void createCalculationRun(false)}>DRAFTを作成</button><button disabled={!canUse || busy} onClick={() => void createCalculationRun(true)}>FINALを要求</button></div><p className="hint">必須Dataset: {REQUIRED_DATASETS.length}件。現在のCoverageをSnapshotします。</p><a className="download" href={`${API_BASE}/api/export/annual-summary.csv?userId=${encodeURIComponent(userId)}&targetYear=${Number(opening.asOfDate.slice(0, 4)) + 1}`}>annual-summary.csv</a></Card><Card title="5. Export" subtitle="Raw/Normalizedの追跡情報をCSVで確認します。"><div className="download-list"><Download href={`${API_BASE}/api/export/transactions.csv?userId=${encodeURIComponent(userId)}`} label="transactions.csv" /><Download href={`${API_BASE}/api/export/coverage.csv?userId=${encodeURIComponent(userId)}`} label="coverage.csv" /><Download href={`${API_BASE}/api/export/needs-review.csv?userId=${encodeURIComponent(userId)}`} label="needs-review.csv" /></div></Card></section>

    <section className="grid two"><Card title="Coverage" subtitle="Dataset単位の最新状態"><div className="table-wrap"><table><thead><tr><th>Dataset</th><th>Status</th><th>Actual</th></tr></thead><tbody>{dashboard.coverage.map((item) => <tr key={String(item.id)}><td>{display(item.dataset)}</td><td><Status value={display(item.status)} /></td><td>{display(item.actualFrom)} → {display(item.actualTo)}</td></tr>)}</tbody></table></div></Card><Card title="Needs Review / Error" subtitle="ReviewとErrorは別概念で表示"><ul className="review-list">{dashboard.reviews.map((item) => <li key={String(item.id)}><strong>Review</strong> {display(item.reviewType)} — {display(item.reason)}</li>)}{dashboard.errors.map((item) => <li key={String(item.id)}><strong>Error</strong> {display(item.errorType)} — {display(item.message)}</li>)}{dashboard.reviews.length + dashboard.errors.length === 0 && <li>未解決項目はありません。</li>}</ul></Card></section>

    <section className="card"><div className="card-heading"><div><h2>Timeline</h2><p>Unified TransactionのRaw Data追跡キーを含む時系列です。</p></div></div><div className="table-wrap"><table><thead><tr><th>Time</th><th>Source</th><th>Type</th><th>Asset / Amount</th><th>Hash</th></tr></thead><tbody>{dashboard.timeline.slice(0, 100).map((item) => <tr key={String(item.id)}><td>{display(item.occurredAt)}</td><td>{display(item.source)}</td><td>{display(item.transactionType)}</td><td>{display(item.asset ?? item.assetFrom)} {display(item.grossAmount ?? item.amountFrom ?? item.amountTo)}</td><td className="mono">{display(item.transactionHash)}</td></tr>)}</tbody></table></div></section>
  </main>;
}

function Card({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) { return <section className="card"><div className="card-heading"><div><h2>{title}</h2><p>{subtitle}</p></div></div>{children}</section>; }
function Metric({ label, value, tone = "neutral" }: { label: string; value: string | number; tone?: "neutral" | "good" | "warn" }) { return <div className={`metric ${tone}`}><span>{label}</span><strong>{value}</strong></div>; }
function Status({ value }: { value: string }) { return <span className={`status ${value.toLowerCase()}`}>{value}</span>; }
function Download({ href, label }: { href: string; label: string }) { return <a className="download" href={href}>{label}<span>↓</span></a>; }
