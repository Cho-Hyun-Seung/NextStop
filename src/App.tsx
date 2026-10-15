import { useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { ArrowLeft, ArrowRight, ArrowUpRight, Bell, Bus, Check, ChevronDown, CircleHelp, Clock3, Compass, Flag, Info, MapPin, Moon, Navigation, Pause, Play, Radio, RefreshCw, Search, Settings2, ShieldCheck, Star, Sun, TrainFront, Volume2, WifiOff, X } from 'lucide-react'
import { getRoute, orderedStops, regions, routes, vehicleCandidates } from './data/catalog'
import { advanceRide, createRide, currentLabel, nextStop } from './domain/ride'
import type { AppState, NotificationMode, Page, Preferences, Ride, Route, Setup } from './domain/types'
import { useAppState } from './state/store'
import { Dialog } from './components/Dialog'
import { NotificationPreview } from './components/NotificationPreview'
import { RouteCard } from './components/RouteCard'
import { RouteLine } from './components/RouteLine'
import { TransitIllustration } from './components/TransitIllustration'

type StateSetter = Dispatch<SetStateAction<AppState>>
const navItems = [
  { page: 'home' as const, label: '노선 찾기', icon: Compass },
  { page: 'ride' as const, label: '내 이동', icon: Navigation },
  { page: 'favorites' as const, label: '즐겨찾기', icon: Star },
  { page: 'settings' as const, label: '알림 설정', icon: Bell },
]
const modeLabels: Record<NotificationMode, string> = { all_stops: '모든 도착 알림', alight_only: '하차 알림', silent: '무음 갱신' }
const modes: { value: NotificationMode; title: string; description: string }[] = [
  { value: 'alight_only', title: '하차할 때만 알려 주세요', description: '하차 지점 직전과 도착 시 알려 드려요.' },
  { value: 'all_stops', title: '모든 정류장에서 알려 주세요', description: '정류장·역에 도착할 때마다 알려 드려요.' },
  { value: 'silent', title: '조용히 이동 상황만 볼게요', description: '도착 알림 없이 진행 표시만 갱신해요.' },
]
const time = (timestamp: number) => new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(timestamp)
const stopUnit = (route: Route) => route.mode === 'subway' ? '역' : '정류장'

function parsePage(): Page {
  const value = window.location.hash.slice(2)
  return ['home', 'ride', 'favorites', 'settings'].includes(value) ? value as Page : 'home'
}

export function App() {
  const [state, setState] = useAppState()
  const [page, setPage] = useState<Page>(parsePage)
  const [setup, setSetup] = useState<Setup | null>(null)
  const [infoOpen, setInfoOpen] = useState(false)
  const [endOpen, setEndOpen] = useState(false)
  const [toast, setToast] = useState('')
  const [autoAdvance, setAutoAdvance] = useState(false)
  const [systemDark, setSystemDark] = useState(() => matchMedia('(prefers-color-scheme: dark)').matches)
  const effectiveTheme = state.preferences.theme === 'system' ? (systemDark ? 'dark' : 'light') : state.preferences.theme
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mainRef = useRef<HTMLElement>(null)
  const previousPage = useRef(page)

  function notify(message: string) {
    if (toastTimer.current) clearTimeout(toastTimer.current)
    setToast(message)
    toastTimer.current = setTimeout(() => setToast(''), 4500)
  }

  function navigate(next: Page) {
    window.location.hash = `/${next}`
    setPage(next)
    window.scrollTo({ top: 0, behavior: 'instant' })
  }

  useEffect(() => {
    const handler = () => { setPage(parsePage()); setSetup(null); window.scrollTo(0, 0) }
    window.addEventListener('hashchange', handler)
    return () => window.removeEventListener('hashchange', handler)
  }, [])

  useEffect(() => {
    if (previousPage.current !== page) mainRef.current?.focus({ preventScroll: true })
    previousPage.current = page
  }, [page])

  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const update = () => setSystemDark(media.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  useEffect(() => { document.documentElement.dataset.theme = effectiveTheme }, [effectiveTheme])

  useEffect(() => {
    if (!autoAdvance || !state.ride || state.ride.lifecycle !== 'active' || state.ride.freshness !== 'fresh' || state.ride.currentIndex === getRoute(state.ride.routeId)!.stops.length - 1) return
    const timer = setInterval(() => setState((previous) => ({ ...previous, ride: previous.ride ? advanceRide(previous.ride) : null })), 6500)
    return () => clearInterval(timer)
  }, [autoAdvance, state.ride?.id, state.ride?.lifecycle, state.ride?.freshness, state.ride?.currentIndex, setState])

  useEffect(() => {
    const timer = setInterval(() => setState((previous) => {
      if (!previous.ride || previous.ride.freshness !== 'fresh' || Date.now() - previous.ride.sourceObservedAt < 90_000) return previous
      return { ...previous, ride: { ...previous.ride, freshness: 'stale' } }
    }), 10000)
    return () => clearInterval(timer)
  }, [setState])

  useEffect(() => () => { if (toastTimer.current) clearTimeout(toastTimer.current) }, [])

  function toggleFavorite(id: string) {
    setState((previous) => ({ ...previous, favorites: previous.favorites.includes(id) ? previous.favorites.filter((favorite) => favorite !== id) : [...previous.favorites, id] }))
  }

  function selectRoute(route: Route) {
    setSetup({ routeId: route.id, direction: 'forward', boardingIndex: route.defaultBoarding, alightIndex: Math.min(route.defaultBoarding + 4, route.stops.length - 1), vehicle: null, stage: 'route', mode: state.preferences.mode })
  }

  function startRide() {
    if (!setup) return
    const route = getRoute(setup.routeId)
    if (!route) return
    try {
      const ride = createRide(route, setup)
      setState((previous) => ({ ...previous, ride, recent: [route.id, ...previous.recent.filter((id) => id !== route.id)].slice(0, 4) }))
      setSetup(null)
      setAutoAdvance(false)
      navigate('ride')
      notify('이동 체험을 시작했어요. 아래 버튼으로 다음 구간을 확인해 보세요.')
    } catch (error) { notify(error instanceof Error ? error.message : '추적을 시작할 수 없어요.') }
  }

  function endRide() {
    setState((previous) => ({ ...previous, ride: null }))
    setAutoAdvance(false)
    setEndOpen(false)
    navigate('home')
    notify('이동을 마쳤어요. 오늘도 편안한 하루 보내세요.')
  }

  const active = state.ride?.lifecycle === 'active' ? state.ride : null
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content" onClick={(event) => { event.preventDefault(); mainRef.current?.focus({ preventScroll: true }); window.scrollTo(0, 0) }}>본문으로 건너뛰기</a>
      <aside className="sidebar">
        <a className="brand" href="#/home" aria-label="NextStop 홈"><span className="brand-mark">N<span /></span><span>nextstop<span className="brand-period">.</span></span></a>
        <span className="sidebar-caption">다음 정류장까지 함께</span>
        <nav className="main-nav" aria-label="주요 메뉴">{navItems.map(({ page: destination, label, icon: Icon }) => <a key={destination} href={`#/${destination}`} className={page === destination ? 'active' : ''} aria-current={page === destination ? 'page' : undefined}><Icon size={20} /><span>{label}</span>{destination === 'ride' && active && <span className="nav-active-dot" aria-label="이동 중" />}</a>)}</nav>
        <div className="sidebar-bottom"><div className="sidebar-route-art" aria-hidden="true"><span /><i /><span /><i /><span className="filled" /><i /><span /></div><p>창밖을 보는 여유,<br />다음 정류장은 우리가.</p><button type="button" className="demo-badge" onClick={() => setInfoOpen(true)}><span className="status-dot" />체험 모드<Info size={13} /></button></div>
      </aside>
      <div className="workspace">
        <header className="topbar"><div className="mobile-brand"><a href="#/home" aria-label="NextStop 홈"><span className="brand-mark">N<span /></span>nextstop.</a></div><span className="topbar-location"><MapPin size={16} />전국 대중교통</span><div className="topbar-actions"><button type="button" className="sample-indicator" onClick={() => setInfoOpen(true)}><span className="status-dot" />예시 데이터<Info size={14} /></button><button type="button" className="icon-button" aria-label={effectiveTheme === 'dark' ? '밝은 화면으로 변경' : '어두운 화면으로 변경'} onClick={() => setState((previous) => ({ ...previous, preferences: { ...previous.preferences, theme: effectiveTheme === 'dark' ? 'light' : 'dark' } }))}>{effectiveTheme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}</button></div></header>
        <main ref={mainRef} id="main-content" className="main-content" tabIndex={-1}>
          {page === 'home' && <HomePage state={state} onFavorite={toggleFavorite} onSelect={selectRoute} onNavigate={navigate} />}
          {page === 'favorites' && <FavoritesPage state={state} onFavorite={toggleFavorite} onSelect={selectRoute} onNavigate={navigate} />}
          {page === 'ride' && <RidePage state={state} setState={setState} autoAdvance={autoAdvance} onAutoAdvance={setAutoAdvance} onEnd={() => setEndOpen(true)} onSelect={selectRoute} onNavigate={navigate} />}
          {page === 'settings' && <SettingsPage state={state} setState={setState} notify={notify} />}
          <footer className="page-footer"><span>NEXTSTOP</span><span>당신의 이동에 작은 안심을 더해요.</span><span>프론트엔드 미리보기</span></footer>
        </main>
      </div>
      <nav className="mobile-nav" aria-label="모바일 주요 메뉴">{navItems.map(({ page: destination, label, icon: Icon }) => <a key={destination} href={`#/${destination}`} className={page === destination ? 'active' : ''} aria-current={page === destination ? 'page' : undefined}><Icon size={21} /><span>{label}</span></a>)}</nav>
      {setup && <SetupDialog setup={setup} setSetup={setSetup} onClose={() => setSetup(null)} onStart={startRide} hasActiveRide={!!active} />}
      {infoOpen && <Dialog title="지금은 화면을 체험하는 단계예요" onClose={() => setInfoOpen(false)}><div className="dialog-body info-dialog"><span className="large-icon"><Compass size={30} /></span><p>노선 검색부터 탑승 차량 선택, 이동 추적까지 NextStop의 사용 흐름을 먼저 확인할 수 있어요.</p><div className="info-box"><Info size={18} /><p>노선과 차량은 예시 데이터입니다. 실제 위치·시간표·도착 정보와 연결되어 있지 않아요.</p></div><p className="muted">도착 알림은 이 웹 화면에서만 재현됩니다. Android 지속 알림과 iOS Live Activity는 별도 모바일 앱 연동 후 제공됩니다.</p><button type="button" className="button button--primary" onClick={() => setInfoOpen(false)}>확인했어요</button></div></Dialog>}
      {endOpen && <Dialog title="이동을 마칠까요?" onClose={() => setEndOpen(false)}><div className="dialog-body"><p>현재 이동 추적과 화면 내 도착 알림을 종료합니다. 새로운 이동은 언제든 시작할 수 있어요.</p><div className="dialog-actions"><button type="button" className="button button--secondary" onClick={() => setEndOpen(false)}>계속 이동하기</button><button type="button" className="button button--primary" onClick={endRide}>하차 완료</button></div></div></Dialog>}
      <div className={`toast ${toast ? 'toast--visible' : ''}`} role="status" aria-live="polite">{toast && <><Check size={18} /><span>{toast}</span><button type="button" className="icon-button" aria-label="안내 닫기" onClick={() => setToast('')}><X size={16} /></button></>}</div>
    </div>
  )
}

function HomePage({ state, onFavorite, onSelect, onNavigate }: { state: AppState; onFavorite: (id: string) => void; onSelect: (route: Route) => void; onNavigate: (page: Page) => void }) {
  const [region, setRegion] = useState('전국')
  const [transport, setTransport] = useState<'all' | 'subway' | 'bus'>('all')
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)
  const normalized = query.trim().toLowerCase().replace(/\s/g, '')
  const filtered = routes.filter((route) => (region === '전국' || route.region === region) && (transport === 'all' || route.mode === transport) && `${route.name}${route.region}${route.operator}${route.stops.join('')}`.toLowerCase().replace(/\s/g, '').includes(normalized))
  const visible = query || expanded || region !== '전국' || transport !== 'all' ? filtered : filtered.slice(0, 4)
  const activeRoute = state.ride ? getRoute(state.ride.routeId) : null
  return (
    <>
      <section className="home-hero"><div className="home-hero__copy"><span className="eyebrow"><span className="eyebrow-line" />YOUR EVERYDAY JOURNEY</span><h1>지금 탄 차,<br />어디쯤 <span>왔을까요?</span></h1><p>버스도 지하철도, 다음 정류장까지.<br />내 이동을 한눈에 확인하세요.</p><button type="button" className="text-link" onClick={() => searchRef.current?.focus()}>나의 노선 찾기 <ArrowRight size={17} /></button></div><TransitIllustration /></section>
      {state.ride && activeRoute && <button type="button" className="active-ride-banner" onClick={() => onNavigate('ride')}><span className="active-ride-banner__icon"><Navigation size={22} /></span><span><span className="eyebrow">진행 중인 이동 체험</span><strong>{activeRoute.name} · {currentLabel(state.ride, activeRoute)}</strong></span><span className="active-ride-banner__action">이동 이어보기 <ArrowUpRight size={18} /></span></button>}
      <div className="home-grid"><section className="route-search"><div className="section-heading"><div><span className="eyebrow">FIND YOUR RIDE</span><h2>어떤 차를 타고 있나요?</h2></div><span className="small-count">{filtered.length}개 예시 노선</span></div><div className="search-controls"><div className="transport-tabs" aria-label="교통수단 선택">{([{ value: 'all', label: '전체', icon: Compass }, { value: 'subway', label: '지하철', icon: TrainFront }, { value: 'bus', label: '버스', icon: Bus }] as const).map(({ value, label, icon: Icon }) => <button type="button" key={value} className={transport === value ? 'selected' : ''} aria-pressed={transport === value} onClick={() => setTransport(value)}><Icon size={17} />{label}</button>)}</div><label className="region-select"><MapPin size={15} /><select aria-label="서비스 지역" value={region} onChange={(event) => setRegion(event.target.value)}>{regions.map((name) => <option key={name}>{name}</option>)}</select><ChevronDown size={14} /></label></div><div className="search-input"><Search size={20} /><input ref={searchRef} type="search" aria-label="노선 또는 정류장 검색" placeholder="노선 번호나 정류장·역 이름을 검색하세요" value={query} onChange={(event) => setQuery(event.target.value)} />{query && <button type="button" className="icon-button" aria-label="검색어 지우기" onClick={() => { setQuery(''); searchRef.current?.focus() }}><X size={17} /></button>}</div>
        {state.recent.length > 0 && <div className="recent-routes"><span><Clock3 size={13} />최근 선택</span>{state.recent.map((id) => { const route = getRoute(id)!; return <button type="button" key={id} onClick={() => onSelect(route)}>{route.region} {route.name}<ArrowUpRight size={12} /></button> })}</div>}
        <div className="route-grid">{visible.map((route) => <RouteCard key={route.id} route={route} favorite={state.favorites.includes(route.id)} onFavorite={onFavorite} onSelect={onSelect} />)}</div>
        {visible.length === 0 && <EmptyState icon="search" title="일치하는 예시 노선이 없어요" description="다른 지역이나 노선 이름으로 검색해 보세요. 현재는 서울·경기 버스와 일부 도시철도 예시를 제공해요." action="검색 초기화" onAction={() => { setQuery(''); setRegion('전국'); setTransport('all') }} />}
        {!expanded && !query && region === '전국' && transport === 'all' && filtered.length > visible.length && <button type="button" className="more-routes" onClick={() => setExpanded(true)}>다른 지역 예시도 보기 <ChevronDown size={16} /></button>}
        <p className="catalog-note"><Info size={13} />차량 추적과 시간표 안내의 예시를 구분해 표시해요.</p>
      </section><aside className="home-aside"><NotificationPreview condensed /><section className="journey-guide"><span className="eyebrow">HOW IT WORKS</span><h3>이동을 시작하는 세 단계</h3><ol><li><span>01</span><div><strong>노선과 방향 선택</strong><p>지금 타고 있는 노선을 찾아요.</p></div></li><li><span>02</span><div><strong>내가 탄 차량 확인</strong><p>차량번호나 행선지로 선택해요.</p></div></li><li><span>03</span><div><strong>다음 정류장 확인</strong><p>이동 상황과 하차 지점을 봐요.</p></div></li></ol></section></aside></div>
    </>
  )
}

function EmptyState({ icon, title, description, action, onAction }: { icon: 'search' | 'star' | 'ride'; title: string; description: string; action: string; onAction: () => void }) {
  const Icon = icon === 'star' ? Star : icon === 'ride' ? Navigation : Search
  return <div className="empty-state"><span className="large-icon"><Icon size={28} /></span><h2>{title}</h2><p>{description}</p><button type="button" className="button button--primary" onClick={onAction}>{action}<ArrowRight size={17} /></button></div>
}

function FavoritesPage({ state, onFavorite, onSelect, onNavigate }: { state: AppState; onFavorite: (id: string) => void; onSelect: (route: Route) => void; onNavigate: (page: Page) => void }) {
  const saved = routes.filter((route) => state.favorites.includes(route.id))
  return <><PageHeading eyebrow="YOUR REGULAR ROUTES" title="자주 타는 노선을 모아두세요" description="별표로 저장한 노선을 더 빠르게 찾을 수 있어요." />{saved.length ? <div className="route-grid favorites-grid">{saved.map((route) => <RouteCard key={route.id} route={route} favorite onFavorite={onFavorite} onSelect={onSelect} />)}</div> : <EmptyState icon="star" title="아직 저장한 노선이 없어요" description="노선 카드의 별표를 누르면 여기에 모아둘게요. 즐겨찾기는 이 기기에 저장돼요." action="노선 찾아보기" onAction={() => onNavigate('home')} />}</>
}

function PageHeading({ eyebrow, title, description }: { eyebrow: string; title: string; description: string }) {
  return <div className="page-heading"><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
}

function SetupDialog({ setup, setSetup, onClose, onStart, hasActiveRide }: { setup: Setup; setSetup: Dispatch<SetStateAction<Setup | null>>; onClose: () => void; onStart: () => void; hasActiveRide: boolean }) {
  const route = getRoute(setup.routeId)!
  const stops = orderedStops(route, setup.direction)
  const [vehicles, setVehicles] = useState(() => vehicleCandidates(route, setup.direction, setup.boardingIndex))
  const [error, setError] = useState('')
  const update = (changes: Partial<Setup>) => { setSetup((previous) => previous ? { ...previous, ...changes } : null); setError('') }
  const Icon = route.mode === 'subway' ? TrainFront : Bus
  function findVehicles() {
    if (setup.mode === 'alight_only' && setup.alightIndex === null) { setError('하차 알림을 받으려면 하차 지점을 선택해 주세요.'); return }
    setVehicles(vehicleCandidates(route, setup.direction, setup.boardingIndex))
    update({ stage: 'vehicle', vehicle: null })
  }
  return <Dialog title={route.capability === 'realtime' ? '내 이동 시작하기' : '노선과 정차 경로'} onClose={onClose} className="setup-dialog"><div className="dialog-body"><div className="setup-route"><span className="transport-icon"><Icon size={24} /></span><div><span className="muted">{route.operator} · {route.serviceType}</span><h3>{route.name}</h3></div><span className="badge badge--blue">예시 데이터</span></div>
    {route.capability === 'realtime' && <div className="setup-steps" aria-label="탑승 설정 단계"><span className={setup.stage === 'route' ? 'current' : 'complete'}><i>{setup.stage === 'vehicle' ? <Check size={12} /> : '1'}</i>노선·방향</span><div /><span className={setup.stage === 'vehicle' ? 'current' : ''}><i>2</i>차량 확인</span><div /><span><i>3</i>이동 시작</span></div>}
    {setup.stage === 'route' ? <>
      <div className="field"><label htmlFor="ride-direction">이동 방향</label><select id="ride-direction" value={setup.direction} onChange={(event) => { const direction = event.target.value as Setup['direction']; const boardingIndex = Math.min(stops.length - 1 - setup.boardingIndex, stops.length - 2); update({ direction, boardingIndex, alightIndex: Math.min(boardingIndex + 4, stops.length - 1), vehicle: null }) }}><option value="forward">{route.directions.forward}</option><option value="reverse">{route.directions.reverse}</option></select></div>
      <div className="setup-fields"><div className="field"><label htmlFor="boarding-stop">탑승 {stopUnit(route)}</label><select id="boarding-stop" value={setup.boardingIndex} onChange={(event) => { const boardingIndex = Number(event.target.value); update({ boardingIndex, alightIndex: setup.alightIndex !== null && setup.alightIndex > boardingIndex ? setup.alightIndex : Math.min(boardingIndex + 4, stops.length - 1), vehicle: null }) }}>{stops.slice(0, -1).map((stop, index) => <option key={`${stop}-${index}`} value={index}>{stop}</option>)}</select></div><div className="field"><label htmlFor="alight-stop">하차 {stopUnit(route)} <span>선택</span></label><select id="alight-stop" value={setup.alightIndex ?? ''} onChange={(event) => update({ alightIndex: event.target.value === '' ? null : Number(event.target.value) })}><option value="">선택하지 않음</option>{stops.map((stop, index) => index > setup.boardingIndex ? <option key={`${stop}-${index}`} value={index}>{stop}</option> : null)}</select></div></div>
      {route.capability === 'realtime' ? <><fieldset className="mode-choices"><legend>도착 알림</legend>{modes.map((mode) => <label key={mode.value} className={`mode-choice ${setup.mode === mode.value ? 'selected' : ''}`}><input type="radio" name="setup-mode" value={mode.value} checked={setup.mode === mode.value} onChange={() => update({ mode: mode.value })} /><span><strong>{modeLabels[mode.value]}</strong><small>{mode.description}</small></span></label>)}</fieldset><p className="form-error" role="alert">{error}</p><button type="button" className="button button--primary button--full" onClick={findVehicles}>탑승 차량 찾기<ArrowRight size={18} /></button></> : <><div className="info-box"><Clock3 size={19} /><p>이 노선은 시간표 안내 예시입니다. 실시간 차량 위치와 도착 알림은 지원하지 않아요.</p></div><div className="stop-list" aria-label="예시 정차 경로">{stops.map((stop, index) => <div key={`${stop}-${index}`} className={index === setup.boardingIndex ? 'current' : ''}><span>{index + 1}</span>{stop}{index === setup.boardingIndex && <small>탑승 지점</small>}</div>)}</div><p className="muted small">정차 경로 예시이며 실제 시간표와 연결되어 있지 않습니다.</p></>}
    </> : <><button type="button" className="text-link setup-back" onClick={() => update({ stage: 'route', vehicle: null })}><ArrowLeft size={15} />노선·방향 다시 선택</button><div className="boarding-summary"><MapPin size={16} /><strong>{stops[setup.boardingIndex]}</strong><span>{route.directions[setup.direction]}</span></div><div className="section-heading vehicle-heading"><div><h3>지금 탄 차량을 확인해 주세요</h3><p>{route.mode === 'bus' ? '버스 차량번호와 대조해 선택하세요.' : '탑승역과 행선지로 열차를 선택하세요.'}</p></div><button type="button" className="icon-button" aria-label="차량 후보 새로고침" onClick={() => { setVehicles(vehicleCandidates(route, setup.direction, setup.boardingIndex)); update({ vehicle: null }) }}><RefreshCw size={18} /></button></div><fieldset className="vehicle-list"><legend className="sr-only">탑승 차량 선택</legend>{vehicles.map((vehicle) => <label key={vehicle.id} className={`vehicle-card ${setup.vehicle?.id === vehicle.id ? 'selected' : ''}`}><input type="radio" name="vehicle" checked={setup.vehicle?.id === vehicle.id} onChange={() => update({ vehicle })} /><span className="vehicle-card__content"><strong>{vehicle.label}</strong><span>{vehicle.detail}</span><span className="vehicle-card__status"><span className="status-dot" />{vehicle.status}<small>기준 {time(vehicle.observedAt)}</small></span></span><span className="vehicle-card__check"><Check size={14} /></span></label>)}</fieldset><div className="info-box"><Info size={16} /><p>차량 후보는 화면 체험을 위한 예시입니다.</p></div>{hasActiveRide && <div className="info-box info-box--warning"><Info size={17} /><p>새 이동을 시작하면 현재 진행 중인 이동 체험은 종료됩니다.</p></div>}<button type="button" className="button button--primary button--full" disabled={!setup.vehicle} onClick={onStart}>{setup.vehicle ? '이동 추적 시작하기' : '탑승 차량을 선택해 주세요'}<Navigation size={18} /></button></>}
  </div></Dialog>
}

function RidePage({ state, setState, autoAdvance, onAutoAdvance, onEnd, onSelect, onNavigate }: { state: AppState; setState: StateSetter; autoAdvance: boolean; onAutoAdvance: (value: boolean) => void; onEnd: () => void; onSelect: (route: Route) => void; onNavigate: (page: Page) => void }) {
  const [showStops, setShowStops] = useState(false)
  const ride = state.ride
  const route = ride ? getRoute(ride.routeId) : null
  if (!ride || !route) return <><PageHeading eyebrow="YOUR JOURNEY" title="다음 정류장까지 함께할게요" description="지금 타고 있는 노선을 선택하고 이동을 시작해 보세요." /><EmptyState icon="ride" title="진행 중인 이동이 없어요" description="노선과 방향을 고른 뒤 탑승 차량을 확인하면 이동 상황을 볼 수 있어요." action="노선 찾아보기" onAction={() => onNavigate('home')} /></>
  const stops = orderedStops(route, ride.direction)
  const remaining = ride.alightIndex === null ? null : Math.max(0, ride.alightIndex - ride.currentIndex)
  const reached = ride.alightIndex !== null && ride.currentIndex === ride.alightIndex && ride.motion === 'arrived'
  const passedAlight = ride.alightIndex !== null && (ride.currentIndex > ride.alightIndex || (ride.currentIndex === ride.alightIndex && ride.motion === 'between'))
  const routeEnded = ride.currentIndex === stops.length - 1
  const Icon = route.mode === 'bus' ? Bus : TrainFront
  const mutateRide = (update: (current: Ride) => Ride) => setState((previous) => ({ ...previous, ride: previous.ride ? update(previous.ride) : null }))
  return <><div className="ride-heading"><PageHeading eyebrow="YOUR JOURNEY" title="지금, 이동 중이에요" description="이동 체험의 현재 위치와 다음 정류장을 확인하세요." /><span className="badge badge--blue"><Radio size={13} />이동 체험</span></div><div className="ride-grid"><div className="ride-main"><section className="journey-card"><div className="journey-card__header"><span className="journey-route"><Icon size={21} /><strong>{route.name}</strong><span>{route.serviceType}</span></span><span className="journey-card__direction">{route.directions[ride.direction]}</span></div><div className="journey-card__location"><span className="journey-status"><span className="status-dot" />{ride.freshness === 'stale' ? '갱신 지연' : ride.freshness === 'lost' ? '차량 정보 없음' : ride.motion === 'between' ? '다음 정류장으로 이동 중' : '도착 상태 예시'}</span><h2>{currentLabel(ride, route)}</h2><p>{ride.freshness !== 'fresh' ? `마지막 확인 위치입니다. 기준 ${time(ride.sourceObservedAt)}` : reached ? '하차 지점이에요. 내릴 준비를 해 주세요.' : `다음 ${stopUnit(route)}은 ${nextStop(ride, route) ?? '없어요. 마지막 지점입니다.'}${nextStop(ride, route) ? '입니다.' : ''}`}</p></div><div className="journey-card__bottom"><span>{ride.vehicleLabel}</span><span><Clock3 size={13} />기준 {time(ride.sourceObservedAt)}</span></div></section>
    <section className="route-panel"><div className="section-heading"><h3>한눈에 보는 내 이동</h3><span className="muted small">진행 방향 <ArrowRight size={14} /></span></div><RouteLine ride={ride} route={route} /><div className="route-legend"><span><i className="legend-past" />지나온 지점</span><span><i className="legend-current" />현재 {ride.motion === 'between' ? '구간' : '위치'}</span><span><i className="legend-next" />다음 지점</span></div></section>
    <div className="ride-metrics"><section><span className="metric-icon"><Flag size={20} /></span><div><span>하차까지</span><strong>{remaining === null ? '하차 지점 미선택' : passedAlight ? '하차 지점을 지났어요' : reached ? '도착했어요' : `${remaining}개 ${stopUnit(route)}`}</strong></div></section><section><span className="metric-icon"><Bell size={20} /></span><div><span>알림 방식</span><strong>{modeLabels[ride.mode]}</strong></div><button type="button" className="icon-button" aria-label="알림 설정 변경" onClick={() => onNavigate('settings')}><Settings2 size={17} /></button></section></div>
    <section className="alight-panel"><div><Flag size={18} /><label htmlFor="change-alight">내릴 {stopUnit(route)}</label></div><select id="change-alight" aria-label="하차 지점 변경" value={ride.alightIndex ?? ''} onChange={(event) => { const alightIndex = event.target.value === '' ? null : Number(event.target.value); mutateRide((current) => ({ ...current, alightIndex, mode: alightIndex === null && current.mode === 'alight_only' ? 'silent' : current.mode, revision: current.revision + 1 })) }}><option value="">선택하지 않음</option>{stops.map((stop, index) => index > ride.currentIndex || index === ride.alightIndex ? <option key={`${stop}-${index}`} value={index}>{stop}</option> : null)}</select></section>
    <section className="demo-controls"><div className="section-heading"><div><span className="eyebrow">TRY THE JOURNEY</span><h3>이동 상황을 체험해 보세요</h3></div><label className="auto-advance"><input type="checkbox" checked={autoAdvance} onChange={(event) => onAutoAdvance(event.target.checked)} />자동 진행</label></div><p>예시 차량을 움직여 노선도와 알림이 바뀌는 모습을 확인할 수 있어요.</p><div className="demo-actions"><button type="button" className="button button--primary" disabled={ride.freshness !== 'fresh' || routeEnded} onClick={() => mutateRide((current) => advanceRide(current))}>{ride.motion === 'arrived' ? <Play size={16} /> : <MapPin size={16} />}{routeEnded ? '마지막 지점입니다' : ride.motion === 'arrived' ? '다음 구간으로 이동' : '다음 지점 도착'}</button><button type="button" className="button button--secondary" onClick={() => { onAutoAdvance(false); mutateRide((current) => ({ ...current, freshness: current.freshness === 'fresh' ? 'stale' : 'fresh', sourceObservedAt: current.freshness === 'fresh' ? current.sourceObservedAt - 120000 : Date.now(), revision: current.revision + 1 })) }}>{ride.freshness === 'fresh' ? <WifiOff size={16} /> : <RefreshCw size={16} />}{ride.freshness === 'fresh' ? '갱신 지연 보기' : '정보 갱신하기'}</button></div>{autoAdvance && <span className="small muted"><Pause size={12} />약 6.5초마다 화면 안에서 진행해요.</span>}</section>
    <section className="full-route"><button type="button" aria-expanded={showStops} className="full-route__toggle" onClick={() => setShowStops(!showStops)}>전체 예시 정차 경로<ChevronDown size={18} className={showStops ? 'rotated' : ''} /></button>{showStops && <div className="stop-list">{stops.map((stop, index) => <div key={`${stop}-${index}`} className={index === ride.currentIndex ? 'current' : index < ride.currentIndex ? 'passed' : ''}><span>{index < ride.currentIndex ? <Check size={12} /> : index + 1}</span>{stop}{index === ride.boardingIndex && <small>탑승</small>}{index === ride.alightIndex && <small>하차</small>}{index === ride.currentIndex && <small>{ride.motion === 'between' ? '출발' : '현재'}</small>}</div>)}</div>}</section>
    <div className="ride-end-actions"><button type="button" className="text-link" onClick={() => onSelect(route)}>차량 다시 선택 <RefreshCw size={14} /></button><button type="button" className="button button--primary" onClick={onEnd}>하차 완료<Check size={17} /></button></div>
    </div><aside className="ride-aside"><NotificationPreview ride={ride} route={route} /><section className="arrival-history"><div className="section-heading"><h3>화면 내 도착 알림</h3><Bell size={18} /></div>{ride.events.length ? <div aria-live="polite" aria-relevant="additions">{[...ride.events].reverse().map((event) => <article className="arrival-event" key={event.id}><span className="arrival-event__icon">{event.type === 'alight_next' ? <Flag size={17} /> : <MapPin size={17} />}</span><div><strong>{event.title}</strong><p>{event.body}</p><small>{time(event.at)} · 예시 알림</small></div></article>)}</div> : <div className="history-empty"><Bell size={25} /><p>{ride.mode === 'silent' ? '무음 모드에서는 진행 표시만 갱신해요.' : ride.mode === 'alight_only' ? '하차 지점 직전과 도착 시 여기에 알려 드릴게요.' : '다음 지점에 도착하면 여기에 알려 드릴게요.'}</p></div>}</section><p className="ride-disclaimer"><ShieldCheck size={16} />이동 체험은 브라우저 안에서만 실행돼요. 실제 위치와 도착을 보장하지 않습니다.</p></aside></div></>
}

function SettingsPage({ state, setState, notify }: { state: AppState; setState: StateSetter; notify: (message: string) => void }) {
  const updatePreferences = (changes: Partial<Preferences>) => setState((previous) => ({ ...previous, preferences: { ...previous.preferences, ...changes } }))
  const ride = state.ride
  const selectedMode = ride?.mode ?? state.preferences.mode
  function selectMode(mode: NotificationMode) {
    if (mode === 'alight_only' && ride && ride.alightIndex === null) { notify('내 이동 화면에서 하차 지점을 먼저 선택해 주세요.'); return }
    setState((previous) => ({ ...previous, preferences: { ...previous.preferences, mode }, ride: previous.ride ? { ...previous.ride, mode, revision: previous.ride.revision + 1 } : null }))
  }
  const activeRoute = ride ? getRoute(ride.routeId) : null
  return <><PageHeading eyebrow="A LITTLE PEACE OF MIND" title="내 이동에 맞는 알림" description="필요한 순간에, 원하는 방식으로 알려 드릴게요." /><div className="settings-grid"><div className="settings-main"><section className="settings-card"><div className="section-heading"><h2>언제 알려 드릴까요?</h2><Bell size={20} /></div><fieldset className="mode-choices"><legend className="sr-only">알림 모드</legend>{modes.map((mode) => <label key={mode.value} className={`mode-choice ${selectedMode === mode.value ? 'selected' : ''}`}><input type="radio" name="notification-mode" checked={selectedMode === mode.value} onChange={() => selectMode(mode.value)} /><span><strong>{mode.title}</strong><small>{mode.description}</small></span><span className="choice-check"><Check size={14} /></span></label>)}</fieldset>{ride && <p className="small muted">현재 이동과 다음 이동의 기본 설정에 함께 적용됩니다.</p>}</section><section className="settings-card"><div className="section-heading"><h2>알림 방식</h2><Volume2 size={20} /></div><Toggle checked={state.preferences.sound} onChange={(checked) => updatePreferences({ sound: checked })} title="알림 소리" description="모바일 앱 연결 시 적용할 설정이에요." /><Toggle checked={state.preferences.vibration} onChange={(checked) => updatePreferences({ vibration: checked })} title="진동" description="이 웹 미리보기에서는 기기를 진동시키지 않아요." /><div className="settings-note"><Info size={16} /><p>이 설정은 기기에 저장됩니다. 실제 소리·진동과 시스템 알림은 모바일 앱에서 연결됩니다.</p></div></section><section className="settings-card"><div className="section-heading"><h2>화면 스타일</h2><Sun size={20} /></div><div className="theme-options" aria-label="화면 테마">{([{ value: 'light', label: '밝게', icon: Sun }, { value: 'dark', label: '어둡게', icon: Moon }, { value: 'system', label: '기기 설정', icon: Settings2 }] as const).map(({ value, label, icon: Icon }) => <button type="button" key={value} className={state.preferences.theme === value ? 'selected' : ''} aria-pressed={state.preferences.theme === value} onClick={() => updatePreferences({ theme: value })}><Icon size={19} />{label}</button>)}</div></section></div><aside className="settings-aside"><NotificationPreview {...(ride && activeRoute ? { ride, route: activeRoute } : {})} /><section className="setting-help"><CircleHelp size={21} /><h3>알림을 볼 수 없다면</h3><p>지금은 시스템 알림의 화면 예시만 제공해요. 실제 알림 권한과 잠금화면 표시는 모바일 앱 연동 단계에서 제공됩니다.</p></section></aside></div></>
}

function Toggle({ checked, onChange, title, description }: { checked: boolean; onChange: (checked: boolean) => void; title: string; description: string }) {
  return <label className="toggle-row"><span><strong>{title}</strong><small>{description}</small></span><input type="checkbox" role="switch" aria-label={title} checked={checked} onChange={(event) => onChange(event.target.checked)} /><span className="toggle-visual" aria-hidden="true"><span /></span></label>
}
