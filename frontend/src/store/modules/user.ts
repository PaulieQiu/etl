import { defineStore } from 'pinia'

/**
 * 用户 store 轻量 shim：独立运行时直接返回固定 userId。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    userId: 1,
    userName: 'admin'
  })
})
