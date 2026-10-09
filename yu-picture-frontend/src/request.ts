import axios from 'axios'
import { message } from 'ant-design-vue'
import { apiBaseUrl } from '@/config.ts'

const myAxios = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10000,
  withCredentials: true,
})

let redirecting = false
myAxios.interceptors.response.use((response) => {
  if (
    response.data.code === 40100 &&
    !response.config.url?.includes('/user/get/login') &&
    window.location.pathname !== '/user/login' &&
    !redirecting
  ) {
    redirecting = true
    message.warning('请先登录')
    const redirect = window.location.pathname + window.location.search + window.location.hash
    window.location.href = `/user/login?${new URLSearchParams({ redirect })}`
  }
  return response
})

export default myAxios
