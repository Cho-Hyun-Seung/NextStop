import { ArrowRight, Check, Navigation } from 'lucide-react'
import { currentLabel, nextStop, rideWindow } from '../domain/ride'
import type { Ride, Route } from '../domain/types'

export function RouteLine({ ride, route, compact = false }: { ride: Ride; route: Route; compact?: boolean }) {
  const window = rideWindow(ride, route)
  const label = `${route.name}, ${route.directions[ride.direction]}, ${currentLabel(ride, route)} ${ride.motion === 'between' ? '이동 중' : '도착'}, 다음 ${nextStop(ride, route) ?? '종점'}`
  return (
    <div className={`route-line ${compact ? 'route-line--compact' : ''}`} role="img" aria-label={label}>
      <div className="route-line__track" aria-hidden="true" />
      <div className="route-line__group route-line__group--past" aria-hidden="true">
        {window.previous.map((stop, index) => <div className="route-node route-node--past" key={`${stop}-${index}`}><span className="route-node__dot"><Check size={10} strokeWidth={3} /></span><span className="route-node__name">{stop}</span></div>)}
      </div>
      <div className={`route-node route-node--current ${window.center ? '' : 'route-node--moving'}`} aria-hidden="true">
        <span className="route-node__tag">{window.center ? '현재' : '이동 중'}</span>
        <span className="route-node__dot">{window.center ? <Navigation size={14} fill="currentColor" /> : <ArrowRight size={16} />}</span>
        <span className="route-node__name">{window.center ?? '이동 구간'}</span>
      </div>
      <div className="route-line__group route-line__group--future" aria-hidden="true">
        {window.upcoming.map((stop, index) => <div className={`route-node ${index === 0 ? 'route-node--next' : ''}`} key={`${stop}-${index}`}><span className="route-node__dot" /><span className="route-node__name">{stop}</span></div>)}
      </div>
    </div>
  )
}
