import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

// Teach tailwind-merge the custom type scale so `text-small` isn't read as a colour.
const merge = extendTailwindMerge({
  extend: { classGroups: { 'font-size': [{ text: ['display', 'body', 'small'] }] } },
})

export function cn(...inputs: ClassValue[]) {
  return merge(clsx(inputs))
}
