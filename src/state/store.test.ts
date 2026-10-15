import { describe, expect, it } from 'vitest'
import { routes, vehicleCandidates } from '../data/catalog'
import { createRide } from '../domain/ride'
import { loadState } from './store'

const now = Date.parse('2026-10-07T03:00:00Z')
const route = routes[0]
const ride = () => createRide(route, {
  routeId: route.id, direction: 'forward', boardingIndex: 5, alightIndex: 9, stage: 'vehicle',
  mode: 'all_stops', vehicle: vehicleCandidates(route, 'forward', 5, now)[0],
}, now)

describe('기기에 저장한 상태 복구', () => {
  it('잘못된 JSON과 다른 버전은 기본값으로 복구한다', () => {
    expect(loadState('{bad', now).ride).toBeNull()
    expect(loadState('{"version":9}', now).preferences.mode).toBe('alight_only')
  })
  it('90초 이상 지난 위치를 새 정보로 표시하지 않는다', () => {
    const state = loadState(JSON.stringify({ version: 1, ride: ride() }), now + 91_000)
    expect(state.ride?.freshness).toBe('stale')
    expect(state.ride?.sourceObservedAt).toBe(now)
  })
  it('만료되거나 종료된 세션을 되살리지 않는다', () => {
    expect(loadState(JSON.stringify({ version: 1, ride: ride() }), now + 4 * 60 * 60 * 1000 + 1).ride).toBeNull()
    expect(loadState(JSON.stringify({ version: 1, ride: { ...ride(), lifecycle: 'ended' } }), now).ride).toBeNull()
  })
  it('손상된 세션을 버리면서 올바른 즐겨찾기와 설정을 보존한다', () => {
    const state = loadState(JSON.stringify({
      version: 1, favorites: ['seoul-2', 'unknown', 'seoul-2'],
      preferences: { theme: 'dark', sound: false, mode: 'invalid' }, ride: { ...ride(), events: [null] },
    }), now)
    expect(state.ride).toBeNull()
    expect(state.favorites).toEqual(['seoul-2'])
    expect(state.preferences.theme).toBe('dark')
    expect(state.preferences.sound).toBe(false)
    expect(state.preferences.mode).toBe('alight_only')
  })
})
