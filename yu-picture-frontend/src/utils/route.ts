import type { LocationQueryValue } from 'vue-router'

// Java snowflake IDs are decimal strings and must never pass through Number().
export function routeId(value: LocationQueryValue | LocationQueryValue[] | undefined): string | undefined {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : undefined
}

export function loginRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return '/'
  }
  const url = new URL(value, 'https://local.invalid')
  if (url.origin !== 'https://local.invalid' || url.pathname === '/user/login') return '/'
  return `${url.pathname}${url.search}${url.hash}`
}
