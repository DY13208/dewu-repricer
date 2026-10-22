import axios from 'axios'
import { ElMessage } from 'element-plus'
const http = axios.create({ baseURL: '/api', timeout: 30000 })
http.interceptors.request.use(c => { const u = localStorage.getItem('user'); if (u) c.headers['X-User'] = u; return c })
http.interceptors.response.use(r => r.data, e => { ElMessage.error(e.response?.data?.message || e.message); return Promise.reject(e) })
export default http
export const fen2yuan = v => (v == null ? '-' : (v / 100).toFixed(2))
export const yuan2fen = v => (v === '' || v == null ? null : Math.round(Number(v) * 100))
