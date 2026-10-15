export type Transport = 'subway' | 'bus'
export type Direction = 'forward' | 'reverse'
export type Capability = 'realtime' | 'timetable' | 'unsupported'
export type NotificationMode = 'all_stops' | 'alight_only' | 'silent'
export type Freshness = 'fresh' | 'stale' | 'lost'
export type Page = 'home' | 'favorites' | 'ride' | 'settings'

export interface Route {
  id: string
  name: string
  region: string
  mode: Transport
  operator: string
  description: string
  color: string
  capability: Capability
  serviceType: string
  stops: string[]
  directions: Record<Direction, string>
  defaultBoarding: number
}

export interface Vehicle {
  id: string
  label: string
  detail: string
  status: string
  observedAt: number
}

export interface Preferences {
  mode: NotificationMode
  sound: boolean
  vibration: boolean
  theme: 'light' | 'dark' | 'system'
}

export interface Ride {
  id: string
  routeId: string
  direction: Direction
  vehicleId: string
  vehicleLabel: string
  boardingIndex: number
  currentIndex: number
  alightIndex: number | null
  motion: 'arrived' | 'between'
  freshness: Freshness
  mode: NotificationMode
  revision: number
  startedAt: number
  sourceObservedAt: number
  lifecycle: 'active' | 'ended'
  events: ArrivalEvent[]
}

export interface ArrivalEvent {
  id: string
  title: string
  body: string
  at: number
  type: 'arrival' | 'alight_next'
}

export interface AppState {
  version: 1
  preferences: Preferences
  favorites: string[]
  recent: string[]
  ride: Ride | null
}

export interface Setup {
  routeId: string
  direction: Direction
  boardingIndex: number
  alightIndex: number | null
  vehicle: Vehicle | null
  stage: 'route' | 'vehicle'
  mode: NotificationMode
}
