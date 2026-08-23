import { useCallback, useEffect, useState } from 'react'
import type { ThemePref } from '../api/types'

const KEY = 'chronicle-theme'
const CYCLE: ThemePref[] = ['system', 'light', 'dark']
const ICONS: Record<ThemePref, string> = { system: '◐', light: '○', dark: '●' }
const LABELS: Record<ThemePref, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
}

function resolve(pref: ThemePref): 'light' | 'dark' {
  if (pref === 'light' || pref === 'dark') return pref
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function apply(pref: ThemePref) {
  const resolved = resolve(pref)
  document.documentElement.dataset.theme = resolved
  document.documentElement.dataset.themePref = pref
  localStorage.setItem(KEY, pref)
}

export function useTheme() {
  const [pref, setPref] = useState<ThemePref>(() => {
    const saved = localStorage.getItem(KEY) as ThemePref | null
    return saved && CYCLE.includes(saved) ? saved : 'dark'
  })

  useEffect(() => {
    apply(pref)
  }, [pref])

  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if ((localStorage.getItem(KEY) as ThemePref) === 'system') apply('system')
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const cycle = useCallback(() => {
    setPref((cur) => CYCLE[(CYCLE.indexOf(cur) + 1) % CYCLE.length])
  }, [])

  return {
    pref,
    setPref,
    cycle,
    icon: ICONS[pref],
    label: LABELS[pref],
    resolved: resolve(pref),
  }
}
