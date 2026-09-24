import { createContext, useContext, useEffect, useState } from 'react';

const STORAGE_KEY = 'clinicops.theme';
const VALID = new Set(['light', 'dark', 'system']);

const ThemeContext = createContext(null);

function readStoredTheme() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return VALID.has(stored) ? stored : 'system';
  } catch {
    // Private browsing / storage disabled - fall back to the default rather
    // than throwing during render.
    return 'system';
  }
}

function systemPrefersDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/**
 * Light/Dark/System theme preference (frontend phase N) - localStorage-only,
 * per the user's own answer when this was scoped (no backend/per-account
 * persistence). Wraps the whole app in main.jsx, outside BrandingProvider -
 * unlike clinic branding (deliberately staff-only), the theme should apply
 * to logged-out public pages too (booking, tracking), so this sits above
 * the authentication boundary, not inside it.
 *
 * Tailwind's `darkMode: 'class'` (tailwind.config.js) means every themed
 * utility just needs a `.dark` ancestor class - so "system" is resolved
 * here, once, into that same class, rather than relying on Tailwind's own
 * 'media' strategy (which can't be overridden by an explicit Light/Dark
 * choice). A live matchMedia listener keeps "system" in sync if the OS
 * theme changes mid-session, not just at first load.
 */
export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(readStoredTheme);
  const [resolvedTheme, setResolvedTheme] = useState(() =>
    theme === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : theme,
  );

  useEffect(() => {
    if (theme !== 'system') {
      setResolvedTheme(theme);
      return undefined;
    }
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    setResolvedTheme(media.matches ? 'dark' : 'light');
    const onChange = (e) => setResolvedTheme(e.matches ? 'dark' : 'light');
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [theme]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  function setTheme(next) {
    if (!VALID.has(next)) return;
    setThemeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Nothing to fall back to here - the in-memory state above still
      // updates, the choice just won't survive a reload.
    }
  }

  return <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>{children}</ThemeContext.Provider>;
}

/** { theme: 'light'|'dark'|'system', resolvedTheme: 'light'|'dark', setTheme } */
export function useTheme() {
  return useContext(ThemeContext);
}
