import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function selectSubway(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: '서울 2호선 선택', exact: true }).click()
  await page.getByRole('button', { name: '탑승 차량 찾기' }).click()
  await expect(page.getByRole('button', { name: '탑승 차량을 선택해 주세요' })).toBeDisabled()
  await page.getByRole('radio', { name: /예시 열차 2014/ }).check()
  await page.getByRole('button', { name: '이동 추적 시작하기' }).click()
  await expect(page.getByRole('heading', { name: '지금, 이동 중이에요' })).toBeVisible()
}

async function expectNoHorizontalOverflow(page: Page) {
  const bounds = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
    app: document.getElementById('root')!.getBoundingClientRect().right,
  }))
  expect(bounds.document).toBeLessThanOrEqual(bounds.viewport)
  expect(bounds.body).toBeLessThanOrEqual(bounds.viewport)
  expect(bounds.app).toBeLessThanOrEqual(bounds.viewport)
  await page.evaluate(() => window.scrollTo(200, window.scrollY))
  expect(await page.evaluate(() => window.scrollX)).toBe(0)
}

test('지하철 탑승, 진행, 하차 알림, 복원과 종료', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await selectSubway(page)
  await expect(page.locator('#main-content')).toBeFocused()
  await page.getByRole('button', { name: '안내 닫기' }).click()
  await expect(page.getByRole('button', { name: '안내 닫기' })).toHaveCount(0)
  await page.getByRole('button', { name: '다음 구간으로 이동' }).click()
  await expect(page.getByRole('heading', { name: '교대 → 강남' })).toBeVisible()
  await page.getByRole('button', { name: '다음 지점 도착' }).click()
  await expect(page.getByRole('heading', { name: '강남', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '강남', exact: true })).toBeVisible()
  for (let index = 0; index < 3; index++) {
    await page.getByRole('button', { name: '다음 구간으로 이동' }).click()
    await page.getByRole('button', { name: '다음 지점 도착' }).click()
  }
  await expect(page.locator('.arrival-event').filter({ hasText: '하차 지점에 도착했어요' })).toHaveCount(1)
  await expect(page.locator('.arrival-event').filter({ hasText: '다음은 하차 지점이에요' })).toHaveCount(1)
  await expect(page.locator('.ride-metrics')).toContainText('도착했어요')
  await page.getByRole('button', { name: '다음 구간으로 이동' }).click()
  await expect(page.locator('.ride-metrics')).toContainText('하차 지점을 지났어요')
  await page.getByRole('button', { name: '하차 완료', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '하차 완료', exact: true }).click()
  await page.goto('/#/ride')
  await expect(page.getByRole('heading', { name: '진행 중인 이동이 없어요' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '진행 중인 이동이 없어요' })).toBeVisible()
  expect(errors).toEqual([])
})

test('버스 후보 선택과 정보 지연의 중단·복구', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '버스', exact: true }).click()
  await page.getByRole('button', { name: '서울 271 선택', exact: true }).click()
  await page.locator('#ride-direction').selectOption('reverse')
  await page.getByRole('radio', { name: /모든 도착 알림/ }).check()
  await page.getByRole('button', { name: '탑승 차량 찾기' }).click()
  await page.getByRole('radio', { name: /4521/ }).check()
  await page.getByRole('button', { name: '이동 추적 시작하기' }).click()
  await expect(page.locator('.journey-card')).toContainText('서울 74사 4521')
  await expect(page.locator('.journey-card')).toContainText('신촌·홍대입구 방면')
  await page.getByRole('button', { name: '갱신 지연 보기' }).click()
  await expect(page.getByRole('button', { name: '다음 구간으로 이동' })).toBeDisabled()
  await expect(page.locator('.journey-status')).toContainText('갱신 지연')
  await page.getByRole('button', { name: '정보 갱신하기' }).click()
  await expect(page.getByRole('button', { name: '다음 구간으로 이동' })).toBeEnabled()
  await page.getByRole('button', { name: '다음 구간으로 이동' }).click()
  await page.getByRole('button', { name: '다음 지점 도착' }).click()
  await expect(page.locator('.arrival-event')).toHaveCount(1)
})

test('지역 검색과 시간표 노선은 실제 차량 추적을 시작하지 않는다', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('combobox', { name: '서비스 지역' }).selectOption('부산')
  await expect(page.locator('.route-card')).toHaveCount(1)
  await page.getByRole('button', { name: '부산 1호선 선택', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('시간표 안내 예시')
  await expect(page.getByRole('button', { name: '이동 추적 시작하기' })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('searchbox').fill('없는노선')
  await expect(page.getByRole('heading', { name: '일치하는 예시 노선이 없어요' })).toBeVisible()
  await page.getByRole('button', { name: '검색 초기화' }).click()
  await expect(page.locator('.route-card')).toHaveCount(4)
})

test('즐겨찾기·알림·테마 설정이 새로고침 후에도 남는다', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '서울 2호선 즐겨찾기 추가' }).click()
  await page.goto('/#/favorites')
  await expect(page.locator('.route-card')).toHaveCount(1)
  await page.reload()
  await expect(page.getByRole('button', { name: '서울 2호선 즐겨찾기 해제' })).toBeVisible()
  await page.goto('/#/settings')
  await page.getByRole('radio', { name: /조용히 이동/ }).check()
  await page.getByRole('switch', { name: '알림 소리' }).uncheck()
  await page.getByRole('button', { name: '어둡게', exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByRole('button', { name: '밝은 화면으로 변경' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('radio', { name: /조용히 이동/ })).toBeChecked()
  await expect(page.getByRole('switch', { name: '알림 소리' })).not.toBeChecked()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.getByRole('button', { name: '밝은 화면으로 변경' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

test('320px 화면에서 가로 넘침 없이 탐색하고 대화상자를 닫는다', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '어떤 차를 타고 있나요?' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
  await page.getByRole('button', { name: '서울 2호선 선택', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(await page.getByRole('dialog').evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true)
  await page.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(page.getByRole('button', { name: '서울 2호선 선택', exact: true })).toBeFocused()
  await page.getByRole('navigation', { name: '모바일 주요 메뉴' }).getByRole('link', { name: '내 이동' }).click()
  await expect(page.getByRole('heading', { name: '진행 중인 이동이 없어요' })).toBeVisible()
})

test('좁은 화면과 iPhone 16 폭에서 모든 화면과 탑승 선택이 가로로 넘치지 않는다', async ({ page }) => {
  await selectSubway(page)
  for (const width of [280, 320, 360, 393, 414, 768]) {
    await page.setViewportSize({ width, height: 852 })
    for (const destination of ['home', 'favorites', 'settings', 'ride']) {
      await page.goto(`/#/${destination}`)
      await expectNoHorizontalOverflow(page)
    }
    await page.goto('/#/home')
    await page.getByRole('button', { name: '서울 2호선 선택', exact: true }).click()
    await expectNoHorizontalOverflow(page)
    expect(await page.getByRole('dialog').evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true)
    await page.getByRole('button', { name: '탑승 차량 찾기' }).click()
    expect(await page.getByRole('dialog').evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true)
    await page.getByRole('button', { name: '닫기', exact: true }).click()
  }
})

test('모바일 터치로 좌우로 끌어도 페이지는 고정되고 세로 스크롤은 유지된다', async ({ page, isMobile }) => {
  await page.goto('/')
  await expectNoHorizontalOverflow(page)
  if (isMobile) {
    const session = await page.context().newCDPSession(page)
    const { width, height } = page.viewportSize()!
    const y = Math.min(250, height / 2)
    for (const [start, end] of [[width - 40, 40], [40, width - 40]]) {
      await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: start, y }] })
      for (let step = 1; step <= 8; step++) {
        await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: start + (end - start) * step / 8, y }] })
        await page.evaluate(() => new Promise(requestAnimationFrame))
      }
      expect(await page.evaluate(() => window.scrollX)).toBe(0)
      expect(await page.evaluate(() => window.visualViewport!.offsetLeft)).toBe(0)
      await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    }
    const startY = height * .75
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: width / 2, y: startY }] })
    for (let step = 1; step <= 12; step++) {
      await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: width / 2, y: startY - 25 * step }] })
      await page.evaluate(() => new Promise(requestAnimationFrame))
    }
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await session.detach()
  } else await page.mouse.wheel(0, 300)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
  await expectNoHorizontalOverflow(page)
})

test('키보드로 본문에 건너뛰어도 현재 페이지를 유지한다', async ({ page }) => {
  await page.goto('/#/settings')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '본문으로 건너뛰기' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
  await expect(page).toHaveURL(/#\/settings$/)
  await expect(page.getByRole('heading', { name: '내 이동에 맞는 알림' })).toBeVisible()
})
