/**
 * Unit tests for ChatModal covering message visibility (Req: chat modal
 * readability fix) and removal of the unused "Attach document" control.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, fireEvent } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ChatModal } from './ChatModal'
import {
  useGetConversationQuery,
  useClarificationRespondMutation,
} from '../../declarationApi'
import type { ChatMessage } from '../../declarationTypes'

vi.mock('../../declarationApi', () => ({
  declarationApi: {
    reducerPath: 'declarationApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
  },
  useGetConversationQuery: vi.fn(),
  useClarificationRespondMutation: vi.fn(),
}))

function makeDcMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'clarification-1',
    type: 'CLARIFICATION',
    actor: 'DC',
    message: 'hi how are you admin',
    timestamp: '2024-01-10T10:00:00',
    metadata: null,
    ...overrides,
  }
}

function mockConversationQuery(messages: ChatMessage[]) {
  vi.mocked(useGetConversationQuery).mockReturnValue({
    data: { success: true, message: 'OK', data: messages },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetConversationQuery>)
}

function mockClarificationRespond(
  triggerFn = vi.fn(),
  state: Record<string, unknown> = { isLoading: false }
) {
  vi.mocked(useClarificationRespondMutation).mockReturnValue([
    triggerFn,
    state,
  ] as ReturnType<typeof useClarificationRespondMutation>)
}

function openModal() {
  fireEvent.click(screen.getByRole('button', { name: /Respond to Clarification|View Conversation/i }))
}

beforeEach(() => {
  vi.clearAllMocks()
  mockConversationQuery([])
  mockClarificationRespond()
})

describe('ChatModal', () => {
  it('rendersFullMessageTextWithoutClipping', () => {
    const longMessage =
      'hi how are you admin, this is a fairly long clarification message that should remain ' +
      'fully readable and not be clipped by a collapsed scroll region.'
    mockConversationQuery([makeDcMessage({ message: longMessage })])

    renderWithProviders(
      <ChatModal declarationId={1} declarationStatus="CLARIFICATION_REQUIRED" readonly={false} />
    )
    openModal()

    expect(screen.getByText(longMessage)).toBeInTheDocument()
  })

  it('messagesAreaReservesVisibleSpaceEvenWithFewMessages', () => {
    mockConversationQuery([makeDcMessage()])

    renderWithProviders(
      <ChatModal declarationId={1} declarationStatus="CLARIFICATION_REQUIRED" readonly={false} />
    )
    openModal()

    const messagesArea = screen.getByTestId('chat-messages-area')
    // A floor height so the area never looks collapsed to a sliver when
    // total dialog content is short.
    expect(messagesArea.className).toMatch(/min-h-\[/)
  })

  it('scrollAreaContainsBothMessagesAndTheResponseBoxSoScrollingRevealsSubmit', () => {
    mockConversationQuery([makeDcMessage()])

    renderWithProviders(
      <ChatModal declarationId={1} declarationStatus="CLARIFICATION_REQUIRED" readonly={false} />
    )
    openModal()

    const scrollArea = screen.getByTestId('chat-scroll-area')
    // The single scrollable region must actually be able to scroll (bounded
    // height + auto overflow) and must contain the submit button, so that
    // scrolling this one area is always sufficient to reach it — it must
    // never be a sibling that can get clipped outside any scroll container.
    expect(scrollArea.className).toContain('overflow-y-auto')
    expect(scrollArea.className).toMatch(/min-h-0/)
    const submitButton = screen.getByRole('button', { name: /Submit Response/i })
    expect(scrollArea.contains(submitButton)).toBe(true)
  })

  it('dialogContentHasADefiniteHeightSoFlexGrowHasSpaceToDistribute', () => {
    mockConversationQuery([makeDcMessage()])

    renderWithProviders(
      <ChatModal declarationId={1} declarationStatus="CLARIFICATION_REQUIRED" readonly={false} />
    )
    openModal()

    const dialogContent = screen.getByTestId('chat-dialog-content')
    // A bare max-h on a position:fixed, height:auto flex column gives the
    // flex-1 child no free space to grow into — it must be a definite height.
    expect(dialogContent.className).toMatch(/\bh-\[\d+vh\]/)
  })

  it('doesNotRenderAttachDocumentControl', () => {
    mockConversationQuery([makeDcMessage()])

    renderWithProviders(
      <ChatModal declarationId={1} declarationStatus="CLARIFICATION_REQUIRED" readonly={false} />
    )
    openModal()

    expect(screen.queryByLabelText(/Attach document/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Attach supporting document/i)).not.toBeInTheDocument()
  })
})
