import request from './request'

export interface NotificationVO {
  id: number
  type: string
  level: string
  content: string
  read: boolean
  projectId?: number
  projectName?: string
  createTime: string
}

export const listNotifications = (): Promise<NotificationVO[]> => {
  return request.get<NotificationVO[]>('/api/notifications')
}

export const markNotificationRead = (id: number): Promise<void> => {
  return request.put<void>(`/api/notifications/${id}/read`)
}

export const markAllNotificationsRead = (): Promise<void> => {
  return request.put<void>('/api/notifications/read-all')
}

export const deleteNotification = (id: number): Promise<void> => {
  return request.delete<void>(`/api/notifications/${id}`)
}
