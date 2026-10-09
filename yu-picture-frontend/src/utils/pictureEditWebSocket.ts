import { apiBaseUrl } from '@/config.ts'

export interface PictureEditMessage {
  type: string
  message?: string
  editAction?: string
  user?: API.UserVO
  isEditor?: boolean
}

type Handler = (message: PictureEditMessage) => void

export default class PictureEditWebSocket {
  private socket: WebSocket | null = null
  private eventHandlers: Record<string, Handler[]> = {}

  constructor(private pictureId: API.Id) {}

  connect() {
    this.disconnect()
    const base = import.meta.env.VITE_WS_BASE_URL || apiBaseUrl
    const url = new URL(`${base.replace(/\/$/, '')}/ws/picture/edit`, window.location.origin)
    url.protocol = url.protocol === 'https:' || url.protocol === 'wss:' ? 'wss:' : 'ws:'
    url.searchParams.set('pictureId', String(this.pictureId))
    const socket = new WebSocket(url.toString())
    this.socket = socket

    socket.onopen = () => {
      if (this.socket === socket) this.triggerEvent('open')
    }
    socket.onmessage = (event) => {
      if (this.socket !== socket) return
      let data: PictureEditMessage
      try {
        const parsed: unknown = JSON.parse(event.data)
        if (!parsed || typeof parsed !== 'object' || !('type' in parsed) || typeof parsed.type !== 'string') return
        data = parsed as PictureEditMessage
      } catch {
        return
      }
      this.triggerEvent(data.type, data)
    }
    socket.onclose = () => {
      if (this.socket !== socket) return
      this.socket = null
      this.triggerEvent('close')
    }
    socket.onerror = () => {
      if (this.socket === socket) this.triggerEvent('error')
    }
  }

  disconnect() {
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null
      socket.close()
    }
  }

  sendMessage(message: object): boolean {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false
    this.socket.send(JSON.stringify(message))
    return true
  }

  on(type: string, handler: Handler) {
    ;(this.eventHandlers[type] ??= []).push(handler)
  }

  private triggerEvent(type: string, data: PictureEditMessage = { type }) {
    this.eventHandlers[type]?.forEach((handler) => handler(data))
  }
}
