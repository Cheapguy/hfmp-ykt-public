import request from './request'

// CRUD 工厂：对齐后端 BaseCrudController 约定
function makeCrud(base) {
  return {
    page: (params) => request.get(`${base}/page`, { params }),
    list: (params) => request.get(`${base}/list`, { params }),
    detail: (id) => request.get(`${base}/${id}`),
    save: (data) => data?.id ? request.put(base, data) : request.post(base, data),
    delete: (id) => request.delete(`${base}/${id}`)
  }
}

// ===== 安全管理 =====
export const menuApi = { ...makeCrud('/sys/menu'), tree: () => request.get('/sys/menu/tree') }
export const orgApi  = { ...makeCrud('/sys/org'),  tree: () => request.get('/sys/org/tree') }
export const roleApi = makeCrud('/sys/role')
export const userApi = {
  ...makeCrud('/sys/user'),
  getDataScope:  (id) => request.get(`/sys/user/${id}/data-scope`),
  saveDataScope: (id, data) => request.post(`/sys/user/${id}/data-scope`, data)
}
export const roleMenuApi = {
  getMenuIds: (id) => request.get(`/sys/role/${id}/menu-ids`),
  assign:     (id, menuIds) => request.put(`/sys/role/${id}/menus`, { menuIds })
}
export const userRoleApi = {
  getRoleIds: (id) => request.get(`/sys/user/${id}/roles`),
  assign:     (id, roleIds) => request.post(`/sys/user/${id}/roles`, roleIds),
  resetPwd:   (id) => request.post(`/sys/user/${id}/reset-password`)
}

// ===== 系统设置 =====
export const bankApi    = makeCrud('/setup/bank')
export const villageApi = makeCrud('/setup/village')
export const beneficiaryApi = {
  ...makeCrud('/setup/beneficiary'),
  refer:    (idCard) => request.get('/setup/beneficiary/refer', { params: { idCard } }),
  cancel:   (id) => request.post(`/setup/beneficiary/${id}/cancel`),
  uncancel: (id) => request.post(`/setup/beneficiary/${id}/uncancel`)
}

// ===== 主管部门 =====
export const projectApi = {
  ...makeCrud('/dept/project'),
  submit:  (ids, opinion) => request.post('/dept/project/submit', { ids, opinion }),
  approve: (ids, opinion, officeCode, officeName) => request.post('/dept/project/approve', { ids, opinion, officeCode, officeName }),
  reject:  (ids, opinion) => request.post('/dept/project/reject', { ids, opinion }),
  offices: () => request.get('/dept/project/offices'),
  history: (id) => request.get(`/dept/project/${id}/history`),
  revoke:  (id) => request.post(`/dept/project/${id}/revoke`),
  include: (id) => request.post(`/dept/project/${id}/include`),
  link:    (id, catalogCode) => request.post(`/dept/project/${id}/link`, null, { params: { catalogCode } }),
  // 纳入及挂接（批量）
  includable:  (params) => request.get('/dept/project/includable', { params }),
  includeBatch:(ids) => request.post('/dept/project/include-batch', { ids }),
  linkBatch:   (ids, catalogCode, catalogName) => request.post('/dept/project/link-batch', { ids, catalogCode, catalogName }),
  unlinkBatch: (ids) => request.post('/dept/project/unlink-batch', { ids }),
  central:     (params) => request.get('/dept/project/central', { params })
}
// 通知公告管理：CRUD + 上传 + 下发单位候选/下发 + 乡镇端下载
export const noticeApi = {
  ...makeCrud('/dept/notice'),
  upload:   (formData) => request.post('/dept/notice/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  towns:    () => request.get('/dept/notice/towns'),
  dispatch: (noticeId, orgIds) => request.post('/dept/notice/dispatch', { noticeId, orgIds }),
  mine:     () => request.get('/dept/notice/mine')
}
export const policyApi = {
  ...makeCrud('/dept/policy'),
  discard: (id) => request.post(`/dept/policy/${id}/discard`)
}
// 政策关联项目：项目树 + 已关联政策 + 候选政策弹窗 + 关联/取消关联
export const projectPolicyApi = {
  projects:   (params) => request.get('/dept/project-policy/projects', { params }),
  linked:     (projectId) => request.get('/dept/project-policy/linked', { params: { projectId } }),
  candidates: (params) => request.get('/dept/project-policy/candidates', { params }),
  link:       (projectId, policyIds) => request.post('/dept/project-policy/link', { projectId, policyIds }),
  unlink:     (projectId, policyIds) => request.post('/dept/project-policy/unlink', { projectId, policyIds })
}
export const batchApi = {
  ...makeCrud('/dept/batch'),
  towns:       () => request.get('/dept/batch/towns'),
  batchCreate: (data) => request.post('/dept/batch/batch-create', data),
  issue:       (id) => request.post(`/dept/batch/${id}/issue`),
  cancelIssue: (id) => request.post(`/dept/batch/${id}/cancel-issue`),
  send:        (id) => request.post(`/dept/batch/${id}/send`),
  cancelSend:  (id) => request.post(`/dept/batch/${id}/cancel-send`)
}

// ===== 更正发放 / 批次重构 =====
export const correctionApi = {
  list:    (params) => request.get('/dept/correction/list', { params }),
  rebuild: (detailIds) => request.post('/dept/correction/rebuild', { detailIds })
}

// ===== 花名册 =====
export const rosterApi = {
  page:      (params) => request.get('/roster/page', { params }),
  save:      (data) => request.post('/roster', data),
  delete:    (id) => request.delete(`/roster/${id}`),
  batchFill: (data) => request.post('/roster/batch-fill', data),
  submit:    (id) => request.post(`/roster/${id}/submit`),
  audit:     (id) => request.post(`/roster/${id}/audit`),
  back:      (id) => request.post(`/roster/${id}/return`)
}

// ===== 集中支付（上级系统）=====
export const quotaApi = {
  list:       (projectId) => request.get('/pay/quota/list', { params: { projectId } }),
  indicators: (params) => request.get('/pay/quota/indicators', { params }),
  link:       (projectId, indicatorIds) => request.post('/pay/quota/link', { projectId, indicatorIds }),
  unlink:     (ids) => request.post('/pay/quota/unlink', { ids }),
  saveRule:   (data) => request.post('/pay/quota/save-rule', data)
}
export const paymentApi = {
  pending: (projectId) => request.get('/pay/apply/pending', { params: { projectId } }),
  paid:    (projectId) => request.get('/pay/apply/paid', { params: { projectId } }),
  preview: (batchId) => request.get('/pay/apply/preview', { params: { batchId } }),
  gen:     (data) => request.post('/pay/apply/gen', data),
  revoke:  (batchId) => request.post('/pay/apply/revoke', { batchId }),
  detail:  (batchId) => request.get(`/pay/apply/${batchId}/detail`),
  page:    (params) => request.get('/pay/apply/page', { params }),
  submitList: (status) => request.get('/pay/apply/submit-list', { params: { status } }),
  submit:  (id) => request.post(`/pay/apply/${id}/submit`),
  grantList: (id) => request.get(`/pay/apply/${id}/grant-list`),
  bankPay: (id, fails) => request.post(`/pay/apply/${id}/bank-pay`, { fails }),
  remove:  (id) => request.delete(`/pay/apply/${id}`)
}

// ===== 发放数据审核 =====
export const auditApi = {
  page:      (params) => request.get('/dept/audit/page', { params }),
  history:   (id) => request.get(`/dept/audit/${id}/history`),
  audit:       (ids, opinion) => request.post('/dept/audit/audit', { ids, opinion }),
  cancelAudit: (ids, opinion) => request.post('/dept/audit/cancel-audit', { ids, opinion }),
  reject:      (ids, opinion) => request.post('/dept/audit/reject', { ids, opinion }),
  rosters:   (id) => request.get(`/dept/audit/${id}/rosters`),
  checkBank: (id) => request.get(`/dept/audit/${id}/check-bank`),
  sum:       (params) => request.get('/dept/audit/sum', { params })
}

// ===== 系统管理-查询 =====
export const queryApi = {
  page:    (params) => request.get('/dept/query/page', { params }),
  batches: (params) => request.get('/dept/query/batches', { params }),
  history: (id) => request.get(`/dept/query/${id}/history`),
  roster:        (params) => request.get('/dept/query/roster', { params }),
  villageSummary:(batchIds) => request.get('/dept/query/village-summary', { params: { batchIds } }),
  exportRoster:  (batchIds) => request.get('/dept/query/roster/export', { params: { batchIds }, responseType: 'blob' })
}

// ===== 编制花名册 =====
export const rosterEditApi = {
  pending:    () => request.get('/dept/roster/pending'),
  info:       (batchId) => request.get(`/dept/roster/${batchId}/info`),
  page:       (params) => request.get('/dept/roster/page', { params }),
  save:       (data) => request.post('/dept/roster', data),
  batchSave:  (list) => request.post('/dept/roster/batch-save', list),
  remove:     (ids) => request.delete('/dept/roster', { params: { ids } }),
  batchFill:  (data) => request.post('/dept/roster/batch-fill', data),
  fillCandidates: (params) => request.get('/dept/roster/fill-candidates', { params }),
  fillSave:   (data) => request.post('/dept/roster/fill-save', data),
  submit:     (batchId) => request.post(`/dept/roster/${batchId}/submit`),
  unsubmit:   (batchId) => request.post(`/dept/roster/${batchId}/unsubmit`),
  stopDetails:(detailIds, reason) => request.post('/dept/roster/stop-details', { detailIds, reason }),
  deleteBatch:(batchId) => request.delete(`/dept/roster/batch/${batchId}`),
  summary:    (batchId) => request.get(`/dept/roster/${batchId}/summary`),
  importExcel:(batchId, formData) => request.post('/dept/roster/import', formData, { params: { batchId }, headers: { 'Content-Type': 'multipart/form-data' } }),
  exportUrl:  (batchIds) => request.get('/dept/query/roster/export', { params: { batchIds }, responseType: 'blob' })
}

// ===== 机构/部门字典 =====
export const agencyApi = {
  list: (params) => request.get('/agency/list', { params }),
  tree: () => request.get('/agency/tree'),
  villages: (townId) => request.get('/agency/villages', { params: { townId } })
}

// ===== 惠民报表 =====
export const reportApi = {
  person:      (params) => request.get('/report/person', { params }),
  detail:      (params) => request.get('/report/detail', { params }),
  project:     (params) => request.get('/report/project', { params }),
  deptProject: (params) => request.get('/report/dept-project', { params }),
  usage:       (params) => request.get('/report/usage', { params })
}

// ===== 工作台 =====
export const dashboardApi = { summary: () => request.get('/dashboard/summary') }
