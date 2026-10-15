import { describe, expect, it } from 'vitest'
import { orderedStops, routes, vehicleCandidates } from '../data/catalog'
import { advanceRide, createRide, isRide, rideWindow } from './ride'
import type { Setup } from './types'

const now = Date.parse('2026-10-07T03:00:00Z')
const route = routes[0]
function setup(overrides: Partial<Setup> = {}): Setup {
  return {
    routeId: route.id, direction: 'forward', boardingIndex: 5, alightIndex: 7,
    vehicle: vehicleCandidates(route, 'forward', 5, now)[0], stage: 'vehicle', mode: 'alight_only', ...overrides,
  }
}

describe('탑승 차량과 정차 경로 검증', () => {
  it('만료된 후보와 다른 방향의 차량으로 추적을 시작하지 않는다', () => {
    expect(() => createRide(route, setup({ vehicle: { ...setup().vehicle!, observedAt: now - 91_000 } }), now)).toThrow('다시 조회')
    expect(() => createRide(route, setup({ vehicle: vehicleCandidates(route, 'reverse', 5, now)[0] }), now)).toThrow('차량을 다시 확인')
  })
  it('하차 지점 미선택, 탑승 전 하차 지점과 소수점 순번을 거부한다', () => {
    expect(() => createRide(route, setup({ alightIndex: null }), now)).toThrow('하차 지점')
    expect(() => createRide(route, setup({ alightIndex: 4 }), now)).toThrow('탑승 지점 이후')
    expect(() => createRide(route, setup({ boardingIndex: 5.5 }), now)).toThrow('탑승 지점')
  })
  it('시간표 노선을 실제 차량 추적으로 시작하지 않는다', () => {
    expect(() => createRide(routes[4], setup({ routeId: routes[4].id }), now)).toThrow('차량 추적을 지원하지')
  })
  it('역방향에서 실제 진행 방향의 다음 정차 지점을 사용한다', () => {
    const reversed = createRide(route, setup({ direction: 'reverse', vehicle: vehicleCandidates(route, 'reverse', 5, now)[0] }), now)
    expect(rideWindow(reversed, route).upcoming[0]).toBe(orderedStops(route, 'reverse')[6])
  })
})

describe('이동 진행과 알림', () => {
  it('출발만으로 도착 이벤트를 생성하지 않고 도착 상태에서 생성한다', () => {
    const ride = createRide(route, setup({ mode: 'all_stops' }), now)
    const departed = advanceRide(ride, now + 1000)
    expect(departed.motion).toBe('between')
    expect(departed.events.filter((event) => event.type === 'arrival')).toHaveLength(0)
    const arrived = advanceRide(departed, now + 2000)
    expect(arrived.currentIndex).toBe(6)
    expect(arrived.events[0].title).toBe('강남 도착')
  })
  it('하차 모드에서는 다른 역 도착을 건너뛰고 직전·하차역만 알린다', () => {
    let ride = createRide(route, setup(), now)
    ride = advanceRide(advanceRide(ride, now + 1000), now + 2000)
    expect(ride.events).toHaveLength(0)
    ride = advanceRide(ride, now + 3000)
    expect(ride.events.map((event) => event.type)).toEqual(['alight_next'])
    ride = advanceRide(ride, now + 4000)
    expect(ride.events.map((event) => event.type)).toEqual(['alight_next', 'arrival'])
    expect(new Set(ride.events.map((event) => event.id)).size).toBe(2)
  })
  it('재처리한 같은 도착을 중복 기록하지 않는다', () => {
    const departed = advanceRide(createRide(route, setup({ mode: 'all_stops' }), now), now + 1000)
    const arrived = advanceRide(departed, now + 2000)
    const repeated = advanceRide({ ...departed, events: arrived.events }, now + 3000)
    expect(repeated.events).toHaveLength(1)
  })
  it('무음·지연·종료 세션은 새 알림을 생성하지 않는다', () => {
    let ride = createRide(route, setup({ mode: 'silent' }), now)
    for (let tick = 0; tick < 8; tick++) ride = advanceRide(ride, now + tick * 1000)
    expect(ride.events).toHaveLength(0)
    const stale = { ...ride, freshness: 'stale' as const }
    expect(advanceRide(stale, now + 20000)).toBe(stale)
    const ended = { ...ride, lifecycle: 'ended' as const }
    expect(advanceRide(ended, now + 20000)).toBe(ended)
  })
  it('종점에 없는 다음 지점과 가짜 노드를 생성하지 않는다', () => {
    const ride = createRide(route, setup({ mode: 'silent' }), now)
    const last = { ...ride, currentIndex: route.stops.length - 1 }
    expect(advanceRide(last, now + 1000)).toBe(last)
    expect(rideWindow(last, route).upcoming).toEqual([])
    expect(rideWindow({ ...ride, currentIndex: 0 }, route).previous).toEqual([])
  })
  it('구간 표시에서 같은 정류장을 중복 노드로 추가하지 않는다', () => {
    const moving = advanceRide(createRide(route, setup(), now), now + 1000)
    const window = rideWindow(moving, route)
    expect(window.center).toBeNull()
    expect(window.previous).toEqual(['방배', '서초', '교대'])
    expect(window.upcoming).toEqual(['강남', '역삼', '선릉'])
  })
  it('손상된 저장 데이터와 진행 순서 역전을 거부한다', () => {
    const ride = createRide(route, setup(), now)
    expect(isRide(ride)).toBe(true)
    expect(isRide({ ...ride, events: [null] })).toBe(false)
    expect(isRide({ ...ride, currentIndex: 1 })).toBe(false)
    expect(isRide({ ...ride, currentIndex: 999 })).toBe(false)
  })
})
