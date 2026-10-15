import { ArrowUpRight, Bus, Clock3, Radio, Star, TrainFront } from 'lucide-react'
import type { Route } from '../domain/types'

export function RouteCard({ route, favorite, onFavorite, onSelect }: { route: Route; favorite: boolean; onFavorite: (id: string) => void; onSelect: (route: Route) => void }) {
  const Icon = route.mode === 'subway' ? TrainFront : Bus
  return (
    <article className="route-card">
      <div className="route-card__top"><span className={`transport-icon transport-icon--${route.mode}`}><Icon size={23} /></span><span className={`badge ${route.capability === 'realtime' ? 'badge--blue' : 'badge--neutral'}`}>{route.capability === 'realtime' ? <Radio size={12} /> : <Clock3 size={12} />}{route.capability === 'realtime' ? '추적 예시' : '시간표 안내'}</span><button type="button" className={`icon-button favorite-button ${favorite ? 'is-favorite' : ''}`} aria-label={`${route.region} ${route.name} 즐겨찾기 ${favorite ? '해제' : '추가'}`} aria-pressed={favorite} onClick={() => onFavorite(route.id)}><Star size={19} fill={favorite ? 'currentColor' : 'none'} /></button></div>
      <button type="button" className="route-card__select" onClick={() => onSelect(route)} aria-label={`${route.region} ${route.name} 선택`}>
        <span className="route-card__operator">{route.operator} <span>· {route.serviceType}</span></span>
        <span className="route-card__name"><span className="line-dot" style={{ background: route.color }} />{route.name}<ArrowUpRight size={21} /></span>
        <span className="route-card__description">{route.description}</span>
        <span className="route-card__bottom">{route.capability === 'realtime' ? '탑승 차량 선택하기' : '정차 경로 확인하기'}<span>→</span></span>
      </button>
    </article>
  )
}
