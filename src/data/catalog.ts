import type { Direction, Route, Vehicle } from '../domain/types'

// All routes and vehicle candidates here are curated examples, not provider responses.
export const regions = ['전국', '서울', '경기', '부산', '대구', '대전', '광주', '인천']

export const routes: Route[] = [
  {
    id: 'seoul-2', name: '2호선', region: '서울', mode: 'subway', operator: '서울 지하철',
    description: '교대 · 강남 · 잠실', color: '#2E9E5C', capability: 'realtime', serviceType: '일반',
    stops: ['서울대입구', '낙성대', '사당', '방배', '서초', '교대', '강남', '역삼', '선릉', '삼성', '종합운동장', '잠실새내', '잠실', '잠실나루', '강변'],
    directions: { forward: '강남·잠실 방면', reverse: '서초·사당 방면' }, defaultBoarding: 5,
  },
  {
    id: 'seoul-9', name: '9호선', region: '서울', mode: 'subway', operator: '서울 지하철',
    description: '여의도 · 고속터미널 · 종합운동장', color: '#B57A0B', capability: 'realtime', serviceType: '급행',
    stops: ['김포공항', '마곡나루', '가양', '염창', '당산', '여의도', '노량진', '동작', '고속터미널', '신논현', '선정릉', '봉은사', '종합운동장', '석촌', '올림픽공원'],
    directions: { forward: '고속터미널·종합운동장 방면', reverse: '여의도·김포공항 방면' }, defaultBoarding: 5,
  },
  {
    id: 'seoul-271', name: '271', region: '서울', mode: 'bus', operator: '서울 간선버스',
    description: '홍대입구 · 광화문 · 청량리', color: '#2457C5', capability: 'realtime', serviceType: '간선',
    stops: ['홍대입구역', '동교동삼거리', '신촌오거리', '이대역', '아현역', '충정로역', '서대문역', '광화문', '종로1가', '종로2가', '종로3가', '종로4가', '종로5가', '동대문', '청량리역'],
    directions: { forward: '광화문·청량리 방면', reverse: '신촌·홍대입구 방면' }, defaultBoarding: 6,
  },
  {
    id: 'gyeonggi-7770', name: '7770', region: '경기', mode: 'bus', operator: '경기 광역버스',
    description: '수원 · 의왕 · 사당', color: '#B42318', capability: 'realtime', serviceType: '광역',
    stops: ['수원역', '고등동', '화서시장', '화서문', '장안문', '수원종합운동장', '한일타운', '의왕톨게이트', '사당역'],
    directions: { forward: '의왕·사당 방면', reverse: '한일타운·수원 방면' }, defaultBoarding: 4,
  },
  {
    id: 'busan-1', name: '1호선', region: '부산', mode: 'subway', operator: '부산 도시철도',
    description: '부산역 · 서면 · 동래', color: '#C65D21', capability: 'timetable', serviceType: '일반',
    stops: ['부산역', '초량', '부산진', '좌천', '범일', '범내골', '서면', '부전', '양정', '시청', '연산', '교대', '동래'],
    directions: { forward: '서면·동래 방면', reverse: '범일·부산역 방면' }, defaultBoarding: 6,
  },
  {
    id: 'daegu-2', name: '2호선', region: '대구', mode: 'subway', operator: '대구 도시철도',
    description: '반월당 · 범어 · 영남대', color: '#2E9E5C', capability: 'timetable', serviceType: '일반',
    stops: ['두류', '내당', '반고개', '청라언덕', '반월당', '경대병원', '대구은행', '범어', '수성구청', '만촌', '담티', '연호', '대공원'],
    directions: { forward: '범어·대공원 방면', reverse: '청라언덕·두류 방면' }, defaultBoarding: 4,
  },
  {
    id: 'daejeon-1', name: '1호선', region: '대전', mode: 'subway', operator: '대전 도시철도',
    description: '대전역 · 시청 · 유성온천', color: '#2E9E5C', capability: 'timetable', serviceType: '일반',
    stops: ['대전역', '중앙로', '중구청', '서대전네거리', '오룡', '용문', '탄방', '시청', '정부청사', '갈마', '월평', '갑천', '유성온천'],
    directions: { forward: '시청·유성온천 방면', reverse: '중앙로·대전역 방면' }, defaultBoarding: 7,
  },
  {
    id: 'gwangju-1', name: '1호선', region: '광주', mode: 'subway', operator: '광주 도시철도',
    description: '문화전당 · 상무 · 광주송정', color: '#2E9E5C', capability: 'timetable', serviceType: '일반',
    stops: ['문화전당', '금남로4가', '금남로5가', '양동시장', '돌고개', '농성', '화정', '쌍촌', '운천', '상무', '김대중컨벤션센터', '공항', '광주송정'],
    directions: { forward: '상무·광주송정 방면', reverse: '농성·문화전당 방면' }, defaultBoarding: 5,
  },
]

export function getRoute(id: string): Route | undefined {
  return routes.find((route) => route.id === id)
}

export function orderedStops(route: Route, direction: Direction): string[] {
  return direction === 'forward' ? route.stops : [...route.stops].reverse()
}

export function vehicleCandidates(route: Route, direction: Direction, boardingIndex: number, now = Date.now()): Vehicle[] {
  const stop = orderedStops(route, direction)[boardingIndex]
  if (route.capability !== 'realtime') return []
  if (route.mode === 'bus') {
    return [
      { id: `${route.id}-${direction}-1001`, label: '서울 74사 3186', detail: '차량번호 끝자리 3186', status: `${stop} 도착`, observedAt: now },
      { id: `${route.id}-${direction}-1002`, label: '서울 74사 4521', detail: '차량번호 끝자리 4521', status: `${stop} 진입 중`, observedAt: now - 8000 },
      { id: `${route.id}-${direction}-1003`, label: '서울 74사 2074', detail: '차량번호 끝자리 2074', status: '앞 정류장 출발', observedAt: now - 12000 },
    ].map((vehicle) => route.region === '경기' ? { ...vehicle, label: vehicle.label.replace('서울 74사', '경기 70아') } : vehicle)
  }
  return [
    { id: `${route.id}-${direction}-2014`, label: `${route.directions[direction]} 열차`, detail: `${route.serviceType} · 예시 열차 2014`, status: `${stop} 도착`, observedAt: now },
    { id: `${route.id}-${direction}-2032`, label: `${route.directions[direction]} 열차`, detail: `${route.serviceType} · 예시 열차 2032`, status: `${stop} 진입 중`, observedAt: now - 5000 },
    { id: `${route.id}-${direction}-2061`, label: `${route.directions[direction]} 열차`, detail: `${route.serviceType} · 예시 열차 2061`, status: '앞 역 출발', observedAt: now - 10000 },
  ]
}
