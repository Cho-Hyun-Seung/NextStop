import { getRoute, orderedStops, vehicleCandidates } from '../data/catalog'
import type { ArrivalEvent, Ride, Route, Setup } from './types'

export function createRide(route: Route, setup: Setup, now = Date.now()): Ride {
  const stops = orderedStops(route, setup.direction)
  if (route.capability !== 'realtime') throw new Error('이 노선은 차량 추적을 지원하지 않습니다.')
  if (setup.routeId !== route.id || !['forward', 'reverse'].includes(setup.direction)) throw new Error('선택한 노선과 방향을 확인해 주세요.')
  if (!setup.vehicle) throw new Error('탑승 차량을 선택해 주세요.')
  if (!Number.isInteger(setup.boardingIndex) || setup.boardingIndex < 0 || setup.boardingIndex >= stops.length - 1) throw new Error('탑승 지점을 확인해 주세요.')
  if (!vehicleCandidates(route, setup.direction, setup.boardingIndex, now).some((candidate) => candidate.id === setup.vehicle!.id)) throw new Error('선택한 노선의 차량을 다시 확인해 주세요.')
  if (!Number.isFinite(setup.vehicle.observedAt) || now - setup.vehicle.observedAt > 90_000 || setup.vehicle.observedAt > now + 5_000) throw new Error('차량 정보가 오래되었습니다. 다시 조회해 주세요.')
  if (setup.alightIndex !== null && (!Number.isInteger(setup.alightIndex) || setup.alightIndex <= setup.boardingIndex || setup.alightIndex >= stops.length)) throw new Error('하차 지점은 탑승 지점 이후로 선택해 주세요.')
  if (setup.mode === 'alight_only' && setup.alightIndex === null) throw new Error('하차 알림을 받으려면 하차 지점을 선택해 주세요.')
  return {
    id: `demo-${crypto.randomUUID()}`, routeId: route.id, direction: setup.direction,
    vehicleId: setup.vehicle.id, vehicleLabel: setup.vehicle.label, boardingIndex: setup.boardingIndex,
    currentIndex: setup.boardingIndex, alightIndex: setup.alightIndex, motion: 'arrived', freshness: 'fresh',
    mode: setup.mode, revision: 1, startedAt: now, sourceObservedAt: now, lifecycle: 'active', events: [],
  }
}

export function advanceRide(ride: Ride, now = Date.now()): Ride {
  const route = getRoute(ride.routeId)
  if (!route || ride.lifecycle !== 'active' || ride.freshness !== 'fresh') return ride
  const stops = orderedStops(route, ride.direction)
  if (ride.currentIndex >= stops.length - 1) return ride
  const next: Ride = {
    ...ride, revision: ride.revision + 1, sourceObservedAt: now,
    motion: ride.motion === 'arrived' ? 'between' : 'arrived',
    currentIndex: ride.motion === 'between' ? ride.currentIndex + 1 : ride.currentIndex,
  }
  let event: ArrivalEvent | null = null
  if (next.motion === 'between' && next.alightIndex === next.currentIndex + 1 && next.mode !== 'silent') {
    event = { id: `${ride.id}-${next.alightIndex}-alight_next`, title: '다음은 하차 지점이에요', body: `${stops[next.alightIndex]}에서 내릴 준비를 해 주세요.`, at: now, type: 'alight_next' }
  }
  if (next.motion === 'arrived' && (next.mode === 'all_stops' || (next.mode === 'alight_only' && next.alightIndex === next.currentIndex))) {
    event = {
      id: `${ride.id}-${next.currentIndex}-arrival`,
      title: next.alightIndex === next.currentIndex ? '하차 지점에 도착했어요' : `${stops[next.currentIndex]} 도착`,
      body: next.alightIndex === next.currentIndex ? `${stops[next.currentIndex]}입니다. 이동을 마쳤다면 하차 완료를 눌러 주세요.` : `다음은 ${stops[next.currentIndex + 1] ?? '종점'}입니다.`,
      at: now, type: 'arrival',
    }
  }
  if (event && !next.events.some((existing) => existing.id === event.id)) next.events = [...next.events, event].slice(-20)
  return next
}

export function rideWindow(ride: Ride, route: Route) {
  const stops = orderedStops(route, ride.direction)
  const current = ride.currentIndex
  if (ride.motion === 'between') {
    return {
      previous: stops.slice(Math.max(0, current - 2), current + 1),
      center: null,
      upcoming: stops.slice(current + 1, current + 4),
    }
  }
  return { previous: stops.slice(Math.max(0, current - 3), current), center: stops[current], upcoming: stops.slice(current + 1, current + 4) }
}

export function currentLabel(ride: Ride, route: Route): string {
  const stops = orderedStops(route, ride.direction)
  return ride.motion === 'between' ? `${stops[ride.currentIndex]} → ${stops[ride.currentIndex + 1]}` : stops[ride.currentIndex]
}

export function nextStop(ride: Ride, route: Route): string | null {
  return orderedStops(route, ride.direction)[ride.currentIndex + 1] ?? null
}

export function isRide(value: unknown): value is Ride {
  if (!value || typeof value !== 'object') return false
  const ride = value as Ride
  const route = getRoute(ride.routeId)
  if (!route || route.capability !== 'realtime') return false
  const indexValid = (index: unknown) => Number.isInteger(index) && Number(index) >= 0 && Number(index) < route.stops.length
  return typeof ride.id === 'string' && typeof ride.vehicleId === 'string' && typeof ride.vehicleLabel === 'string'
    && ['forward', 'reverse'].includes(ride.direction) && indexValid(ride.currentIndex) && indexValid(ride.boardingIndex)
    && ride.boardingIndex < route.stops.length - 1 && ride.currentIndex >= ride.boardingIndex
    && (ride.alightIndex === null || (indexValid(ride.alightIndex) && ride.alightIndex > ride.boardingIndex))
    && ['arrived', 'between'].includes(ride.motion) && (ride.motion !== 'between' || ride.currentIndex < route.stops.length - 1)
    && ['fresh', 'stale', 'lost'].includes(ride.freshness) && ['all_stops', 'alight_only', 'silent'].includes(ride.mode)
    && ['active', 'ended'].includes(ride.lifecycle) && Number.isInteger(ride.revision) && ride.revision > 0
    && Number.isFinite(ride.sourceObservedAt) && Number.isFinite(ride.startedAt)
    && Array.isArray(ride.events) && ride.events.every((event) => event !== null && typeof event === 'object' && typeof event.id === 'string' && typeof event.title === 'string' && typeof event.body === 'string' && Number.isFinite(event.at) && ['arrival', 'alight_next'].includes(event.type))
}
