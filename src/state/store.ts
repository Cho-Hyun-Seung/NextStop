import { useEffect, useState } from 'react'
import { getRoute } from '../data/catalog'
import { isRide } from '../domain/ride'
import type { AppState, Preferences } from '../domain/types'

const storageKey = 'nextstop.preview.v1'
const defaults: AppState = {
  version: 1,
  preferences: { mode: 'alight_only', sound: true, vibration: true, theme: 'light' },
  favorites: [], recent: [], ride: null,
}

export function loadState(raw: string | null, now = Date.now()): AppState {
  if (!raw) return structuredClone(defaults)
  try {
    const value = JSON.parse(raw) as Partial<AppState>
    if (value.version !== 1) return structuredClone(defaults)
    const preferences: Preferences = { ...defaults.preferences }
    const saved = value.preferences
    if (saved && ['all_stops', 'alight_only', 'silent'].includes(saved.mode)) preferences.mode = saved.mode
    if (saved && ['light', 'dark', 'system'].includes(saved.theme)) preferences.theme = saved.theme
    if (typeof saved?.sound === 'boolean') preferences.sound = saved.sound
    if (typeof saved?.vibration === 'boolean') preferences.vibration = saved.vibration
    const validIds = (ids: unknown) => Array.isArray(ids) ? [...new Set(ids.filter((id): id is string => typeof id === 'string' && !!getRoute(id)))] : []
    let ride = isRide(value.ride) ? value.ride : null
    if (ride && (ride.lifecycle === 'ended' || now - ride.startedAt > 4 * 60 * 60 * 1000)) ride = null
    if (ride && now - ride.sourceObservedAt > 90_000) ride = { ...ride, freshness: 'stale' }
    return { version: 1, preferences, favorites: validIds(value.favorites), recent: validIds(value.recent).slice(0, 4), ride }
  } catch {
    return structuredClone(defaults)
  }
}

export function useAppState() {
  const [state, setState] = useState<AppState>(() => {
    try { return loadState(localStorage.getItem(storageKey)) } catch { return structuredClone(defaults) }
  })
  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(state)) } catch { /* A blocked storage area should not prevent a ride. */ }
  }, [state])
  return [state, setState] as const
}
