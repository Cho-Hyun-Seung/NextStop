import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import tokens from '../docs/design-tokens.json'
import { App } from './App'
import './styles.css'

const cssName = (name: string) => name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)
for (const [name, value] of Object.entries(tokens.colors.light)) document.documentElement.style.setProperty(`--light-${cssName(name)}`, value)
for (const [name, value] of Object.entries(tokens.colors.dark)) document.documentElement.style.setProperty(`--dark-${cssName(name)}`, value)

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
