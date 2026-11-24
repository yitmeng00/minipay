import { useEffect, useState } from 'react'

export type Theme = 'light' | 'dark'

/** Saved choice, else the operating system's preference. */
export function initialTheme(): Theme {
  const saved = localStorage.getItem('minipay.theme')
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(initialTheme)
  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
  }, [theme])
  const toggle = () =>
    setTheme((t) => {
      const next = t === 'dark' ? 'light' : 'dark'
      localStorage.setItem('minipay.theme', next)
      return next
    })
  return [theme, toggle]
}
