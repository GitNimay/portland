import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { InlinePeek, type PeekKind } from './InlinePeek'

const peeks: [PeekKind, RegExp][] = [
  ['saved', /Saved to Inspo/],
  ['colour', /^blue$/],
  ['bucket', /Backed up/],
]

describe('InlinePeek', () => {
  it.each(peeks)('opens the %s preview on tap and closes it on a second tap', async (kind, text) => {
    const { container } = render(<InlinePeek kind={kind} tilt={4} />)
    const tap = () => {
      const trigger = container.firstElementChild as HTMLElement
      fireEvent.pointerDown(trigger, { pointerType: 'touch' })
      fireEvent.click(trigger)
    }

    expect(screen.queryByText(text)).toBeNull()
    tap()
    expect(screen.getByText(text)).toBeInTheDocument()
    tap()
    await waitFor(() => expect(screen.queryByText(text)).toBeNull())
  })

  it('stays open when a mouse clicks the object it is hovering', async () => {
    const user = userEvent.setup()
    const { container } = render(<InlinePeek kind="bucket" tilt={6} />)
    await user.click(container.firstElementChild as HTMLElement)
    expect(screen.getByText(/Backed up/)).toBeInTheDocument()
  })

  it('opens on mouse hover and closes when the pointer leaves', async () => {
    const { container } = render(<InlinePeek kind="saved" tilt={-6} />)
    const trigger = container.firstElementChild as HTMLElement

    fireEvent.pointerEnter(trigger, { pointerType: 'mouse' })
    expect(screen.getByText(/Saved to Inspo/)).toBeInTheDocument()
    fireEvent.pointerLeave(trigger, { pointerType: 'mouse' })
    await waitFor(() => expect(screen.queryByText(/Saved to Inspo/)).toBeNull())
  })

  it('ignores hover from touch, which opens on tap instead', () => {
    const { container } = render(<InlinePeek kind="saved" tilt={-6} />)
    fireEvent.pointerEnter(container.firstElementChild as HTMLElement, { pointerType: 'touch' })
    expect(screen.queryByText(/Saved to Inspo/)).toBeNull()
  })

  it('is decorative, so screen readers skip it', () => {
    const { container } = render(<InlinePeek kind="colour" tilt={3} />)
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
  })
})
