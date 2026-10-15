import { useState } from 'react'
import { Bell, Bus, ChevronRight, TrainFront } from 'lucide-react'
import { routes, orderedStops } from '../data/catalog'
import { currentLabel, nextStop } from '../domain/ride'
import type { Ride, Route } from '../domain/types'
import { RouteLine } from './RouteLine'

const exampleRide: Ride = {
  id: 'preview-only', routeId: 'seoul-2', direction: 'forward', vehicleId: 'example', vehicleLabel: '예시 열차 2014',
  boardingIndex: 5, currentIndex: 6, alightIndex: 9, motion: 'arrived', freshness: 'fresh', mode: 'alight_only',
  revision: 1, startedAt: 0, sourceObservedAt: 0, lifecycle: 'active', events: [],
}

export function NotificationPreview({ ride = exampleRide, route = routes[0], condensed = false }: { ride?: Ride; route?: Route; condensed?: boolean }) {
  const [platform, setPlatform] = useState<'ios' | 'android'>('ios')
  const Icon = route.mode === 'bus' ? Bus : TrainFront
  const stops = orderedStops(route, ride.direction)
  const remaining = ride.alightIndex === null ? null : Math.max(0, ride.alightIndex - ride.currentIndex)
  return (
    <section className={`notification-preview ${condensed ? 'notification-preview--condensed' : ''}`} aria-label="시스템 알림 미리보기">
      <div className="section-heading"><div><span className="eyebrow">AT A GLANCE</span><h3>앱을 닫아도, 한눈에</h3></div><Bell size={20} /></div>
      <div className="preview-tabs" aria-label="알림 플랫폼">
        <button type="button" className={platform === 'ios' ? 'selected' : ''} aria-pressed={platform === 'ios'} onClick={() => setPlatform('ios')}>iOS Live Activity</button>
        <button type="button" className={platform === 'android' ? 'selected' : ''} aria-pressed={platform === 'android'} onClick={() => setPlatform('android')}>Android 지속 알림</button>
      </div>
      <div className={`notification-card notification-card--${platform}`}>
        <div className="notification-card__header"><span className="preview-app-icon">N</span><span>NextStop</span><span className="notification-card__example">미리보기</span></div>
        <div className="notification-card__route"><Icon size={18} /><strong>{route.name}</strong><span>{route.directions[ride.direction]}</span></div>
        <div className="notification-card__location"><strong>{currentLabel(ride, route)}</strong><span>{ride.freshness !== 'fresh' ? '갱신 지연' : ride.motion === 'between' ? '이동 중' : '도착'}</span></div>
        <RouteLine ride={ride} route={route} compact />
        <div className="notification-card__footer"><span>다음 <strong>{nextStop(ride, route) ?? '종점'}</strong></span>{remaining !== null && <span>{stops[ride.alightIndex!]}까지 {remaining}개</span>}<ChevronRight size={14} /></div>
      </div>
      <p className="preview-note">화면 예시입니다. 실제 시스템 알림은 모바일 앱에서 제공됩니다.</p>
    </section>
  )
}
