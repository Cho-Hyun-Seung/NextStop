import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { chromium } from '@playwright/test'

const output = process.env.NEXTSTOP_CAPTURE_DIR ?? '/tmp/nextstop-preview'
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5180'
await mkdir(output, { recursive: true })
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined,
})
try {
  for (const [name, viewport] of [
    ['desktop', { width: 1440, height: 1000 }],
    ['mobile', { width: 390, height: 844 }],
  ]) {
    const context = await browser.newContext({ viewport, locale: 'ko-KR' })
    const page = await context.newPage()
    await page.goto(baseURL)
    await page.getByRole('heading', { name: '어떤 차를 타고 있나요?' }).waitFor()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: path.join(output, `home-${name}.png`), fullPage: true })
    await page.getByRole('button', { name: '서울 2호선 선택', exact: true }).click()
    await page.getByRole('button', { name: '탑승 차량 찾기' }).click()
    await page.getByRole('radio', { name: /예시 열차 2014/ }).check()
    await page.getByRole('button', { name: '이동 추적 시작하기' }).click()
    await page.getByRole('button', { name: '다음 구간으로 이동' }).click()
    await page.getByRole('button', { name: '다음 지점 도착' }).click()
    await page.locator('.toast').filter({ hasText: '이동 체험' }).getByRole('button').click()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: path.join(output, `ride-${name}.png`), fullPage: true })
    await page.goto(`${baseURL}/#/settings`)
    await page.getByRole('button', { name: '어둡게', exact: true }).click()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: path.join(output, `settings-dark-${name}.png`), fullPage: true })
    await context.close()
  }
  console.log(`Saved six preview images to ${output}`)
} finally {
  await browser.close()
}
