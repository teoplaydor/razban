import { useState, useMemo, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { ServerCard } from '../components/ServerCard';
import { useApp } from '../store/appStore';
import { bridge } from '../api/bridge';
import { Plus, Search, Zap, Cloud, ClipboardPaste, FolderOpen, X, RefreshCw, KeyRound, Loader2 } from 'lucide-react';
import clsx from 'clsx';
import Discovery from './Discovery';
import Subscriptions from './Subscriptions';
import { VpsDeployPanel } from '../components/VpsDeployPanel';
import { ServerManagement } from './ServerManagement';

type ServerTab = 'own' | 'discovery' | 'subscriptions' | 'deploy' | 'manage';

/// Multi-tab Servers page hosting own/discovery/subscriptions views.
/// Discovery and Subscriptions pages used to be separate nav items —
/// merged here so the sidebar stays minimal.
const TAB_ORDER: ServerTab[] = ['own', 'discovery', 'subscriptions', 'deploy', 'manage'];

// Variants + custom prop: AnimatePresence re-evaluates the exit function
// with the LATEST direction value when a pane unmounts. This means when the
// user rapidly flips Own → Discovery → Own, the in-flight Discovery exit
// target recomputes from the NEW direction so both panes slide in the SAME
// horizontal direction — no "одна влево, другая вправо" tearing.
const tabSlideVariants = {
  enter:  (dir: number) => ({ x: dir > 0 ? '100%'  : '-100%' }),
  center: { x: '0%' },
  exit:   (dir: number) => ({ x: dir > 0 ? '-100%' : '100%'  }),
};

// Protocol-filter choices. Wheel-scrolling cycles through them in order.
const PROTOCOL_FILTERS: readonly [string, string][] = [
  ['all',         'Все'],
  ['vless',       'VLESS'],
  ['wireguard',   'WG'],
  ['hysteria2',   'Hy2'],
  ['tuic',        'TUIC'],
  ['shadowsocks', 'SS'],
];
const PROTOCOL_FILTER_KEYS = PROTOCOL_FILTERS.map(([k]) => k);

// Pixel-delta threshold + accumulator state for the wheel-scroll helper.
// Touchpads emit lots of small deltas; mouse wheel emits one ~100 px notch.
// Same constants as Sidebar.
const WHEEL_THRESHOLD = 50;

/// Generic "scroll wheel = next/prev item" hook. Accumulates touchpad
/// micro-deltas; one mouse-notch (~100 px) commits in one tick; resets if
/// the user pauses for >200 ms (new gesture).
function useWheelStep(
  items: readonly string[],
  current: string,
  onChange: (next: string) => void,
) {
  const accumRef = useRef(0);
  const lastAtRef = useRef(0);
  return (e: React.WheelEvent<HTMLElement>) => {
    const now = Date.now();
    if (now - lastAtRef.current > 200) accumRef.current = 0;
    lastAtRef.current = now;
    accumRef.current += e.deltaY;
    if (Math.abs(accumRef.current) < WHEEL_THRESHOLD) return;
    const dir = accumRef.current > 0 ? 1 : -1;
    accumRef.current = 0;
    const idx = items.indexOf(current);
    if (idx < 0) return;
    const next = Math.max(0, Math.min(items.length - 1, idx + dir));
    if (next !== idx) onChange(items[next]);
  };
}

export default function ServersPage() {
  const [tab, setTab] = useState<ServerTab>('own');
  const prevIndex = useRef(TAB_ORDER.indexOf(tab));
  const curIndex = TAB_ORDER.indexOf(tab);
  const direction = curIndex >= prevIndex.current ? 1 : -1;
  // Sync update (not useEffect) so very fast clicks see up-to-date prev idx
  prevIndex.current = curIndex;

  // Wheel-step across the 4 sub-tabs
  const onTabWheel = useWheelStep(TAB_ORDER, tab, (next) => setTab(next as ServerTab));

  return (
    <div className="h-full flex flex-col">
      <header className="px-5 pt-3 pb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between shrink-0">
        <h1 className="text-xl font-bold text-ink-100 shrink-0">Серверы</h1>
        <div onWheel={onTabWheel}
          className="flex bg-ink-800/40 rounded-lg p-0.5 gap-0.5 overflow-x-auto no-scrollbar">
          <SubTab active={tab === 'own'}           onClick={() => setTab('own')}>Собственные</SubTab>
          <SubTab active={tab === 'discovery'}     onClick={() => setTab('discovery')}>Общедоступные</SubTab>
          <SubTab active={tab === 'subscriptions'} onClick={() => setTab('subscriptions')}>Подписки</SubTab>
          <SubTab active={tab === 'deploy'}        onClick={() => setTab('deploy')}>Настроить сервер</SubTab>
          <SubTab active={tab === 'manage'}        onClick={() => setTab('manage')}>Управление</SubTab>
        </div>
      </header>
      <div className="flex-1 min-h-0 overflow-hidden relative">
        {/* mode="sync" + variants. AnimatePresence re-evaluates the exit
            variant with the LATEST custom (direction) at unmount, so a
            mid-flight reversal makes both leaving and arriving panes move
            in the same horizontal direction. */}
        <AnimatePresence mode="sync" initial={false} custom={direction}>
          <motion.div
            key={tab}
            custom={direction}
            variants={tabSlideVariants}
            initial="enter"
            animate="center"
            exit="exit"
            transition={{ duration: 0.45, ease: [0.32, 0.72, 0, 1] }}
            className="absolute inset-0 h-full w-full"
          >
            {tab === 'own' && <OwnServers />}
            {tab === 'discovery' && <Discovery />}
            {tab === 'subscriptions' && <Subscriptions />}
            {tab === 'deploy' && <VpsDeployPanel />}
            {tab === 'manage' && <ServerManagement />}
          </motion.div>
        </AnimatePresence>
      </div>
    </div>
  );
}

function SubTab({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button onClick={onClick}
      className={clsx(
        "px-3 py-1 rounded-md text-[12px] font-semibold transition shrink-0 whitespace-nowrap",
        active ? 'bg-white/[0.08] text-ink-100 shadow-sm' : 'text-ink-400 hover:text-ink-200'
      )}>
      {children}
    </button>
  );
}

export function OwnServers() {
  const allServers = useApp(s => s.servers);
  const bundles = useApp(s => s.bundles);
  const refreshBundles = useApp(s => s.refreshBundles);
  const groups = useApp(s => s.groups);
  const settings = useApp(s => s.settings);
  // "Own" tab = servers user added explicitly (group "Imported") OR pulled in
  // from a subscription. Excludes discovery-scanned bulk servers.
  const servers = useMemo(() => {
    const ownGroups = new Set(
      groups.filter(g => g.name === 'Imported' || g.name?.toLowerCase().startsWith('sub-'))
            .map(g => g.id)
    );
    return allServers.filter(s =>
      // explicit "own" group
      (s.groupId && ownGroups.has(s.groupId)) ||
      // came from a subscription
      !!s.subscriptionId
    );
  }, [allServers, groups]);
  const refreshServers = useApp(s => s.refreshServers);
  const [query, setQuery] = useState('');
  const [showAdd, setShowAdd] = useState(false);
  const [showCode, setShowCode] = useState(false);
  const [filter, setFilter] = useState<'all' | 'vless' | 'wireguard' | 'hysteria2' | 'tuic' | 'shadowsocks'>('all');
  // Wheel-cycle through PROTOCOL_FILTERS in their declared order
  const onFilterWheel = useWheelStep(PROTOCOL_FILTER_KEYS, filter,
    (next) => setFilter(next as any));
  // Always show all servers — dead ones are greyed-out instead of hidden
  const onlyAlive = false;
  const [profiles, setProfiles] = useState<{name: string; kind: string; active?: boolean; summary?: string}[]>([]);

  // Auto-refresh on mount + every 5s while page is visible
  useEffect(() => {
    refreshServers();
    refreshBundles();
    const t = setInterval(() => { refreshServers(); refreshBundles(); }, 5000);
    return () => clearInterval(t);
  }, [refreshServers, refreshBundles]);

  useEffect(() => {
    const load = async () => {
      try {
        const res = await bridge.call<{ profiles?: {name: string; kind: string; active?: boolean; summary?: string}[] }>('profiles.list');
        setProfiles(res?.profiles ?? []);
      } catch {
        /* desktop builds may not implement profiles.list */
      }
    };
    void load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  const filtered = useMemo(() => {
    return servers
      .filter(s => {
        if (filter !== 'all' && String(s.protocol).toLowerCase() !== filter) return false;
        if (query && !`${s.name} ${s.server} ${s.countryName ?? ''} ${s.countryCode ?? ''}`.toLowerCase().includes(query.toLowerCase())) return false;
        return true;
      })
      // Alive first, dead at the end (grey + "не отвечает" overlay)
      .sort((a, b) => {
        const aAlive = a.pingMs > 0; const bAlive = b.pingMs > 0;
        if (aAlive !== bAlive) return aAlive ? -1 : 1;
        return (a.pingMs > 0 ? a.pingMs : Number.MAX_SAFE_INTEGER) -
               (b.pingMs > 0 ? b.pingMs : Number.MAX_SAFE_INTEGER);
      });
  }, [servers, filter, query]);

  const importFromClipboard = async () => {
    const text = await bridge.call<string>('clipboard.read');
    if (!text) return;
    const res = await bridge.call<{ count: number }>('servers.addMany', { text });
    await refreshServers();
    alert(`Добавлено серверов: ${res.count}`);
  };

  const importFromFile = async () => {
    try {
      await bridge.call('files.importDocument');
    } catch (e) {
      alert('Не удалось открыть выбор файла');
    }
  };

  const selectProfile = async (name: string) => {
    try {
      const res = await bridge.call<{ ok?: boolean; error?: string }>('profiles.select', { name });
      if (res?.error === 'disconnect_required') {
        alert('Сначала отключите VPN, чтобы сменить профиль');
        return;
      }
      await refreshServers();
    } catch {
      /* ignore */
    }
  };

  const aliveCount = servers.filter(s => s.pingMs > 0).length;
  return (
    <div className="h-full flex flex-col">
      <header className="px-5 pt-3 pb-3">
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-[11px] text-ink-400 shrink-0">
            {servers.length} сохранено · {aliveCount} доступно
          </p>
          {profiles.length > 0 && (
            <div className="flex flex-wrap gap-1.5 w-full order-4" aria-label="Профили">
              {profiles.map(p => (
                <button key={p.name} onClick={() => selectProfile(p.name)}
                  aria-pressed={!!p.active}
                  className={clsx(
                    "text-[11px] px-2 py-1 rounded font-medium transition",
                    p.active ? "bg-emerald-500/20 text-emerald-200" : "bg-ink-900/40 text-ink-300 hover:text-ink-100"
                  )}>
                  {p.name}{p.active ? ' · активен' : ''}
                </button>
              ))}
            </div>
          )}
          {/* ml-auto keeps the buttons on the first wrap row (next to the count)
              on a phone, so "Ввести код" is never pushed off-screen. */}
          <div className="flex items-center gap-1.5 ml-auto shrink-0 order-2 md:order-3">
            <button onClick={importFromClipboard} className="btn-secondary text-xs"
              aria-label="Из буфера"
              title="Вставить URI, JSON outbounds или razbanProfile из буфера">
              <ClipboardPaste size={13} /> Из буфера
            </button>
            <button onClick={importFromFile} className="btn-secondary text-xs"
              aria-label="Из файла"
              title="Импортировать профиль из файла (JSON или TXT)">
              <FolderOpen size={13} /> Из файла
            </button>
            <button onClick={() => setShowCode(true)} className="btn-secondary text-xs"
              title="Добавить сервер по коду доступа">
              <KeyRound size={13} /> Ввести код
            </button>
            <button onClick={() => setShowAdd(true)} className="btn-secondary text-xs">
              Добавить
            </button>
          </div>
          <div className="flex items-center gap-2 w-full md:flex-1 md:w-auto order-3 md:order-2">
            <div className="relative flex-1 min-w-0 md:max-w-sm md:ml-3">
              <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-ink-500" />
              <input value={query} onChange={e => setQuery(e.target.value)}
                className="input pl-8 py-1.5 text-xs w-full" placeholder="Поиск..." />
            </div>
            <div onWheel={onFilterWheel}
              className="hidden md:flex items-center gap-0.5 bg-ink-900/40 rounded-lg p-0.5">
              {PROTOCOL_FILTERS.map(([k, label]) => (
                <button key={k} onClick={() => setFilter(k as any)}
                  className={clsx(
                    "text-[11px] px-2 py-1 rounded font-medium transition",
                    filter === k ? 'bg-white/10 text-ink-100' : 'text-ink-400 hover:text-ink-200'
                  )}>{label}</button>
              ))}
            </div>
          </div>
        </div>
      </header>

      <div className="flex-1 overflow-auto px-8 pb-8 space-y-5">
        {/* Razban-deployed bundle cards — multi-protocol, drawn distinct from regular servers */}
        {bundles.length > 0 && (
          <div>
            <div className="text-[10px] uppercase tracking-widest text-ink-500 mb-2">
              Razban-серверы ({bundles.length}) — мульти-протокол
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {bundles.map(b => (
                <BundleCard key={b.id} bundle={b}
                  isActive={b.id === settings?.activeServerId}
                  onRefresh={refreshBundles} />
              ))}
            </div>
          </div>
        )}

        {filtered.length === 0 && bundles.length === 0 ? (
          <EmptyServers onAdd={() => setShowAdd(true)} />
        ) : (
          filtered.length > 0 && <div>
            {bundles.length > 0 && (
              <div className="text-[10px] uppercase tracking-widest text-ink-500 mb-2">
                Одиночные серверы ({filtered.length})
              </div>
            )}
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {filtered.map(s => (
                <ServerCard key={s.id} server={s} isActive={s.id === settings?.activeServerId} />
              ))}
            </div>
          </div>
        )}
      </div>

      {showAdd && <AddServerModal onClose={() => setShowAdd(false)} onAdded={refreshServers} />}
      {showCode && <CodeModal onClose={() => setShowCode(false)} onDone={async () => { await refreshServers(); await refreshBundles(); }} />}
    </div>
  );
}

/// Premium-tier card for Razban-deployed multi-protocol bundles.
///
/// Visually loud on purpose: this is the user's OWN deployed server, not
/// some scraped public proxy. Big card, half-flag background like premium
/// servers, animated shimmer ring around the active selection, live per-
/// protocol latency that refreshes every 15 s (calls `bundles.health` —
/// the bridge does parallel TCP/UDP probes from the user's current network).
///
/// Click → selects this bundle as active server (Connect uses it next).
/// Hover delete button → drops local record only (sing-box on VPS keeps running).
function BundleCard({ bundle, isActive, onRefresh }: {
  bundle: import('../store/appStore').BundleSummary;
  isActive: boolean;
  onRefresh: () => Promise<void>;
}) {
  const [health, setHealth] = useState<Record<string, { ok: boolean; latencyMs: number; pinging?: boolean }>>({});
  const [pinging, setPinging] = useState(false);

  // Live per-endpoint health refresh — initial probe on mount, then every 15s.
  // The backend pings each endpoint's TCP port directly (works even before the
  // VPN is up), so a just-added (by-code) server shows real latency. While the
  // backend reports any endpoint as still `pinging`, we re-poll every 2s so the
  // spinner resolves quickly instead of waiting the full 15s cycle.
  useEffect(() => {
    let cancelled = false;
    let fastRetry: ReturnType<typeof setTimeout> | undefined;
    const probe = async () => {
      setPinging(true);
      try {
        const r = await bridge.call<{ endpoints?: { tag: string; ok: boolean; latencyMs: number; pinging?: boolean }[] }>(
          'bundles.health', { id: bundle.id }, 12_000);
        if (cancelled) return;
        const map: Record<string, { ok: boolean; latencyMs: number; pinging?: boolean }> = {};
        for (const e of r.endpoints ?? []) map[e.tag] = { ok: e.ok, latencyMs: e.latencyMs, pinging: e.pinging };
        setHealth(map);
        if ((r.endpoints ?? []).some(e => e.pinging)) {
          fastRetry = setTimeout(probe, 2000);
        }
      } catch { /* keep prior state */ }
      finally { if (!cancelled) setPinging(false); }
    };
    void probe();
    const t = setInterval(probe, 15_000);
    return () => { cancelled = true; clearInterval(t); clearTimeout(fastRetry); };
  }, [bundle.id]);

  const setSelected = async () => {
    try {
      await bridge.call('servers.setActive', { id: bundle.id });
      await useApp.getState().refreshSettings();
    } catch (e) {
      alert('Не удалось выбрать сервер: ' + (e as Error).message);
    }
  };
  const remove = async () => {
    if (!confirm(`Удалить сервер «${bundle.label || bundle.host}»? На самом VPS sing-box продолжит работать — удалится только локальная запись в Razban.`)) return;
    try {
      await bridge.call('bundles.delete', { id: bundle.id });
      await onRefresh();
    } catch (e) {
      alert('Ошибка: ' + (e as Error).message);
    }
  };

  // Protocol colour mapping — matches Управление + deploy result palette
  const protoColor: Record<string, { bg: string; text: string; dot: string }> = {
    vless:     { bg: 'bg-accent-500/15',   text: 'text-accent-300',   dot: 'bg-accent-400' },
    hysteria2: { bg: 'bg-amber-500/15',    text: 'text-amber-300',    dot: 'bg-amber-400' },
    anytls:    { bg: 'bg-purple-500/15',   text: 'text-purple-300',   dot: 'bg-purple-400' },
    shadowtls: { bg: 'bg-cyan-500/15',     text: 'text-cyan-300',     dot: 'bg-cyan-400' },
    trojan:    { bg: 'bg-rose-500/15',     text: 'text-rose-300',     dot: 'bg-rose-400' },
  };
  const protoLabel: Record<string, string> = {
    vless: 'VLESS+Reality',
    hysteria2: 'Hysteria2',
    anytls: 'AnyTLS',
    shadowtls: 'ShadowTLS',
    trojan: 'Trojan',
  };

  const aliveCount = bundle.endpoints.filter(ep => health[ep.tag]?.ok).length;
  const totalCount = bundle.endpoints.length;

  return (
    <motion.div
      onClick={setSelected}
      initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }}
      className={clsx(
        "group relative cursor-pointer overflow-hidden rounded-2xl",
        "min-h-[200px] p-5",
        "transition-all duration-300",
        isActive
          ? "ring-2 ring-accent-400 shadow-[0_0_40px_-8px_rgba(96,165,250,0.6)]"
          : "ring-1 ring-white/5 hover:ring-white/15 hover:shadow-[0_0_30px_-10px_rgba(96,165,250,0.3)]",
        "bg-gradient-to-br from-ink-800/90 via-ink-900/95 to-black/90"
      )}>

      {/* Shimmer ring — premium feel, only when active or hovered */}
      {isActive && (
        <div className="absolute inset-0 pointer-events-none rounded-2xl overflow-hidden">
          <motion.div
            className="absolute inset-0 rounded-2xl"
            style={{
              background: 'linear-gradient(90deg, transparent, rgba(96,165,250,0.18) 50%, transparent)',
            }}
            animate={{ x: ['-100%', '100%'] }}
            transition={{ duration: 3.5, repeat: Infinity, ease: 'linear' }}
          />
        </div>
      )}

      {/* Half-flag background — like premium server cards */}
      {bundle.countryCode && (
        <div className="absolute right-0 top-0 bottom-0 w-2/5 pointer-events-none opacity-[0.08]"
          style={{
            background: `url(/flags/${bundle.countryCode.toLowerCase()}.svg) no-repeat center right`,
            backgroundSize: 'auto 110%',
            maskImage: 'linear-gradient(to right, transparent, black 60%)',
            WebkitMaskImage: 'linear-gradient(to right, transparent, black 60%)',
          }} />
      )}

      <div className="relative z-10 flex flex-col h-full">
        {/* Header: label + country + active badge */}
        <div className="flex items-start justify-between gap-3 mb-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2.5 mb-1">
              {bundle.countryCode && (
                <span className={`fi fi-${bundle.countryCode.toLowerCase()} rounded shadow-md shrink-0`}
                      style={{ width: 24, height: 18 }} />
              )}
              <h3 className="text-base font-bold text-ink-100 truncate tracking-tight">
                {bundle.label || bundle.host}
              </h3>
              {isActive && (
                <motion.span
                  initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }}
                  className="text-[9px] uppercase tracking-[0.2em] text-accent-300 font-black shrink-0 ml-auto">
                  ▸ активен
                </motion.span>
              )}
            </div>
            <div className="flex items-center gap-2 text-[10px] text-ink-500">
              <span className="font-mono">{bundle.host}</span>
              {bundle.countryName && <><span>·</span><span>{bundle.countryName}</span></>}
              {bundle.deployedByRazban && (
                <><span>·</span><span className="text-accent-400/80 uppercase tracking-wider">★ razban</span></>
              )}
            </div>
          </div>
          <button onClick={(e) => { e.stopPropagation(); void remove(); }}
            className="opacity-0 group-hover:opacity-100 p-1.5 rounded hover:bg-danger-500/20 text-ink-400 hover:text-danger-300 transition shrink-0"
            title="Удалить (sing-box на VPS продолжит работать)">
            <X size={14} />
          </button>
        </div>

        {/* Per-protocol live pings — the focal point of the card */}
        <div className="grid grid-cols-2 gap-1.5 mb-3">
          {bundle.endpoints.map(ep => {
            const h = health[ep.tag];
            const c = protoColor[ep.protocol] ?? protoColor.vless;
            const isPinging = h?.pinging ?? false;
            const known = h !== undefined && !isPinging;
            const alive = h?.ok ?? false;
            const ms = h?.latencyMs ?? -1;
            return (
              <div key={ep.tag}
                className={clsx(
                  "flex items-center gap-2 px-2.5 py-1.5 rounded-lg",
                  "transition-colors",
                  known && alive ? c.bg : known && !alive ? "bg-danger-500/10" : "bg-white/[0.03]"
                )}
                title={`${ep.tag} :${ep.port}`}>
                <span className={clsx(
                  "w-1.5 h-1.5 rounded-full shrink-0",
                  known && alive ? c.dot : isPinging ? "bg-warn-400 animate-pulse" : known && !alive ? "bg-danger-400" : "bg-ink-500"
                )} />
                <span className={clsx(
                  "text-[10px] font-bold uppercase tracking-wider",
                  known && alive ? c.text : known && !alive ? "text-danger-300" : "text-ink-400"
                )}>
                  {protoLabel[ep.protocol] ?? ep.protocol}
                </span>
                <span className="ml-auto text-[10px] font-mono text-ink-400 tabular-nums flex items-center gap-1">
                  {isPinging
                    ? <><Loader2 size={10} className="animate-spin" /> пинг…</>
                    : known
                      ? alive
                        ? (ms < 0 ? 'udp✓' : `${ms} ms`)
                        : '× нет'
                      : (pinging ? '…' : '?')}
                </span>
              </div>
            );
          })}
        </div>

        {/* Footer: alive count + visual hint */}
        <div className="flex items-center justify-between mt-auto pt-2 text-[10px]">
          <div className="text-ink-500">
            <span className={clsx(
              "font-bold tabular-nums",
              aliveCount === totalCount ? "text-accent-300" :
              aliveCount > 0 ? "text-amber-300" : "text-ink-500"
            )}>{aliveCount}/{totalCount}</span>{' '}
            протоколов доступно
          </div>
          {isActive ? (
            <span className="text-accent-400 font-bold uppercase tracking-widest text-[9px]">
              готов к подключению
            </span>
          ) : (
            <span className="text-ink-500 uppercase tracking-widest text-[9px]">
              нажми чтобы выбрать
            </span>
          )}
        </div>
      </div>
    </motion.div>
  );
}

function EmptyServers({ onAdd }: { onAdd: () => void }) {
  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}
      className="h-full flex flex-col items-center justify-center text-center max-w-md mx-auto py-20">
      <div className="w-20 h-20 rounded-2xl bg-ink-800/60 border border-white/5 mb-4 flex items-center justify-center">
        <Cloud size={36} className="text-ink-400" />
      </div>
      <h3 className="text-lg font-semibold text-ink-100">Нет серверов</h3>
      <p className="text-sm text-ink-400 mt-1 mb-4">
        Добавьте свой первый сервер через подписку, ссылку vless://, vmess:// или используйте бесплатный Cloudflare WARP.
      </p>
      <button onClick={onAdd} className="btn-primary"><Plus size={14} /> Добавить сервер</button>
    </motion.div>
  );
}

function CodeModal({ onClose, onDone }: { onClose: () => void; onDone: () => Promise<void> }) {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState(false);

  const submit = async () => {
    const c = code.trim();
    if (!c || busy) return;
    setBusy(true); setErr(null);
    try {
      const res = await bridge.call<{ ok: boolean; outbounds: number }>('code.redeem', { code: c }, 25000);
      if (!res?.ok) throw new Error('Не удалось активировать код');
      setOk(true);
      await onDone();
      setTimeout(onClose, 900);
    } catch (e) {
      setErr((e as Error).message || 'Ошибка');
    } finally { setBusy(false); }
  };

  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      onClick={onClose}>
      <motion.div initial={{ scale: 0.95, y: 8 }} animate={{ scale: 1, y: 0 }}
        className="glass-card w-full max-w-sm p-5" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-semibold text-ink-100 flex items-center gap-2">
            <KeyRound size={16} className="text-accent-400" /> Сервер по коду
          </h3>
          <button onClick={onClose} className="text-ink-400 hover:text-ink-200"><X size={16} /></button>
        </div>
        <p className="text-xs text-ink-400 mb-3">
          Введите код доступа — сервер подтянется и зашифрованно загрузится автоматически.
        </p>
        <input autoFocus value={code} onChange={e => { setCode(e.target.value); setErr(null); }}
          onKeyDown={e => { if (e.key === 'Enter') submit(); }}
          placeholder="например: hub"
          className="input w-full text-center tracking-[0.3em] uppercase font-bold text-lg py-2.5" />
        {err && <p className="text-xs text-danger-400 mt-2 text-center">{err}</p>}
        {ok && <p className="text-xs text-emerald-400 mt-2 text-center">✓ Сервер добавлен</p>}
        <button onClick={submit} disabled={busy || !code.trim()}
          className="btn-primary w-full mt-4 disabled:opacity-50">
          {busy ? 'Подключаюсь…' : ok ? 'Готово' : 'Активировать'}
        </button>
      </motion.div>
    </motion.div>
  );
}

function AddServerModal({ onClose, onAdded }: { onClose: () => void; onAdded: () => Promise<void> }) {
  const [tab, setTab] = useState<'uri' | 'paste'>('uri');
  const [uri, setUri] = useState('');
  const [bulk, setBulk] = useState('');
  const [busy, setBusy] = useState(false);

  const addOne = async () => {
    if (!uri.trim()) return;
    setBusy(true);
    try {
      const p = await bridge.call('servers.add', { uri: uri.trim() });
      if (!p) alert('Не удалось распознать URI'); else { await onAdded(); onClose(); }
    } finally { setBusy(false); }
  };
  const addMany = async () => {
    if (!bulk.trim()) return;
    setBusy(true);
    try {
      const { count } = await bridge.call<{ count: number }>('servers.addMany', { text: bulk });
      await onAdded();
      alert(`Добавлено: ${count}`);
      onClose();
    } finally { setBusy(false); }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }}
        className="glass-card w-full max-w-2xl p-6 relative">
        <button onClick={onClose} className="absolute top-4 right-4 btn-ghost p-1.5"><X size={16} /></button>
        <h3 className="text-lg font-bold text-ink-100">Добавить сервер</h3>
        <div className="flex gap-1 my-4 bg-ink-900/40 rounded-lg p-1 w-fit">
          {(['uri', 'paste'] as const).map(t => (
            <button key={t} onClick={() => setTab(t)} className={clsx(
              "text-xs px-3 py-1.5 rounded-md font-medium",
              tab === t ? 'bg-white/10 text-ink-100' : 'text-ink-400 hover:text-ink-200'
            )}>{t === 'uri' ? 'Один URI' : 'Массово (список)'}</button>
          ))}
        </div>
        {tab === 'uri' ? (
          <div>
            <label className="label">URI ссылка</label>
            <input value={uri} onChange={e => setUri(e.target.value)}
              placeholder="vless://uuid@host:port?..." className="input font-mono text-xs" />
            <p className="text-[11px] text-ink-500 mt-2">
              Поддерживаются: vless://, vmess://, ss://, trojan://, hysteria2://, tuic://, wireguard://, anytls://
            </p>
            <div className="flex gap-2 mt-4">
              <button onClick={addOne} disabled={busy} className="btn-primary">Добавить</button>
              <button onClick={onClose} className="btn-ghost">Отмена</button>
            </div>
          </div>
        ) : (
          <div>
            <label className="label">Список (URI через перенос строки или base64 подписки)</label>
            <textarea value={bulk} onChange={e => setBulk(e.target.value)} rows={8}
              placeholder="vless://...&#10;vmess://...&#10;hysteria2://..."
              className="input font-mono text-xs resize-none" />
            <div className="flex gap-2 mt-4">
              <button onClick={addMany} disabled={busy} className="btn-primary">Импортировать</button>
              <button onClick={onClose} className="btn-ghost">Отмена</button>
            </div>
          </div>
        )}
      </motion.div>
    </div>
  );
}
