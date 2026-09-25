import { useState } from 'react'
import { MessageCircle, X, MapPin, Send } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/feedback/Skeleton/Skeleton'
import { EmptyState } from '@/components/feedback/EmptyState/EmptyState'
import { cn } from '@/lib/utils'
import { useGetConversationQuery } from '../../declarationApi'
import type { ChatMessage, DeclarationStatus } from '../../declarationTypes'
import { ResponseBox } from '../ChatPanel/ResponseBox'

interface ChatModalProps {
  declarationId: number
  declarationStatus: DeclarationStatus
  readonly: boolean
  unreadCount?: number
}

function formatTimestamp(iso: string): string {
  try {
    const date = new Date(iso)
    const now = new Date()
    const diffMs = now.getTime() - date.getTime()
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

    if (diffDays === 0) {
      return new Intl.DateTimeFormat('en-IN', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: true,
      }).format(date)
    } else if (diffDays === 1) {
      return 'Yesterday'
    } else if (diffDays < 7) {
      return new Intl.DateTimeFormat('en-IN', { weekday: 'short' }).format(date)
    } else {
      return new Intl.DateTimeFormat('en-IN', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      }).format(date)
    }
  } catch {
    return iso
  }
}

function actorLabel(actor: ChatMessage['actor']): string {
  return actor === 'DC' ? 'District Collector' : 'Temple Authority'
}

const hasMetadata = (m: string | null | undefined): boolean =>
  m != null && m.trim() !== '' && m.toLowerCase() !== 'null'

function getSiteVisitBadgeLabel(message: string): string {
  if (message === 'Site Visit Scheduled') return '📍 Site Visit Scheduled'
  if (message === 'Site Visit Completed') return '✅ Site Visit Done'
  return message
}

interface MessageBubbleProps {
  message: ChatMessage
  isActionRequired: boolean
}

function MessageBubble({ message, isActionRequired }: MessageBubbleProps) {
  const isDC = message.actor === 'DC'
  const isSiteVisit = message.type === 'SITE_VISIT'

  return (
    <div
      className={cn(
        'flex w-full mb-2',
        isDC ? 'justify-start' : 'justify-end',
      )}
    >
      <div
        className={cn(
          'max-w-[75%] rounded-2xl px-4 py-2.5 space-y-1 shadow-sm',
          isDC
            ? 'bg-white border border-border/60 text-foreground rounded-tl-sm'
            : 'bg-primary/10 border border-primary/20 text-foreground rounded-tr-sm',
        )}
      >
        {/* Header row: actor label + badges */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[10px] font-semibold tracking-wide uppercase text-muted-foreground">
            {actorLabel(message.actor)}
          </span>

          {isSiteVisit && (
            <Badge
              variant="outline"
              className="flex items-center gap-1 text-[10px] border-purple-300 text-purple-700 bg-purple-50"
            >
              <MapPin size={10} />
              {getSiteVisitBadgeLabel(message.message)}
            </Badge>
          )}

          {isActionRequired && (
            <Badge className="text-[10px] bg-orange-100 text-orange-800 border border-orange-300 hover:bg-orange-100">
              Action Required
            </Badge>
          )}
        </div>

        {/* Message text */}
        {!isSiteVisit && (
          <p className="text-sm leading-relaxed whitespace-pre-wrap break-words">
            {message.message}
          </p>
        )}

        {/* Metadata */}
        {hasMetadata(message.metadata) && (
          <p className="text-xs text-muted-foreground italic mt-1">{message.metadata}</p>
        )}

        {/* Timestamp */}
        <p className="text-[10px] text-muted-foreground text-right mt-1">
          {formatTimestamp(message.timestamp)}
        </p>
      </div>
    </div>
  )
}

function ChatSkeletonBubbles() {
  return (
    <div className="space-y-3 p-4">
      {[1, 2, 3].map((i) => (
        <div key={i} className={cn('flex', i % 2 === 0 ? 'justify-end' : 'justify-start')}>
          <div className="max-w-[60%] space-y-2">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-16 w-full rounded-2xl" />
          </div>
        </div>
      ))}
    </div>
  )
}

export function ChatModal({ declarationId, declarationStatus, readonly, unreadCount = 0 }: ChatModalProps) {
  const { data, isLoading, isError } = useGetConversationQuery(declarationId)
  const [open, setOpen] = useState(false)

  const messages: ChatMessage[] = data?.data ?? []
  const totalCount = messages.length

  // Only show button if there are messages or if clarification is required
  const shouldShowButton = totalCount > 0 || declarationStatus === 'CLARIFICATION_REQUIRED'

  // Find the index of the last CLARIFICATION message
  let lastClarificationIndex = -1
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].type === 'CLARIFICATION') {
      lastClarificationIndex = i
      break
    }
  }

  const showResponseBox = !readonly && declarationStatus === 'CLARIFICATION_REQUIRED'

  // Don't render button if not needed
  if (!shouldShowButton) {
    return null
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <div className="space-y-1.5">
        <DialogTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className="relative gap-2 border-primary/30 bg-primary/5 hover:bg-primary/10 hover:text-foreground"
          >
            <MessageCircle size={16} />
            <span>
              {declarationStatus === 'CLARIFICATION_REQUIRED' 
                ? 'Respond to Clarification' 
                : 'View Conversation'}
            </span>
            {totalCount > 0 && (
              <Badge variant="secondary" className="ml-1 h-5 min-w-[20px] rounded-full px-1.5 text-[10px]">
                {totalCount}
              </Badge>
            )}
            {unreadCount > 0 && (
              <span className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white">
                {unreadCount}
              </span>
            )}
          </Button>
        </DialogTrigger>
        {/* Description below button */}
        <p className="text-[10px] text-muted-foreground">
          {declarationStatus === 'CLARIFICATION_REQUIRED'
            ? 'DC has requested additional information'
            : totalCount > 0
            ? 'View communication with District Collector'
            : 'Communication history will appear here'}
        </p>
      </div>
      <DialogContent
        data-testid="chat-dialog-content"
        className="w-[calc(100%-2rem)] max-w-3xl h-[85vh] flex flex-col p-0 gap-0"
      >
        <DialogHeader className="shrink-0 px-6 py-4 border-b bg-gradient-to-r from-primary/5 to-primary/10">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10">
                <MessageCircle size={20} className="text-primary" />
              </div>
              <div>
                <DialogTitle className="text-lg">
                  {declarationStatus === 'CLARIFICATION_REQUIRED'
                    ? 'Clarification Required'
                    : 'Conversation History'}
                </DialogTitle>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {declarationStatus === 'CLARIFICATION_REQUIRED'
                    ? 'Please respond to the District Collector\'s request below'
                    : `Communication thread with District Collector • ${totalCount} ${totalCount === 1 ? 'message' : 'messages'}`}
                </p>
              </div>
            </div>
          </div>
        </DialogHeader>

        {/*
          Single scrollable region for everything below the header: info
          banners, the message list, and the response box all scroll
          together. Keeping the response box inside this same scroll
          container (rather than as a separate flex sibling) guarantees the
          Submit button is always reachable by scrolling — it can no longer
          get squeezed out of view when a short viewport can't fit
          everything at once. `min-h-0` is required so this flex child can
          shrink below its content and actually activate `overflow-y-auto`.
        */}
        <div data-testid="chat-scroll-area" className="flex-1 min-h-0 overflow-y-auto">
          {/* Info banner */}
          {declarationStatus === 'CLARIFICATION_REQUIRED' && (
            <div className="bg-orange-50 border-b border-orange-200 px-6 py-3">
              <p className="text-xs text-orange-800">
                💡 <strong>What is this?</strong> The District Collector has requested additional information or clarification about your declaration.
                Please review their message below and provide a response in the text box at the bottom.
              </p>
            </div>
          )}

          {!declarationStatus.includes('CLARIFICATION') && totalCount > 0 && (
            <div className="bg-blue-50 border-b border-blue-200 px-6 py-3">
              <p className="text-xs text-blue-800">
                💬 <strong>About this conversation:</strong> This is your communication history with the District Collector regarding this declaration.
                All messages, clarifications, and site visit updates are shown here.
              </p>
            </div>
          )}

          {/* Chat Messages Area */}
          <div
            data-testid="chat-messages-area"
            className="min-h-[200px] bg-muted/20 p-6"
          >
            {isLoading ? (
              <ChatSkeletonBubbles />
            ) : isError ? (
              <div className="flex h-full items-center justify-center">
                <EmptyState title="Could not load conversation history." />
              </div>
            ) : totalCount === 0 ? (
              <div className="flex h-full items-center justify-center">
                <EmptyState
                  title="No messages yet"
                  description="When the District Collector sends a message or requests clarification, it will appear here. You can respond directly through this interface."
                />
              </div>
            ) : (
              <div className="space-y-2">
                {messages.map((msg) => {
                  const originalIndex = messages.indexOf(msg)
                  const isActionRequired =
                    declarationStatus === 'CLARIFICATION_REQUIRED' &&
                    msg.type === 'CLARIFICATION' &&
                    originalIndex === lastClarificationIndex

                  return (
                    <MessageBubble
                      key={msg.id}
                      message={msg}
                      isActionRequired={isActionRequired}
                    />
                  )
                })}
              </div>
            )}
          </div>

          {/* Response Box */}
          {showResponseBox && (
            <div className="border-t bg-background px-6 py-4">
              <div className="mb-3">
                <p className="text-xs font-medium text-foreground mb-1">Your Response</p>
                <p className="text-[10px] text-muted-foreground">
                  Type your response to the District Collector's clarification request below. Be clear and provide all requested information.
                </p>
              </div>
              <ResponseBox declarationId={declarationId} />
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
