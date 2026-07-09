import request from './request'

export const login = (data) => request.post('/auth/login', data)
export const fetchCaptcha = () => request.get('/auth/captcha')
export const fetchInfo = () => request.get('/auth/info')
export const fetchMenus = () => request.get('/auth/menus')
export const logout = () => request.post('/auth/logout')
export const changePassword = (data) => request.post('/auth/change-password', data)
